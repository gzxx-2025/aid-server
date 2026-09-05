'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  UserSkillDefinition,
  UserSkillInputAnswer,
  UserSkillInputRequest,
  UserSkillInputResponseRequest,
  UserSkillRuntimeOutputDelta,
  UserSkillRuntimeResponseMode,
  UserSkillRuntimeRunHandle
} from '~/types/user-skill'
import {
  userSkillRuntimeCatalog,
  userSkillRuntimeInvoke,
  userSkillRuntimeRespondInput,
  userSkillRuntimeRunCancel,
  userSkillRuntimeRunDetail,
  userSkillRuntimeRunHistory
} from '~/utils/businessApi'
import {
  createUserSkillClientMessageId,
  patchStoryScriptAgentState,
  readStoryScriptAgentState,
  type StoryScriptAgentProjectState,
  type StoryScriptAgentRuntimeCheckpoint,
  writeStoryScriptAgentState
} from '~/utils/storyScriptAgentState'
import { streamUserSkillRuntimeEvents, parseUserSkillRuntimeOutputDelta } from '~/utils/userSkillRuntimeEventStream'
import {
  recoverUserSkillRuntimeMilestones,
  runtimeMilestonePresentation
} from '~/utils/storyScriptAgentRuntimeEvents'
import {
  completedStatusText,
  isRuntimeTerminalStatus,
  isScreenplayRuntimeSkill,
  mergeRuntimeSkills,
  mergeRuntimeThinkingStep,
  runtimeExecutionKey,
  runtimeResponseMode,
  runtimeStageText,
  runtimeThinkingStep
} from '~/utils/storyScriptAgentRuntime'
import type { AgentThinkingStep } from '~/utils/agentThinkingSteps'
import type { EditorTextSelection } from '~/utils/quill/editorTextSelection'
import {
  buildStoryScriptAgentPrompt,
  normalizeStoryScriptAgentReferences,
  parseStoryScriptAgentPrompt
} from '~/utils/storyScriptAgentReference'
import { normalizeUserSkillInputRequest } from '~/utils/storyScriptAgentClarification'
import { resolveFlowShortcutSkillCode } from '~/utils/storyScriptAgentSkillPicker'

const RUN_POLL_INTERVAL_MS = 2400
const MAX_CHAT_PROMPT_CHARS = 100_000
const MAX_RUNTIME_REFERENCES = 20
const MAX_RUNTIME_REFERENCE_CHARS = 20_000
const MAX_RUNTIME_REFERENCE_TOTAL_CHARS = 20_000
const HISTORY_PAGE_SIZE = 30

export type StoryScriptAgentMessageStatus = 'complete' | 'streaming' | 'error' | 'stopped'

export interface StoryScriptAgentViewMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  thinkingSteps?: AgentThinkingStep[]
  thinkingStartedAt?: string | null
  thinkingCompletedAt?: string | null
  runId?: number | null
  clientMessageId?: string | null
  status: StoryScriptAgentMessageStatus
  references?: EditorTextSelection[]
  responseMode?: UserSkillRuntimeResponseMode
  inputRequest?: UserSkillInputRequest
  pendingInputResponse?: UserSkillInputResponseRequest | null
  partialOutputTrusted?: boolean
}

interface UseStoryScriptAgentOptions {
  projectId: number | null
  episodeId: number | null
  projectTitle: string
  projectStyle: string
  enabled?: boolean
  /** 剧本步骤页只暴露当前创作所需的编剧 Skill。 */
  catalogScope?: 'screenplay' | 'all'
}

function waitForNextPoll(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    const onAbort = () => {
      window.clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, RUN_POLL_INTERVAL_MS)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

function isDefinitiveRuntimeBusinessRejection(error: unknown): boolean {
  if (!error || typeof error !== 'object' || error instanceof Error) return false
  const code = Number((error as { code?: unknown }).code)
  return Number.isFinite(code) && code !== 0 && code !== 200
}

function runtimeErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) return error.message
  if (error && typeof error === 'object') {
    const payload = error as { msg?: unknown; message?: unknown }
    const message = String(payload.msg ?? payload.message ?? '').trim()
    if (message) return message
  }
  return fallback
}

function sameInputRequestBundle(
  left: Pick<UserSkillInputRequest, 'requestId' | 'contextVersion' | 'schemaDigest'>,
  right: Pick<UserSkillInputRequest, 'requestId' | 'contextVersion' | 'schemaDigest'>
): boolean {
  return left.requestId === right.requestId
    && left.contextVersion === right.contextVersion
    && left.schemaDigest === right.schemaDigest
}

function checkpointMessages(checkpoint: StoryScriptAgentRuntimeCheckpoint): StoryScriptAgentViewMessage[] {
  return [
    {
      id: `user-${checkpoint.idempotencyKey}`,
      role: 'user',
      content: checkpoint.prompt,
      runId: checkpoint.runId,
      clientMessageId: checkpoint.idempotencyKey,
      status: 'complete',
      references: checkpoint.references
    },
    {
      id: `assistant-${checkpoint.idempotencyKey}`,
      role: 'assistant',
      content: '',
      runId: checkpoint.runId,
      status: 'streaming',
      thinkingStartedAt: new Date().toISOString(),
      responseMode: checkpoint.responseMode,
      references: checkpoint.references,
      partialOutputTrusted: checkpoint.partialOutputTrusted === true
    }
  ]
}

function historyRunMessages(handle: UserSkillRuntimeRunHandle): StoryScriptAgentViewMessage[] {
  const rawPrompt = String(handle.prompt || '').trim()
  const parsedPrompt = parseStoryScriptAgentPrompt(rawPrompt)
  const responseMode = runtimeResponseMode(handle, 'SCREENPLAY')
  const reviewText = String(handle.reviewReport || '')
  const terminalContent = handle.status === 'SUCCEEDED'
    ? String(handle.assistantMessage || handle.outputText || reviewText || '')
    : handle.status === 'CANCELED'
      ? String(handle.errorMessage || '生成已停止')
      : String(handle.errorMessage || 'Skill 运行失败')
  const status: StoryScriptAgentMessageStatus = handle.status === 'SUCCEEDED'
    ? 'complete'
    : handle.status === 'CANCELED' ? 'stopped' : 'error'
  return [
    {
      id: `user-run-${handle.runId}`,
      role: 'user',
      content: parsedPrompt.instruction || rawPrompt,
      runId: handle.runId,
      status: 'complete',
      references: parsedPrompt.references
    },
    {
      id: `assistant-run-${handle.runId}`,
      role: 'assistant',
      content: terminalContent,
      runId: handle.runId,
      status,
      responseMode,
      references: parsedPrompt.references,
      partialOutputTrusted: handle.status === 'SUCCEEDED'
    }
  ]
}

function runtimeMessageIdentity(message: StoryScriptAgentViewMessage): string {
  return message.runId
    ? `${message.role}:run:${message.runId}`
    : `${message.role}:id:${message.id}`
}

function mergeHydratedMessages(
  history: StoryScriptAgentViewMessage[],
  current: StoryScriptAgentViewMessage[]
): StoryScriptAgentViewMessage[] {
  const currentByIdentity = new Map(current.map((message) => [runtimeMessageIdentity(message), message]))
  const historyIdentities = new Set<string>()
  const merged = history.map((message) => {
    const identity = runtimeMessageIdentity(message)
    historyIdentities.add(identity)
    return currentByIdentity.get(identity) || message
  })
  for (const message of current) {
    if (!historyIdentities.has(runtimeMessageIdentity(message))) merged.push(message)
  }
  return merged
}

