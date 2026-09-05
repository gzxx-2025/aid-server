import type {
  UserSkillDefinition,
  UserSkillRuntimeResponseMode,
  UserSkillRuntimeRunHandle
} from '~/types/user-skill'
import type { StoryScriptAgentProjectState } from '~/utils/storyScriptAgentState'
import {
  inferAgentThinkingStepKind,
  type AgentThinkingStep
} from '~/utils/agentThinkingSteps'
import { looksLikeScreenplayContent } from '~/utils/storyScriptAgentMessagePresentation'

const MAX_THINKING_STEPS = 24

export function isRuntimeTerminalStatus(status: string): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELED'
}

export function runtimeExecutionKey(runId: number, generation?: number | null): string {
  return `${runId}:${generation ?? 0}`
}

export function isScreenplayRuntimeSkill(skill: UserSkillDefinition): boolean {
  return skill.capability === 'SCRIPT_WRITING' && skill.outputKind === 'SCREENPLAY'
}

export function mergeRuntimeSkills(
  availableSkills: UserSkillDefinition[],
  stored: StoryScriptAgentProjectState | null
): UserSkillDefinition[] {
  const activeSkill = stored?.activeRun ? stored.skill : null
  if (!activeSkill || availableSkills.some((item) => item.skillCode === activeSkill.skillCode)) {
    return availableSkills
  }
  return [activeSkill, ...availableSkills]
}

export function completedStatusText(mode: UserSkillRuntimeResponseMode): string {
  if (mode === 'CHAT') return '已回复'
  return mode === 'DIAGNOSTIC' ? '剧本诊断已完成' : '剧本已生成'
}

export function runtimeResponseMode(
  handle: UserSkillRuntimeRunHandle,
  fallback: UserSkillRuntimeResponseMode
): UserSkillRuntimeResponseMode {
  if (handle.responseMode === 'DIAGNOSTIC' || handle.qualityMode === 'REVIEW_ONLY') return 'DIAGNOSTIC'
  if (handle.responseMode === 'SCREENPLAY') return 'SCREENPLAY'
  if (handle.responseMode === 'CHAT') {
    // Intent routing may briefly report CHAT; do not demote an active screenplay run.
    if (fallback === 'SCREENPLAY' && !isRuntimeTerminalStatus(String(handle.status || ''))) {
      return 'SCREENPLAY'
    }
    if (fallback === 'SCREENPLAY') {
      const output = String(handle.assistantMessage || handle.outputText || '')
      if (looksLikeScreenplayContent(output)) return 'SCREENPLAY'
    }
    return 'CHAT'
  }
  return fallback
}

export function runtimeStageText(stage?: string | null, message?: string | null): string {
  if (message?.trim()) return message.trim()
  const normalized = String(stage || '').trim().toUpperCase()
  if (normalized === 'VALIDATING') return '正在识别并校验请求…'
  if (normalized === 'PLANNING') return '正在理解创作目标…'
  if (normalized === 'WAITING_USER') return '等待你确认创作信息'
  if (normalized === 'WRITING') return '正在生成剧本…'
  if (normalized === 'REVIEWING') return '正在审查剧本…'
  if (normalized === 'GENERATING_MEDIA') return '正在生成内容…'
  if (normalized === 'FINALIZING') return '正在整理最终结果…'
  return 'Skill 正在运行…'
}

export function runtimeThinkingStep(
  stage?: string | null,
  message?: string | null,
  startedAt?: string | null,
  identity?: string | number | null
): AgentThinkingStep {
  const normalizedStage = String(stage || 'progress').trim().toLowerCase() || 'progress'
  const label = runtimeStageText(stage, message)
  const normalizedIdentity = String(identity ?? 'snapshot').trim() || 'snapshot'
  return {
    id: `runtime-${normalizedStage}-${normalizedIdentity}`,
    kind: inferAgentThinkingStepKind(label),
    label,
    status: 'active',
    startedAt: startedAt || new Date().toISOString()
  }
}

export function mergeRuntimeThinkingStep(
  steps: AgentThinkingStep[] | undefined,
  nextStep: AgentThinkingStep
): AgentThinkingStep[] {
  const current = steps ?? []
  const existingIndex = current.findIndex((step) => step.id === nextStep.id)
  if (existingIndex >= 0) {
    const existing = current[existingIndex]
    return [
      ...current
        .filter((_, index) => index !== existingIndex)
        .map((step) => ({ ...step, status: 'done' as const })),
      { ...existing, ...nextStep, startedAt: existing.startedAt || nextStep.startedAt }
    ].slice(-MAX_THINKING_STEPS)
  }
  return [
    ...current.map((step) => ({ ...step, status: 'done' as const })),
    nextStep
  ].slice(-MAX_THINKING_STEPS)
}