export function useStoryScriptAgent({
  projectId,
  episodeId,
  projectStyle,
  enabled = true,
  catalogScope = 'screenplay'
}: UseStoryScriptAgentOptions) {
  const [open, setOpen] = useState(false)
  const [skills, setSkills] = useState<UserSkillDefinition[]>([])
  const [selectedSkillCode, setSelectedSkillCode] = useState('')
  const [messages, setMessages] = useState<StoryScriptAgentViewMessage[]>([])
  const [skillsLoading, setSkillsLoading] = useState(false)
  const [skillsError, setSkillsError] = useState('')
  const [messagesLoading, setMessagesLoading] = useState(false)
  const [olderMessagesLoading, setOlderMessagesLoading] = useState(false)
  const [hasOlderMessages, setHasOlderMessages] = useState(false)
  const [sending, setSending] = useState(false)
  const [statusText, setStatusText] = useState('Agent 已就绪')
  const [lastError, setLastError] = useState('')
  const [canRetry, setCanRetry] = useState(false)
  const [canStop, setCanStop] = useState(false)
  const [paused, setPaused] = useState(false)
  const [resumePending, setResumePending] = useState(false)
  const [stopping, setStopping] = useState(false)

  const stateRef = useRef<StoryScriptAgentProjectState | null>(null)
  const activeAbortRef = useRef<AbortController | null>(null)
  const sendingRef = useRef(false)
  const stoppingRef = useRef(false)
  const stopOperationRef = useRef<symbol | null>(null)
  const scopeProjectRef = useRef<number | null>(projectId)
  const scopeEpisodeRef = useRef<number | null>(episodeId)
  const projectStyleRef = useRef(projectStyle)
  const runtimeDetailInflightRef = useRef(new Map<number, Promise<UserSkillRuntimeRunHandle>>())
  const runtimeFinalizeInflightRef = useRef(new Map<string, Promise<void>>())
  const runtimeTerminalAppliedRef = useRef(new Set<string>())
  const runtimeOutputStartedRef = useRef(new Set<string>())
  const runtimeDeltaBufferRef = useRef(new Map<string, { content: string; reset: boolean }>())
  const runtimeDeltaStepRef = useRef(new Map<string, string>())
  const runtimeReasoningBufferRef = useRef(new Map<string, { content: string; reset: boolean }>())
  const runtimeReasoningStepRef = useRef(new Map<string, string>())
  const runtimeReasoningFrameRef = useRef<number | null>(null)
  const runtimeDeltaFrameRef = useRef<number | null>(null)
  const historyGenerationRef = useRef(0)
  const historyBeforeRunIdRef = useRef<number | null>(null)
  const skillCatalogRef = useRef<UserSkillDefinition[]>([])
  const skillCatalogLoadedRef = useRef(false)
  const skillCatalogInflightRef = useRef<Promise<void> | null>(null)
  const skillCatalogGenerationRef = useRef(0)

  const loadSkills = useCallback((): Promise<void> => {
    if (!enabled || skillCatalogLoadedRef.current) return Promise.resolve()
    const running = skillCatalogInflightRef.current
    if (running) return running
    const generation = skillCatalogGenerationRef.current
    setSkillsLoading(true)
    setSkillsError('')
    const holder: { promise?: Promise<void> } = {}
    const pending = userSkillRuntimeCatalog()
      .then((items) => {
        if (skillCatalogGenerationRef.current !== generation) return
        const availableSkills = catalogScope === 'all'
          ? items
          : items.filter(isScreenplayRuntimeSkill)
        skillCatalogRef.current = availableSkills
        skillCatalogLoadedRef.current = true
        const merged = mergeRuntimeSkills(availableSkills, stateRef.current)
        setSkills(merged)
        setSelectedSkillCode((current) => merged.some((item) => item.skillCode === current)
          ? current
          : String(merged[0]?.skillCode || ''))
      })
      .catch((error: unknown) => {
        if (skillCatalogGenerationRef.current !== generation) return
        setSkillsError(runtimeErrorMessage(error, 'Skill 列表加载失败'))
      })
      .finally(() => {
        if (skillCatalogInflightRef.current === holder.promise) {
          skillCatalogInflightRef.current = null
        }
        if (skillCatalogGenerationRef.current === generation) setSkillsLoading(false)
      })
    holder.promise = pending
    skillCatalogInflightRef.current = pending
    return pending
  }, [catalogScope, enabled])

  useEffect(() => {
    if (!enabled) {
      skillCatalogGenerationRef.current += 1
      skillCatalogRef.current = []
      skillCatalogLoadedRef.current = false
      skillCatalogInflightRef.current = null
      // Disabling the runtime must synchronously remove the previous authenticated catalog from view.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSkills([])
      setSelectedSkillCode('')
      setSkillsError('')
      setSkillsLoading(false)
      return
    }
    void loadSkills()
    return () => {
      skillCatalogGenerationRef.current += 1
      skillCatalogInflightRef.current = null
    }
  }, [enabled, loadSkills])

  useEffect(() => {
    projectStyleRef.current = projectStyle
  }, [projectStyle])

  const isCurrentProject = useCallback((expectedProjectId: number) => (
    scopeProjectRef.current === expectedProjectId && scopeEpisodeRef.current === episodeId
  ), [episodeId])

  const persistPatch = useCallback((
    expectedProjectId: number,
    patch: Partial<Omit<StoryScriptAgentProjectState, 'version' | 'projectId' | 'episodeId'>>
  ) => {
    if (!isCurrentProject(expectedProjectId) || episodeId == null) return null
    const updated = patchStoryScriptAgentState(expectedProjectId, episodeId, patch)
    if (updated) {
      stateRef.current = updated
      setCanRetry(Boolean(updated.activeRun || updated.pendingPrompt))
      setCanStop(Boolean(updated.activeRun))
      setPaused(Boolean(updated.paused && updated.activeRun))
    }
    return updated
  }, [episodeId, isCurrentProject])

  const rememberLatestHistoryRun = useCallback((
    expectedProjectId: number,
    skillCode: string,
    lastRunId: number
  ) => {
    if (!isCurrentProject(expectedProjectId) || episodeId == null) return
    const current = stateRef.current
    if (current?.skill.skillCode === skillCode) {
      persistPatch(expectedProjectId, { lastRunId })
      return
    }
    const skill = skillCatalogRef.current.find((item) => item.skillCode === skillCode)
    if (!skill) return
    const next: StoryScriptAgentProjectState = {
      version: 3,
      projectId: expectedProjectId,
      episodeId,
      skill,
      autoOpen: false,
      lastRunId,
      activeRun: null,
      pendingInputResponse: null,
      paused: false,
      updatedAt: Date.now()
    }
    if (writeStoryScriptAgentState(next)) stateRef.current = next
  }, [episodeId, isCurrentProject, persistPatch])

  const updateAssistant = useCallback((
    idempotencyKey: string,
    patch: Partial<StoryScriptAgentViewMessage>
  ) => {
    setMessages((current) => {
      const id = `assistant-${idempotencyKey}`
      const index = current.findIndex((item) => item.id === id)
      if (index < 0) {
        return [...current, {
          id,
          role: 'assistant',
          content: '',
          status: 'streaming',
          ...patch
        }]
      }
      const next = [...current]
      next[index] = { ...next[index], ...patch }
      return next
    })
  }, [])

  const markPartialOutputUntrusted = useCallback((
    expectedProjectId: number,
    checkpoint: StoryScriptAgentRuntimeCheckpoint
  ) => {
    const nextCheckpoint = checkpoint.partialOutputTrusted === false
      ? checkpoint
      : { ...checkpoint, partialOutputTrusted: false }
    persistPatch(expectedProjectId, { activeRun: nextCheckpoint })
    updateAssistant(nextCheckpoint.idempotencyKey, { partialOutputTrusted: false })
    return nextCheckpoint
  }, [persistPatch, updateAssistant])

  const advanceAssistantThinking = useCallback((
    idempotencyKey: string,
    stage?: string | null,
    message?: string | null,
    startedAt?: string | null,
    identity?: string | number | null
  ) => {
    setMessages((current) => {
      const id = `assistant-${idempotencyKey}`
      const index = current.findIndex((item) => item.id === id)
      if (index < 0) return current
      const item = current[index]
      let nextStep = runtimeThinkingStep(stage, message, startedAt, identity)
      const existing = item.thinkingSteps?.find((step) => (
        step.id === nextStep.id || step.label === nextStep.label
      ))
      if (!item.thinkingCompletedAt && existing?.status === 'active' && existing.label === nextStep.label) {
        return current
      }
      if (existing) nextStep = { ...nextStep, id: existing.id }
      const next = [...current]
      next[index] = {
        ...item,
        thinkingStartedAt: item.thinkingStartedAt || startedAt || new Date().toISOString(),
        thinkingCompletedAt: null,
        thinkingSteps: mergeRuntimeThinkingStep(item.thinkingSteps, nextStep)
      }
      return next
    })
  }, [])

  const completeAssistantThinking = useCallback((idempotencyKey: string) => {
    setMessages((current) => {
      const id = `assistant-${idempotencyKey}`
      const index = current.findIndex((item) => item.id === id)
      if (index < 0 || current[index].thinkingCompletedAt) return current
      const next = [...current]
      next[index] = {
        ...current[index],
        thinkingSteps: current[index].thinkingSteps?.map((step) => ({
          ...step,
          status: 'done' as const
        })),
        thinkingCompletedAt: new Date().toISOString()
      }
      return next
    })
  }, [])

  const flushAssistantDeltas = useCallback(() => {
    if (runtimeDeltaFrameRef.current != null && typeof window !== 'undefined') {
      window.cancelAnimationFrame(runtimeDeltaFrameRef.current)
    }
    runtimeDeltaFrameRef.current = null
    const buffered = runtimeDeltaBufferRef.current
    if (!buffered.size) return
    runtimeDeltaBufferRef.current = new Map()
    setMessages((current) => {
      let changed = false
      const next = current.map((item) => {
        const delta = buffered.get(item.id)
        if (!delta?.content) return item
        changed = true
        return {
          ...item,
          content: delta.reset ? delta.content : `${item.content}${delta.content}`
        }
      })
      return changed ? next : current
    })
  }, [])

  const queueAssistantDelta = useCallback((
    idempotencyKey: string,
    delta: UserSkillRuntimeOutputDelta
  ) => {
    if (!delta.content || typeof window === 'undefined') return
    const id = `assistant-${idempotencyKey}`
    const current = runtimeDeltaBufferRef.current.get(id)
    const stepExecutionId = String(delta.stepExecutionId || '').trim()
    const stepChanged = Boolean(stepExecutionId && runtimeDeltaStepRef.current.get(id) !== stepExecutionId)
    if (stepExecutionId) runtimeDeltaStepRef.current.set(id, stepExecutionId)
    const reset = stepChanged || delta.reset === true
    runtimeDeltaBufferRef.current.set(id, reset
      ? { content: delta.content, reset: true }
      : { content: `${current?.content || ''}${delta.content}`, reset: current?.reset === true })
    if (runtimeDeltaFrameRef.current != null) return
    runtimeDeltaFrameRef.current = window.requestAnimationFrame(flushAssistantDeltas)
  }, [flushAssistantDeltas])

  const flushAssistantReasoningDeltas = useCallback(() => {
    if (runtimeReasoningFrameRef.current != null && typeof window !== 'undefined') {
      window.cancelAnimationFrame(runtimeReasoningFrameRef.current)
    }
    runtimeReasoningFrameRef.current = null
    const buffered = runtimeReasoningBufferRef.current
    if (!buffered.size) return
    runtimeReasoningBufferRef.current = new Map()
    setMessages((current) => {
      let changed = false
      const next = current.map((item) => {
        const delta = buffered.get(item.id)
        if (!delta?.content) return item
        changed = true
        return {
          ...item,
          thinkingStartedAt: item.thinkingStartedAt || new Date().toISOString(),
          reasoning: delta.reset ? delta.content : `${item.reasoning || ''}${delta.content}`
        }
      })
      return changed ? next : current
    })
  }, [])

  const queueAssistantReasoningDelta = useCallback((
    idempotencyKey: string,
    delta: UserSkillRuntimeOutputDelta
  ) => {
    if (!delta.content || typeof window === 'undefined') return
    const id = `assistant-${idempotencyKey}`
    const current = runtimeReasoningBufferRef.current.get(id)
    const stepExecutionId = String(delta.stepExecutionId || '').trim()
    const stepChanged = Boolean(
      stepExecutionId && runtimeReasoningStepRef.current.get(id) !== stepExecutionId
    )
    if (stepExecutionId) runtimeReasoningStepRef.current.set(id, stepExecutionId)
    const reset = stepChanged || delta.reset === true
    runtimeReasoningBufferRef.current.set(id, reset
      ? { content: delta.content, reset: true }
      : { content: `${current?.content || ''}${delta.content}`, reset: current?.reset === true })
    if (runtimeReasoningFrameRef.current != null) return
    runtimeReasoningFrameRef.current = window.requestAnimationFrame(flushAssistantReasoningDeltas)
  }, [flushAssistantReasoningDeltas])

  const bindRunId = useCallback((idempotencyKey: string, runId: number) => {
    setMessages((current) => current.map((message) => (
      message.id === `user-${idempotencyKey}` || message.id === `assistant-${idempotencyKey}`
        ? { ...message, runId }
        : message
    )))
  }, [])

  const readRuntimeRunDetailOnce = useCallback((runId: number) => {
    const existing = runtimeDetailInflightRef.current.get(runId)
    if (existing) return existing
    const pending = userSkillRuntimeRunDetail(runId)
      .finally(() => runtimeDetailInflightRef.current.delete(runId))
    runtimeDetailInflightRef.current.set(runId, pending)
    return pending
  }, [])

  const finalizeRuntimeRunOnce = useCallback((
    expectedProjectId: number,
    checkpoint: StoryScriptAgentRuntimeCheckpoint,
    handle: UserSkillRuntimeRunHandle
  ): Promise<void> => {
    const key = `${handle.runId}:${handle.generation}:${handle.status}`
    const existing = runtimeFinalizeInflightRef.current.get(key)
    if (existing) return existing
    const pending = Promise.resolve().then(() => {
      if (!isCurrentProject(expectedProjectId) || runtimeTerminalAppliedRef.current.has(key)) return
      runtimeTerminalAppliedRef.current.add(key)
      flushAssistantDeltas()
      if (runtimeReasoningFrameRef.current != null && typeof window !== 'undefined') {
        window.cancelAnimationFrame(runtimeReasoningFrameRef.current)
        runtimeReasoningFrameRef.current = null
      }
      flushAssistantReasoningDeltas()
      runtimeOutputStartedRef.current.delete(runtimeExecutionKey(handle.runId, handle.generation))
      runtimeDeltaStepRef.current.delete(`assistant-${checkpoint.idempotencyKey}`)
      runtimeReasoningStepRef.current.delete(`assistant-${checkpoint.idempotencyKey}`)
      persistPatch(expectedProjectId, {
        activeRun: null,
        lastRunId: handle.runId,
        pendingInputResponse: null,
        pendingPrompt: undefined,
        paused: false
      })
      const responseMode = runtimeResponseMode(handle, checkpoint.responseMode)
      const reviewText = String(handle.reviewReport || '')
      const terminalContent = handle.status === 'SUCCEEDED'
        ? String(handle.assistantMessage || handle.outputText || reviewText || '')
        : String(handle.errorMessage || 'Skill 运行失败')
      completeAssistantThinking(checkpoint.idempotencyKey)
      setMessages((current) => current.map((item) => item.id === `assistant-${checkpoint.idempotencyKey}`
        ? {
            ...item,
            runId: handle.runId,
            content: handle.status === 'CANCELED' ? item.content : terminalContent,
            responseMode,
            inputRequest: undefined,
            partialOutputTrusted: handle.status === 'SUCCEEDED'
              ? true
              : handle.status === 'CANCELED' && checkpoint.partialOutputTrusted === true,
            status: handle.status === 'SUCCEEDED'
              ? 'complete' as const
              : handle.status === 'CANCELED' ? 'stopped' as const : 'error' as const
          }
        : item))
      setCanRetry(false)
      setCanStop(false)
      setPaused(false)
      if (handle.status === 'FAILED') setLastError(terminalContent)
      setStatusText(handle.status === 'SUCCEEDED'
        ? completedStatusText(responseMode)
        : handle.status === 'CANCELED' ? '生成已停止' : '生成失败')
    }).finally(() => {
      // Keep the applied key separately; the promise only coordinates callers that arrive together.
      runtimeFinalizeInflightRef.current.delete(key)
    })
    runtimeFinalizeInflightRef.current.set(key, pending)
    return pending
  }, [completeAssistantThinking, flushAssistantDeltas, flushAssistantReasoningDeltas, isCurrentProject, persistPatch])

  const consumeRuntimeHandle = useCallback(async (
    expectedProjectId: number,
    checkpoint: StoryScriptAgentRuntimeCheckpoint,
    handle: UserSkillRuntimeRunHandle
  ): Promise<boolean> => {
    if (!isCurrentProject(expectedProjectId)) return true
    const runId = Number(handle.runId)
    const inputRequest = normalizeUserSkillInputRequest(handle.requiredInput, runId)
    const responseMode = runtimeResponseMode(handle, checkpoint.responseMode)
    const nextCheckpoint: StoryScriptAgentRuntimeCheckpoint = {
      ...checkpoint,
      runId,
      generation: handle.generation,
      responseMode,
      waitingInput: handle.status === 'NEEDS_INPUT'
    }
    bindRunId(checkpoint.idempotencyKey, runId)

    if (isRuntimeTerminalStatus(handle.status)) {
      await finalizeRuntimeRunOnce(expectedProjectId, nextCheckpoint, handle)
      return true
    }
    if (handle.status === 'NEEDS_INPUT') {
      if (!inputRequest) throw new Error('Skill 返回的创作信息请求无效')
      const savedResponse = stateRef.current?.pendingInputResponse
      const matchingSavedResponse = savedResponse
        && savedResponse.runId === runId
        && savedResponse.requestId === inputRequest.requestId
        && savedResponse.contextVersion === inputRequest.contextVersion
        && savedResponse.schemaDigest === inputRequest.schemaDigest
        ? savedResponse
        : null
      runtimeOutputStartedRef.current.delete(runtimeExecutionKey(runId, handle.generation))
      persistPatch(expectedProjectId, { activeRun: nextCheckpoint, paused: false })
      flushAssistantDeltas()
      completeAssistantThinking(checkpoint.idempotencyKey)
      updateAssistant(checkpoint.idempotencyKey, {
        runId,
        content: '',
        responseMode,
        inputRequest,
        pendingInputResponse: matchingSavedResponse,
        partialOutputTrusted: nextCheckpoint.partialOutputTrusted === true,
        status: 'complete'
      })
      setCanStop(true)
      setStatusText('等待你确认创作信息')
      return true
    }
    persistPatch(expectedProjectId, { activeRun: nextCheckpoint, paused: false })
    if (!runtimeOutputStartedRef.current.has(runtimeExecutionKey(runId, handle.generation))) {
      advanceAssistantThinking(
        checkpoint.idempotencyKey,
        handle.stage,
        null,
        null,
        `snapshot-${handle.generation ?? 0}`
      )
    }
    updateAssistant(checkpoint.idempotencyKey, {
      runId,
      responseMode,
      inputRequest: undefined,
      partialOutputTrusted: nextCheckpoint.partialOutputTrusted === true,
      status: 'streaming'
    })
    setCanStop(true)
    setStatusText(runtimeStageText(handle.stage))
    return false
  }, [
    advanceAssistantThinking,
    bindRunId,
    completeAssistantThinking,
    finalizeRuntimeRunOnce,
    flushAssistantDeltas,
    isCurrentProject,
    persistPatch,
    updateAssistant
  ])

  const watchRuntimeRun = useCallback(async (
    expectedProjectId: number,
    checkpoint: StoryScriptAgentRuntimeCheckpoint & { runId: number },
    signal: AbortSignal,
    recoverPersistedEvents = false
  ) => {
    checkpoint = { ...checkpoint }
    let afterSeq = Math.max(0, Number(checkpoint.afterSeq) || 0)

    const noteSeq = (seq: number) => {
      if (!Number.isFinite(seq) || seq <= afterSeq) return false
      afterSeq = seq
      checkpoint.afterSeq = afterSeq
      return true
    }

    const resolveOutputMode = (
      artifactType: string | null | undefined
    ): UserSkillRuntimeResponseMode | null => {
      const artifact = String(artifactType || '').trim()
      if (artifact === 'SCREENPLAY_TEXT' || artifact === 'SCREENPLAY') return 'SCREENPLAY'
      if (artifact === 'REVIEW_REPORT') return 'DIAGNOSTIC'
      // Keep writing into the active screenplay/diagnostic card when providers emit generic TEXT bodies.
      if (artifact === 'CREATIVE_REASONING') return null
      if (checkpoint.responseMode === 'SCREENPLAY') return 'SCREENPLAY'
      if (checkpoint.responseMode === 'DIAGNOSTIC') return 'DIAGNOSTIC'
      return null
    }

    const applyOutputDelta = (delta: UserSkillRuntimeOutputDelta, seq: number) => {
      if (!isCurrentProject(expectedProjectId) || !noteSeq(seq)) return
      const nextMode = resolveOutputMode(delta.artifactType)
      if (!nextMode || (nextMode === 'DIAGNOSTIC' && checkpoint.responseMode === 'SCREENPLAY')) return
      const assistantId = `assistant-${checkpoint.idempotencyKey}`
      const stepExecutionId = String(delta.stepExecutionId || '').trim()
      const firstSeenForStep = Boolean(stepExecutionId
        && runtimeDeltaStepRef.current.get(assistantId) !== stepExecutionId)
      if (firstSeenForStep || checkpoint.responseMode !== nextMode) {
        checkpoint = {
          ...checkpoint,
          responseMode: nextMode,
          partialOutputTrusted: firstSeenForStep ? delta.reset === true : checkpoint.partialOutputTrusted,
          runId: checkpoint.runId
        }
        persistPatch(expectedProjectId, { activeRun: checkpoint })
        updateAssistant(checkpoint.idempotencyKey, {
          responseMode: nextMode,
          partialOutputTrusted: checkpoint.partialOutputTrusted
        })
      }
      runtimeOutputStartedRef.current.add(
        runtimeExecutionKey(checkpoint.runId, checkpoint.generation)
      )
      completeAssistantThinking(checkpoint.idempotencyKey)
      queueAssistantDelta(checkpoint.idempotencyKey, delta)
    }

    const applyReasoningDelta = (delta: UserSkillRuntimeOutputDelta, seq: number) => {
      if (!isCurrentProject(expectedProjectId) || !noteSeq(seq)) return
      if (String(delta.artifactType || '').trim() !== 'CREATIVE_REASONING') return
      setStatusText('正在思考…')
      queueAssistantReasoningDelta(checkpoint.idempotencyKey, delta)
    }

    while (!signal.aborted && isCurrentProject(expectedProjectId)) {
      const applyMilestone = (event: Parameters<typeof runtimeMilestonePresentation>[0]) => {
        if (!isCurrentProject(expectedProjectId) || event.seq <= afterSeq) return false
        afterSeq = event.seq
        checkpoint.afterSeq = afterSeq
        if (event.eventType === 'reasoning_delta') {
          const delta = parseUserSkillRuntimeOutputDelta({
            payloadJson: event.payloadJson
          })
          if (delta) {
            // Recover path already advanced afterSeq; replay content without re-checking seq.
            if (String(delta.artifactType || '').trim() === 'CREATIVE_REASONING') {
              setStatusText('正在思考…')
              queueAssistantReasoningDelta(checkpoint.idempotencyKey, delta)
            }
          }
          return false
        }
        if (event.eventType === 'output_delta') {
          const delta = parseUserSkillRuntimeOutputDelta({
            payloadJson: event.payloadJson
          })
          if (delta) {
            const nextMode = resolveOutputMode(delta.artifactType)
            if (nextMode && !(nextMode === 'DIAGNOSTIC' && checkpoint.responseMode === 'SCREENPLAY')) {
              const assistantId = `assistant-${checkpoint.idempotencyKey}`
              const stepExecutionId = String(delta.stepExecutionId || '').trim()
              const firstSeenForStep = Boolean(stepExecutionId
                && runtimeDeltaStepRef.current.get(assistantId) !== stepExecutionId)
              if (firstSeenForStep || checkpoint.responseMode !== nextMode) {
                checkpoint = {
                  ...checkpoint,
                  responseMode: nextMode,
                  partialOutputTrusted: firstSeenForStep
                    ? delta.reset === true
                    : checkpoint.partialOutputTrusted,
                  runId: checkpoint.runId
                }
                persistPatch(expectedProjectId, { activeRun: checkpoint })
                updateAssistant(checkpoint.idempotencyKey, {
                  responseMode: nextMode,
                  partialOutputTrusted: checkpoint.partialOutputTrusted
                })
              }
              runtimeOutputStartedRef.current.add(
                runtimeExecutionKey(checkpoint.runId, checkpoint.generation)
              )
              completeAssistantThinking(checkpoint.idempotencyKey)
              queueAssistantDelta(checkpoint.idempotencyKey, delta)
            }
          }
          return false
        }
        if (event.eventType === 'stage' || event.eventType === 'progress') {
          const { stage, message } = runtimeMilestonePresentation(event)
          setStatusText(runtimeStageText(stage, message))
          if (!runtimeOutputStartedRef.current.has(
            runtimeExecutionKey(checkpoint.runId, checkpoint.generation)
          )) {
            advanceAssistantThinking(
              checkpoint.idempotencyKey,
              stage,
              message,
              event.createTime,
              event.stepId ?? event.mediaTaskId ?? event.seq
            )
          }
        }
        return event.eventType === 'input_required' || event.eventType === 'terminal'
      }

      if (recoverPersistedEvents) {
        const recovered = await recoverUserSkillRuntimeMilestones({
          runId: checkpoint.runId,
          afterSeq,
          onMilestone: applyMilestone
        })
        afterSeq = recovered.afterSeq
        checkpoint = {
          ...checkpoint,
          afterSeq,
          generation: recovered.run.generation,
          runId: checkpoint.runId
        }
        persistPatch(expectedProjectId, { activeRun: { ...checkpoint } })
        if (await consumeRuntimeHandle(expectedProjectId, checkpoint, recovered.run)) return
        recoverPersistedEvents = false
      }

      let terminalMilestone = false
      let terminalSnapshot: UserSkillRuntimeRunHandle | null = null
      const connection = new AbortController()
      const abortConnection = () => connection.abort()
      signal.addEventListener('abort', abortConnection, { once: true })
      try {
        await streamUserSkillRuntimeEvents({
          runId: checkpoint.runId,
          afterSeq,
          signal: connection.signal,
          onMilestone: (event) => {
            if (applyMilestone(event)) {
              terminalMilestone = true
              connection.abort()
            }
          },
          onReasoningDelta: applyReasoningDelta,
          onOutputDelta: applyOutputDelta,
          onSnapshot: (snapshot) => {
            if (isRuntimeTerminalStatus(snapshot.status)) {
              terminalSnapshot = snapshot
              terminalMilestone = true
              connection.abort()
            }
          },
          onReconnectRequired: () => {
            recoverPersistedEvents = true
            connection.abort()
          }
        })
      } catch (error) {
        if (!terminalMilestone && !isAbortError(error)) {
          setStatusText('事件连接中断，正在核对任务状态…')
        }
      } finally {
        signal.removeEventListener('abort', abortConnection)
      }
      if (!terminalMilestone) {
        checkpoint = {
          ...markPartialOutputUntrusted(expectedProjectId, checkpoint),
          runId: checkpoint.runId
        }
      }
      checkpoint.afterSeq = afterSeq
      persistPatch(expectedProjectId, { activeRun: { ...checkpoint } })
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      const handle = terminalSnapshot ?? await readRuntimeRunDetailOnce(checkpoint.runId)
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      checkpoint.generation = handle.generation
      if (await consumeRuntimeHandle(expectedProjectId, checkpoint, handle)) return
      await waitForNextPoll(signal)
    }
  }, [
    advanceAssistantThinking,
    completeAssistantThinking,
    consumeRuntimeHandle,
    isCurrentProject,
    markPartialOutputUntrusted,
    persistPatch,
    queueAssistantDelta,
    queueAssistantReasoningDelta,
    readRuntimeRunDetailOnce,
    updateAssistant
  ])

  const executeRuntimeRun = useCallback(async (input: {
    expectedProjectId: number
    prompt: string
    skill: UserSkillDefinition
    checkpoint?: StoryScriptAgentRuntimeCheckpoint | null
    references?: EditorTextSelection[]
  }) => {
    const { expectedProjectId, skill } = input
    const rawPrompt = input.prompt.trim()
    if (!rawPrompt || sendingRef.current || !isCurrentProject(expectedProjectId)) return
    if (rawPrompt.length > MAX_CHAT_PROMPT_CHARS) {
      setLastError('单次对话内容不能超过 100,000 字')
      return
    }

    const abortController = new AbortController()
    activeAbortRef.current?.abort()
    activeAbortRef.current = abortController
    sendingRef.current = true
    setSending(true)
    setPaused(false)
    setCanRetry(true)
    setLastError('')
    setStatusText(input.checkpoint?.runId ? '正在恢复 Skill 任务…' : '正在识别你的意图…')

    let checkpoint = input.checkpoint ?? null
    try {
      if (!checkpoint) {
        const parsedPrompt = parseStoryScriptAgentPrompt(rawPrompt)
        const references = normalizeStoryScriptAgentReferences(
          input.references ?? parsedPrompt.references
        )
        const instruction = parsedPrompt.instruction.trim() || rawPrompt
        const idempotencyKey = createUserSkillClientMessageId()
        const parentRunId = Number(stateRef.current?.lastRunId)
        const style = String(projectStyleRef.current || '').trim().slice(0, 2000)
        checkpoint = {
          idempotencyKey,
          invokeRequest: {
            skillCode: skill.skillCode,
            idempotencyKey,
            projectId: expectedProjectId,
            episodeId: episodeId ?? 0,
            parentRunId: Number.isFinite(parentRunId) && parentRunId > 0 ? parentRunId : undefined,
            operation: 'AUTO',
            qualityMode: 'AUTO',
            prompt: instruction,
            style: style || undefined,
            language: 'zh-CN',
            references: references.map((reference) => ({
              referenceType: 'TEXT' as const,
              text: reference.text
            }))
          },
          prompt: instruction,
          references,
          responseMode: isScreenplayRuntimeSkill(skill) ? 'SCREENPLAY' : 'CHAT',
          runId: null,
          generation: null,
          afterSeq: 0,
          waitingInput: false,
          partialOutputTrusted: false
        }
        const current = stateRef.current
        const next: StoryScriptAgentProjectState = current
          ? { ...current, skill, pendingPrompt: undefined, activeRun: checkpoint, paused: false, updatedAt: Date.now() }
          : {
              version: 3,
              projectId: expectedProjectId,
              episodeId: episodeId ?? 0,
              skill,
              autoOpen: false,
              activeRun: checkpoint,
              paused: false,
              updatedAt: Date.now()
            }
        writeStoryScriptAgentState(next)
        stateRef.current = next
        setCanStop(true)
        setMessages((currentMessages) => [
          ...currentMessages,
          ...checkpointMessages(checkpoint as StoryScriptAgentRuntimeCheckpoint)
        ])
      } else {
        setMessages((current) => current.length ? current : checkpointMessages(checkpoint!))
        persistPatch(expectedProjectId, { activeRun: checkpoint, paused: false })
      }

      if (checkpoint.runId) {
        await watchRuntimeRun(
          expectedProjectId,
          { ...checkpoint, runId: checkpoint.runId },
          abortController.signal,
          true
        )
        return
      }

      const handle = await userSkillRuntimeInvoke(checkpoint.invokeRequest)
      if (abortController.signal.aborted) throw new DOMException('Aborted', 'AbortError')
      checkpoint = {
        ...checkpoint,
        runId: Number(handle.runId),
        generation: handle.generation
      }
      persistPatch(expectedProjectId, { activeRun: checkpoint, pendingPrompt: undefined, paused: false })
      if (await consumeRuntimeHandle(expectedProjectId, checkpoint, handle)) return
      await watchRuntimeRun(
        expectedProjectId,
        { ...checkpoint, runId: Number(handle.runId) },
        abortController.signal
      )
    } catch (error: unknown) {
      if (isAbortError(error) || !isCurrentProject(expectedProjectId)) return
      const errorMessage = runtimeErrorMessage(error, 'Skill 连接失败')
      flushAssistantDeltas()
      if (checkpoint) {
        completeAssistantThinking(checkpoint.idempotencyKey)
        if (!checkpoint.runId) {
          checkpoint = { ...checkpoint, partialOutputTrusted: false }
        }
        persistPatch(expectedProjectId, {
          activeRun: { ...checkpoint },
          pendingPrompt: undefined,
          paused: false
        })
        updateAssistant(checkpoint.idempotencyKey, {
          status: 'error',
          partialOutputTrusted: checkpoint.partialOutputTrusted === true
        })
        setCanStop(true)
      }
      setCanRetry(Boolean(checkpoint || stateRef.current?.pendingPrompt))
      setLastError(errorMessage)
      setStatusText('连接中断，可重试恢复')
    } finally {
      if (isCurrentProject(expectedProjectId) && activeAbortRef.current === abortController) {
        sendingRef.current = false
        setSending(false)
        activeAbortRef.current = null
      }
    }
  }, [
    completeAssistantThinking,
    consumeRuntimeHandle,
    episodeId,
    isCurrentProject,
    flushAssistantDeltas,
    persistPatch,
    updateAssistant,
    watchRuntimeRun
  ])

  useEffect(() => {
    scopeProjectRef.current = projectId
    scopeEpisodeRef.current = episodeId
    activeAbortRef.current?.abort()
    activeAbortRef.current = null
    if (runtimeDeltaFrameRef.current != null) {
      window.cancelAnimationFrame(runtimeDeltaFrameRef.current)
      runtimeDeltaFrameRef.current = null
    }
    if (runtimeReasoningFrameRef.current != null) {
      window.cancelAnimationFrame(runtimeReasoningFrameRef.current)
      runtimeReasoningFrameRef.current = null
    }
    runtimeDeltaBufferRef.current.clear()
    runtimeDeltaStepRef.current.clear()
    runtimeReasoningBufferRef.current.clear()
    runtimeReasoningStepRef.current.clear()
    runtimeOutputStartedRef.current.clear()
    historyGenerationRef.current += 1
    historyBeforeRunIdRef.current = null
    sendingRef.current = false
    stoppingRef.current = false
    stopOperationRef.current = null
    // Scope changes must clear the previous project's visible conversation before hydration starts.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMessages([])
    setMessagesLoading(Boolean(enabled && projectId && projectId > 0 && episodeId != null && episodeId >= 0))
    setOlderMessagesLoading(false)
    setHasOlderMessages(false)
    setLastError('')
    setSending(false)
    setPaused(false)
    setResumePending(false)
    setStopping(false)
    setCanStop(false)
    setStatusText('Agent 已就绪')

    if (!enabled || !projectId || projectId <= 0 || episodeId == null || episodeId < 0) {
      stateRef.current = null
      setCanRetry(false)
      setOpen(false)
      setSkills(skillCatalogRef.current)
      setSelectedSkillCode((current) => skillCatalogRef.current.some(
        (item) => item.skillCode === current
      ) ? current : String(skillCatalogRef.current[0]?.skillCode || ''))
      return undefined
    }

    const expectedProjectId = projectId
    const expectedEpisodeId = episodeId
    let stored = readStoryScriptAgentState(expectedProjectId, expectedEpisodeId)
    if (stored?.activeRun) {
      stored = {
        ...stored,
        activeRun: { ...stored.activeRun, partialOutputTrusted: false },
        updatedAt: Date.now()
      }
      writeStoryScriptAgentState(stored)
    }
    let disposed = false
    let consumeAutoOpenFrame = 0
    stateRef.current = stored
    setCanRetry(Boolean(stored?.activeRun || stored?.pendingPrompt))
    setCanStop(Boolean(stored?.activeRun))
    setPaused(Boolean(stored?.paused && stored.activeRun))
    setOpen(Boolean(stored?.autoOpen || stored?.activeRun || stored?.pendingPrompt))
    const availableSkills = mergeRuntimeSkills(skillCatalogRef.current, stored)
    setSkills(availableSkills)
    setSelectedSkillCode(availableSkills.some((item) => item.skillCode === stored?.skill.skillCode)
      ? String(stored?.skill.skillCode || '')
      : String(availableSkills[0]?.skillCode || ''))
    if (stored?.activeRun) setMessages(checkpointMessages(stored.activeRun))
    if (stored?.paused && stored.activeRun) setStatusText('已暂停接收，可恢复接收')
    if (stored?.autoOpen) {
      consumeAutoOpenFrame = window.requestAnimationFrame(() => {
        if (!disposed && isCurrentProject(expectedProjectId)) {
          persistPatch(expectedProjectId, { autoOpen: false })
        }
      })
    }

    const resumablePrompt = stored?.activeRun?.prompt || stored?.pendingPrompt
    if (resumablePrompt && stored?.skill && !stored.paused) {
      void executeRuntimeRun({
        expectedProjectId,
        prompt: resumablePrompt,
        skill: stored.skill,
        checkpoint: stored.activeRun
      })
    }

    return () => {
      disposed = true
      if (consumeAutoOpenFrame) window.cancelAnimationFrame(consumeAutoOpenFrame)
      activeAbortRef.current?.abort()
    }
  }, [enabled, episodeId, executeRuntimeRun, isCurrentProject, persistPatch, projectId])

  useEffect(() => {
    if (!enabled || !projectId || projectId <= 0 || episodeId == null || episodeId < 0
      || !selectedSkillCode) {
      setMessagesLoading(false)
      return undefined
    }
    const expectedProjectId = projectId
    const expectedEpisodeId = episodeId
    const expectedSkillCode = selectedSkillCode
    const generation = historyGenerationRef.current + 1
    historyGenerationRef.current = generation
    setMessagesLoading(true)
    setHasOlderMessages(false)
    historyBeforeRunIdRef.current = null
    void userSkillRuntimeRunHistory({
      projectId: expectedProjectId,
      episodeId: expectedEpisodeId,
      skillCode: expectedSkillCode,
      pageSize: HISTORY_PAGE_SIZE
    }).then((page) => {
      if (historyGenerationRef.current !== generation || !isCurrentProject(expectedProjectId)) return
      const historyMessages = page.data.flatMap(historyRunMessages)
      setMessages((current) => mergeHydratedMessages(historyMessages, current))
      const runIds = page.data.map((run) => Number(run.runId)).filter((runId) => runId > 0)
      historyBeforeRunIdRef.current = runIds.length ? Math.min(...runIds) : null
      setHasOlderMessages(page.hasMore)
      const latestRunId = runIds.at(-1)
      if (latestRunId) rememberLatestHistoryRun(expectedProjectId, expectedSkillCode, latestRunId)
    }).catch((error: unknown) => {
      if (historyGenerationRef.current !== generation || !isCurrentProject(expectedProjectId)) return
      setLastError(runtimeErrorMessage(error, '历史消息加载失败'))
    }).finally(() => {
      if (historyGenerationRef.current === generation && isCurrentProject(expectedProjectId)) {
        setMessagesLoading(false)
      }
    })
    return () => {
      if (historyGenerationRef.current === generation) historyGenerationRef.current += 1
    }
  }, [
    enabled,
    episodeId,
    isCurrentProject,
    projectId,
    rememberLatestHistoryRun,
    selectedSkillCode
  ])

  const loadOlderMessages = useCallback(async () => {
    const expectedProjectId = projectId
    const expectedEpisodeId = episodeId
    const expectedSkillCode = selectedSkillCode
    const beforeRunId = historyBeforeRunIdRef.current
    if (!expectedProjectId || expectedEpisodeId == null || !expectedSkillCode || !beforeRunId
      || olderMessagesLoading || !hasOlderMessages) return
    const generation = historyGenerationRef.current
    setOlderMessagesLoading(true)
    try {
      const page = await userSkillRuntimeRunHistory({
        projectId: expectedProjectId,
        episodeId: expectedEpisodeId,
        skillCode: expectedSkillCode,
        beforeRunId,
        pageSize: HISTORY_PAGE_SIZE
      })
      if (historyGenerationRef.current !== generation || !isCurrentProject(expectedProjectId)) return
      const historyMessages = page.data.flatMap(historyRunMessages)
      setMessages((current) => mergeHydratedMessages(historyMessages, current))
      const runIds = page.data.map((run) => Number(run.runId)).filter((runId) => runId > 0)
      if (runIds.length) historyBeforeRunIdRef.current = Math.min(...runIds)
      setHasOlderMessages(page.hasMore)
    } catch (error: unknown) {
      if (historyGenerationRef.current === generation && isCurrentProject(expectedProjectId)) {
        setLastError(runtimeErrorMessage(error, '更早的历史消息加载失败'))
      }
    } finally {
      if (historyGenerationRef.current === generation && isCurrentProject(expectedProjectId)) {
        setOlderMessagesLoading(false)
      }
    }
  }, [
    episodeId,
    hasOlderMessages,
    isCurrentProject,
    olderMessagesLoading,
    projectId,
    selectedSkillCode
  ])

  const selectSkill = useCallback((skillCode: string) => {
    if (sendingRef.current || !projectId || skillCode === selectedSkillCode) return
    if (stateRef.current?.activeRun) {
      setLastError('请先完成或停止当前 Skill 运行')
      return
    }
    const skill = skillCatalogRef.current.find((item) => item.skillCode === skillCode)
      ?? skills.find((item) => item.skillCode === skillCode)
    if (!skill) return
    setSelectedSkillCode(skillCode)
    setMessages([])
    const next: StoryScriptAgentProjectState = {
      version: 3,
      projectId,
      episodeId: episodeId ?? 0,
      skill,
      autoOpen: false,
      activeRun: null,
      pendingInputResponse: null,
      paused: false,
      updatedAt: Date.now()
    }
    writeStoryScriptAgentState(next)
    stateRef.current = next
    setCanRetry(false)
    setCanStop(false)
    setStatusText('已切换 Skill，将开始新的运行')
  }, [episodeId, projectId, selectedSkillCode, skills])

  const selectMatchingSkill = useCallback(async (hint: string) => {
    await loadSkills()
    const skillCode = resolveFlowShortcutSkillCode(skillCatalogRef.current, hint)
    if (!skillCode) return
    selectSkill(skillCode)
  }, [loadSkills, selectSkill])

  const send = useCallback((prompt: string, references?: EditorTextSelection[]) => {
    if (!projectId || episodeId == null || sendingRef.current) return false
    if (stateRef.current?.activeRun) {
      setLastError('请先恢复、完成或停止当前 Skill 运行')
      return false
    }
    const skill = skills.find((item) => item.skillCode === selectedSkillCode)
    if (!skill) {
      setLastError('请先选择 Skill')
      return false
    }
    const normalizedReferences = normalizeStoryScriptAgentReferences(references)
    if (normalizedReferences.length > MAX_RUNTIME_REFERENCES) {
      setLastError(`剧本批注最多 ${MAX_RUNTIME_REFERENCES} 项`)
      return false
    }
    if (normalizedReferences.some((reference) => reference.text.length > MAX_RUNTIME_REFERENCE_CHARS)) {
      setLastError(`单项剧本批注不能超过 ${MAX_RUNTIME_REFERENCE_CHARS.toLocaleString()} 字`)
      return false
    }
    const referenceChars = normalizedReferences.reduce(
      (total, reference) => total + reference.text.length,
      0
    )
    if (referenceChars > MAX_RUNTIME_REFERENCE_TOTAL_CHARS) {
      setLastError(`剧本批注总长度不能超过 ${MAX_RUNTIME_REFERENCE_TOTAL_CHARS.toLocaleString()} 字`)
      return false
    }
    const requestPrompt = buildStoryScriptAgentPrompt(prompt, normalizedReferences)
    if (requestPrompt.length > MAX_CHAT_PROMPT_CHARS) {
      setLastError('选段与批注总长度不能超过 100,000 字')
      return false
    }
    const current = stateRef.current
    const pendingState: StoryScriptAgentProjectState = current
      ? {
          ...current,
          skill,
          pendingPrompt: requestPrompt,
          activeRun: null,
          pendingInputResponse: null,
          paused: false,
          updatedAt: Date.now()
        }
      : {
          version: 3,
          projectId,
          episodeId,
          skill,
          autoOpen: false,
          pendingPrompt: requestPrompt,
          activeRun: null,
          pendingInputResponse: null,
          paused: false,
          updatedAt: Date.now()
        }
    writeStoryScriptAgentState(pendingState)
    stateRef.current = pendingState
    setCanRetry(true)
    void executeRuntimeRun({
      expectedProjectId: projectId,
      prompt: requestPrompt,
      skill,
      references: normalizedReferences
    })
    return true
  }, [episodeId, executeRuntimeRun, projectId, selectedSkillCode, skills])

  const submitInputRequest = useCallback(async (
    inputRequest: UserSkillInputRequest,
    answers: UserSkillInputAnswer[]
  ): Promise<boolean> => {
    const expectedProjectId = projectId
    const checkpoint = stateRef.current?.activeRun
    const runId = Number(checkpoint?.runId)
    if (!expectedProjectId || sendingRef.current || stoppingRef.current || !checkpoint
      || !checkpoint.waitingInput || runId !== Number(inputRequest.runId)) return false

    const existing = stateRef.current?.pendingInputResponse
    const existingMatchesBundle = Boolean(existing
      && existing.runId === runId
      && existing.requestId === inputRequest.requestId
      && existing.contextVersion === inputRequest.contextVersion
      && existing.schemaDigest === inputRequest.schemaDigest)
    const answersMatch = existingMatchesBundle
      && JSON.stringify(existing?.answers) === JSON.stringify(answers)
    if (existingMatchesBundle && !answersMatch) {
      setLastError('上次提交结果尚未确认，请保持原答案重试')
      setStatusText('请使用上次答案精确重试')
      return false
    }
    const response: UserSkillInputResponseRequest = answersMatch && existing
      ? existing
      : {
          runId,
          requestId: inputRequest.requestId,
          responseKey: createUserSkillClientMessageId(),
          contextVersion: inputRequest.contextVersion,
          schemaDigest: inputRequest.schemaDigest,
          answers
        }
    persistPatch(expectedProjectId, { pendingInputResponse: response })
    updateAssistant(checkpoint.idempotencyKey, { pendingInputResponse: response })
    runtimeOutputStartedRef.current.delete(runtimeExecutionKey(runId, checkpoint.generation))
    const abortController = new AbortController()
    activeAbortRef.current?.abort()
    activeAbortRef.current = abortController
    sendingRef.current = true
    setSending(true)
    setLastError('')
    setStatusText('正在提交创作信息…')
    try {
      let handle: UserSkillRuntimeRunHandle
      try {
        handle = await userSkillRuntimeRespondInput(response)
      } catch (error) {
        const snapshot = await readRuntimeRunDetailOnce(runId)
        if (abortController.signal.aborted) throw new DOMException('Aborted', 'AbortError')
        if (snapshot.status === 'NEEDS_INPUT') {
          const nextInput = normalizeUserSkillInputRequest(snapshot.requiredInput, runId)
          if (nextInput && !sameInputRequestBundle(inputRequest, nextInput)) {
            persistPatch(expectedProjectId, { pendingInputResponse: null })
            updateAssistant(checkpoint.idempotencyKey, { pendingInputResponse: null })
            await consumeRuntimeHandle(expectedProjectId, checkpoint, snapshot)
            return true
          }
          if (isDefinitiveRuntimeBusinessRejection(error)) {
            persistPatch(expectedProjectId, { pendingInputResponse: null })
            updateAssistant(checkpoint.idempotencyKey, { pendingInputResponse: null })
          }
          throw error
        }
        handle = snapshot
      }
      if (abortController.signal.aborted) throw new DOMException('Aborted', 'AbortError')
      persistPatch(expectedProjectId, { pendingInputResponse: null })
      updateAssistant(checkpoint.idempotencyKey, { pendingInputResponse: null })
      if (await consumeRuntimeHandle(expectedProjectId, checkpoint, handle)) return true
      await watchRuntimeRun(
        expectedProjectId,
        {
          ...checkpoint,
          runId,
          generation: handle.generation,
          waitingInput: false
        },
        abortController.signal
      )
      return true
    } catch (error: unknown) {
      if (isAbortError(error) || !isCurrentProject(expectedProjectId)) return false
      markPartialOutputUntrusted(expectedProjectId, checkpoint)
      setLastError(runtimeErrorMessage(error, '提交创作信息失败'))
      setStatusText('提交失败，请重试')
      return false
    } finally {
      if (isCurrentProject(expectedProjectId) && activeAbortRef.current === abortController) {
        sendingRef.current = false
        setSending(false)
        activeAbortRef.current = null
      }
    }
  }, [
    consumeRuntimeHandle,
    isCurrentProject,
    markPartialOutputUntrusted,
    persistPatch,
    projectId,
    readRuntimeRunDetailOnce,
    updateAssistant,
    watchRuntimeRun
  ])

  const retry = useCallback(() => {
    if (!projectId || sendingRef.current) return
    const state = stateRef.current
    const checkpoint = state?.activeRun
    const skill = state?.skill
    const prompt = checkpoint?.prompt || state?.pendingPrompt
    if (!prompt || !skill) return
    const retryCheckpoint = checkpoint
      ? markPartialOutputUntrusted(projectId, checkpoint)
      : checkpoint
    void executeRuntimeRun({
      expectedProjectId: projectId,
      prompt,
      skill,
      checkpoint: retryCheckpoint
    })
  }, [executeRuntimeRun, markPartialOutputUntrusted, projectId])

  const pauseReceiving = useCallback(() => {
    if (!projectId || !sendingRef.current) return
    const checkpoint = stateRef.current?.activeRun
    if (!checkpoint) return
    const nextCheckpoint = { ...checkpoint, partialOutputTrusted: false }
    persistPatch(projectId, { activeRun: nextCheckpoint, paused: true })
    updateAssistant(nextCheckpoint.idempotencyKey, { partialOutputTrusted: false })
    setResumePending(false)
    setPaused(true)
    setStatusText('已暂停接收，任务仍在后台处理')
    activeAbortRef.current?.abort()
  }, [persistPatch, projectId, updateAssistant])

  const resumeReceiving = useCallback(() => {
    if (!projectId) return
    const checkpoint = stateRef.current?.activeRun
    const skill = stateRef.current?.skill
    if (!checkpoint || !skill) return
    const nextCheckpoint = { ...checkpoint, partialOutputTrusted: false }
    persistPatch(projectId, { activeRun: nextCheckpoint, paused: false })
    updateAssistant(nextCheckpoint.idempotencyKey, { partialOutputTrusted: false })
    setPaused(false)
    if (sendingRef.current) {
      setResumePending(true)
      setStatusText('正在结束旧连接，随后自动恢复接收…')
      return
    }
    setResumePending(false)
    void executeRuntimeRun({
      expectedProjectId: projectId,
      prompt: nextCheckpoint.prompt,
      skill,
      checkpoint: nextCheckpoint
    })
  }, [executeRuntimeRun, persistPatch, projectId, updateAssistant])

  useEffect(() => {
    if (!resumePending || sending || stopping || sendingRef.current || !projectId) return
    const state = stateRef.current
    const checkpoint = state?.activeRun
    const skill = state?.skill
    setResumePending(false)
    if (!checkpoint || !skill || state?.paused) return
    void executeRuntimeRun({
      expectedProjectId: projectId,
      prompt: checkpoint.prompt,
      skill,
      checkpoint
    })
  }, [executeRuntimeRun, projectId, resumePending, sending, stopping])

  const stop = useCallback(async () => {
    const expectedProjectId = projectId
    const activeCheckpoint = stateRef.current?.activeRun
    if (!expectedProjectId || !activeCheckpoint || stoppingRef.current) return
    let checkpoint: StoryScriptAgentRuntimeCheckpoint = activeCheckpoint
    let runId = Number(checkpoint.runId)
    const operation = Symbol(`cancel-${checkpoint.idempotencyKey}`)
    const controllerAtStart = activeAbortRef.current
    stopOperationRef.current = operation
    stoppingRef.current = true
    setResumePending(false)
    setStopping(true)
    setStatusText('正在停止生成…')
    try {
      if (!Number.isFinite(runId) || runId <= 0) {
        let handle: UserSkillRuntimeRunHandle
        try {
          handle = await userSkillRuntimeInvoke(checkpoint.invokeRequest)
        } catch (firstError) {
          if (!isDefinitiveRuntimeBusinessRejection(firstError)) throw firstError
          try {
            // The second exact request confirms that no Run was hidden by the first rejection.
            handle = await userSkillRuntimeInvoke(checkpoint.invokeRequest)
          } catch (confirmationError) {
            if (!isDefinitiveRuntimeBusinessRejection(confirmationError)) throw confirmationError
            controllerAtStart?.abort()
            flushAssistantDeltas()
            completeAssistantThinking(checkpoint.idempotencyKey)
            persistPatch(expectedProjectId, {
              activeRun: null,
              pendingInputResponse: null,
              pendingPrompt: undefined,
              paused: false
            })
            updateAssistant(checkpoint.idempotencyKey, {
              content: '',
              inputRequest: undefined,
              partialOutputTrusted: false,
              status: 'stopped'
            })
            setCanRetry(false)
            setCanStop(false)
            setPaused(false)
            setLastError('')
            setStatusText('运行未创建，已放弃本地请求')
            return
          }
        }
        runId = Number(handle.runId)
        if (!Number.isFinite(runId) || runId <= 0) throw new Error('无法确认 Skill 运行标识')
        checkpoint = {
          ...checkpoint,
          runId,
          generation: handle.generation,
          waitingInput: handle.status === 'NEEDS_INPUT'
        }
        persistPatch(expectedProjectId, { activeRun: checkpoint, pendingPrompt: undefined, paused: false })
        bindRunId(checkpoint.idempotencyKey, runId)
        if (isRuntimeTerminalStatus(handle.status)) {
          await consumeRuntimeHandle(expectedProjectId, checkpoint, handle)
          return
        }
      }
      await userSkillRuntimeRunCancel(runId)
      controllerAtStart?.abort()
      const staleDetail = runtimeDetailInflightRef.current.get(runId)
      if (staleDetail) {
        try {
          await staleDetail
        } catch {
          // The post-cancel read below is authoritative.
        }
      }
      while (isCurrentProject(expectedProjectId) && stopOperationRef.current === operation) {
        runtimeDetailInflightRef.current.delete(runId)
        const handle = await readRuntimeRunDetailOnce(runId)
        if (!isCurrentProject(expectedProjectId) || stopOperationRef.current !== operation) return
        if (isRuntimeTerminalStatus(handle.status)) {
          await consumeRuntimeHandle(expectedProjectId, checkpoint, handle)
          break
        }
        await waitForNextPoll(new AbortController().signal)
      }
      if (!isCurrentProject(expectedProjectId) || stopOperationRef.current !== operation) return
      setLastError('')
    } catch (error) {
      if (!isCurrentProject(expectedProjectId) || stopOperationRef.current !== operation) return
      setCanStop(true)
      setLastError(runtimeErrorMessage(error, '停止生成失败'))
      setStatusText('停止生成失败')
    } finally {
      if (stopOperationRef.current === operation) {
        stopOperationRef.current = null
        stoppingRef.current = false
        setStopping(false)
      }
    }
  }, [
    bindRunId,
    completeAssistantThinking,
    consumeRuntimeHandle,
    flushAssistantDeltas,
    isCurrentProject,
    persistPatch,
    projectId,
    readRuntimeRunDetailOnce,
    updateAssistant
  ])

  return {
    open,
    setOpen,
    skills,
    selectedSkillCode,
    selectSkill,
    selectMatchingSkill,
    skillsLoading,
    skillsError,
    loadSkills,
    messages,
    messagesLoading,
    hasOlderMessages,
    olderMessagesLoading,
    loadOlderMessages,
    sending,
    paused,
    stopping,
    statusText,
    lastError,
    send,
    submitInputRequest,
    pauseReceiving,
    resumeReceiving,
    retry,
    stop,
    canStop: !stopping && canStop,
    canRetry: canRetry && !sending && !paused
  }
}
