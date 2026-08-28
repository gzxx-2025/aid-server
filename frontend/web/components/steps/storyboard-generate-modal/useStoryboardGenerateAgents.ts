'use client'

import { message } from 'antd'
import { useRef,useState } from 'react'
import { useCreationStore } from '~/stores/creation'
import type { AiModelType } from '~/types/business-api'
import type { AgentOption } from '~/types/modelAgentOptions'
import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import { aidAgentList } from '~/utils/businessApi'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import {
STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY,
STORYBOARD_SCRIPT_MODEL_FUNC_CODE,
STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
agentOptionsFromGroup,
pickFirstAgentOption
} from '~/utils/extractAgentBiz'

export interface StoryboardAgent {
  id: string
  name: string
  desc: string
  thumbnail?: string
}

/** 脚本 / 视频共用：选择智能体（原组件 script 段平移，store 快照统一走 getState） */
export function useStoryboardGenerateAgents(options: {
  source: 'script' | 'video'
  /** 当前生效的分镜脚本智能体（props.agent） */
  agent: StoryboardAgent
}) {
  const { source, agent } = options

  /** 异步流程（await 后）读最新 props 用 */
  const agentRef = useRef(agent)
  agentRef.current = agent

  const [agentPickerOpen, setAgentPickerOpen] = useState(false)
  const [agentPickerContext, setAgentPickerContext] = useState<'script' | 'video'>('script')
  const agentPickerContextRef = useRef<'script' | 'video'>('script')
  const [scriptAgentOptions, setScriptAgentOptionsState] = useState<AgentOption[]>([])
  const scriptAgentOptionsRef = useRef<AgentOption[]>([])
  const [scriptAgentsLoading, setScriptAgentsLoading] = useState(false)
  const [videoAgentOptions, setVideoAgentOptionsState] = useState<AgentOption[]>([])
  const videoAgentOptionsRef = useRef<AgentOption[]>([])
  const [videoAgentsLoading, setVideoAgentsLoading] = useState(false)

  const setScriptAgentOptions = (list: AgentOption[]) => {
    scriptAgentOptionsRef.current = list
    setScriptAgentOptionsState(list)
  }
  const setVideoAgentOptions = (list: AgentOption[]) => {
    videoAgentOptionsRef.current = list
    setVideoAgentOptionsState(list)
  }

  // 渲染期订阅，供 AgentPickerModal 回显已选模型
  const storyboardVideoPromptModelCode = useCreationStore(
    (s) => s.storyboardVideoGenerateSettings.videoPromptModelCode
  )
  const storyboardScriptModelCode = useCreationStore((s) => s.storyboardGenerateSettings.modelCode)

  const activeAgentPickerList =
    agentPickerContext === 'video' ? videoAgentOptions : scriptAgentOptions

  const agentPickerDefaultQuery = agentPickerContext === 'video' ? '导演' : '分镜'

  const agentPickerFuncCode =
    agentPickerContext === 'script'
      ? STORYBOARD_SCRIPT_MODEL_FUNC_CODE
      : agentPickerContext === 'video'
        ? AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_PROMPT
        : ''

  const agentPickerModelType: AiModelType | undefined =
    agentPickerContext === 'video' ? 'text' : 'text'

  const agentPickerInitialModelCode =
    agentPickerContext === 'video'
      ? String(storyboardVideoPromptModelCode || '').trim()
      : String(storyboardScriptModelCode || '').trim()

  function applyScriptAgentSelection(autoPickDefault: boolean) {
    const list = scriptAgentOptionsRef.current
    if (!list.length) return

    const currentId = String(agentRef.current?.id || '').trim()
    const matched = currentId ? list.find((a) => a.id === currentId) : null
    if (matched) {
      useCreationStore.getState().updateStoryboardAgent({
        id: matched.id,
        name: matched.name,
        desc: matched.desc || '',
        thumbnail: matched.thumbnail
      })
      return
    }
    if (autoPickDefault) {
      const first = pickFirstAgentOption(list)
      if (first) {
        useCreationStore.getState().updateStoryboardAgent({
          id: first.id,
          name: first.name,
          desc: first.desc || '',
          thumbnail: first.thumbnail
        })
      }
    }
  }

  function aidAgentListScopeParams() {
    return buildAidAgentListScopeParams(useCreationStore.getState())
  }

  async function loadScriptAgents(autoPickDefault = false) {
    setScriptAgentsLoading(true)
    try {
      const groups = await aidAgentList({
        bizCategoryCodes: [STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY],
        ...aidAgentListScopeParams()
      })
      setScriptAgentOptions(agentOptionsFromGroup(groups, STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY))
      applyScriptAgentSelection(autoPickDefault)
      if (autoPickDefault && !scriptAgentOptionsRef.current.length) {
        message.warning('暂无可用分镜脚本智能体')
      }
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载分镜脚本智能体失败')
      setScriptAgentOptions([])
    } finally {
      setScriptAgentsLoading(false)
    }
  }

  function applyVideoAgentSelection(autoPickDefault: boolean) {
    const list = videoAgentOptionsRef.current
    if (!list.length) return

    const currentId = String(useCreationStore.getState().storyboardVideoAgent?.id || '').trim()
    const matched = currentId ? list.find((a) => a.id === currentId) : null
    if (matched) {
      useCreationStore.getState().updateStoryboardVideoAgent({
        id: matched.id,
        name: matched.name,
        desc: matched.desc || '',
        thumbnail: matched.thumbnail
      })
      return
    }
    if (autoPickDefault) {
      const first = pickFirstAgentOption(list)
      if (first) {
        useCreationStore.getState().updateStoryboardVideoAgent({
          id: first.id,
          name: first.name,
          desc: first.desc || '',
          thumbnail: first.thumbnail
        })
      }
    }
  }

  async function loadVideoAgents(autoPickDefault = false) {
    setVideoAgentsLoading(true)
    try {
      const groups = await aidAgentList({
        bizCategoryCodes: [STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY],
        ...aidAgentListScopeParams()
      })
      setVideoAgentOptions(
        agentOptionsFromGroup(groups, STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY)
      )
      applyVideoAgentSelection(autoPickDefault)
      if (autoPickDefault && !videoAgentOptionsRef.current.length) {
        message.warning('暂无可用分镜视频提示词智能体')
      }
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载分镜视频提示词智能体失败')
      setVideoAgentOptions([])
    } finally {
      setVideoAgentsLoading(false)
    }
  }

  async function openAgentPicker(context?: 'script' | 'video') {
    const next = context ?? (source === 'video' ? 'video' : 'script')
    agentPickerContextRef.current = next
    setAgentPickerContext(next)

    if (next === 'script') {
      if (!scriptAgentOptionsRef.current.length) {
        await loadScriptAgents(false)
      }
      if (!scriptAgentOptionsRef.current.length) {
        message.warning('暂无可用分镜脚本智能体')
        return
      }
    } else if (!videoAgentOptionsRef.current.length) {
      await loadVideoAgents(false)
      if (!videoAgentOptionsRef.current.length) {
        message.warning('暂无可用分镜视频提示词智能体')
        return
      }
    }

    setAgentPickerOpen(true)
  }

  function onUnifiedAgentPicked(payload: { agent?: AgentOption; modelCode?: string }) {
    const picked = payload.agent
    const modelCode = String(payload.modelCode || '').trim()
    const store = useCreationStore.getState()
    if (agentPickerContextRef.current === 'video') {
      if (picked) {
        store.updateStoryboardVideoAgent({
          id: picked.id,
          name: picked.name,
          desc: picked.desc || '',
          thumbnail: picked.thumbnail
        })
      }
      if (modelCode) {
        store.setStoryboardVideoGenerateSettings({ videoPromptModelCode: modelCode })
      } else if (picked) {
        store.setStoryboardVideoGenerateSettings({ videoPromptModelCode: '' })
      }
    } else {
      if (picked) {
        store.updateStoryboardAgent({
          id: picked.id,
          name: picked.name,
          desc: picked.desc || '',
          thumbnail: picked.thumbnail
        })
      }
      if (picked || modelCode) {
        store.setStoryboardGenerateSettings({
          ...(picked ? { agentId: picked.id } : {}),
          ...(modelCode ? { modelCode } : picked ? { modelCode: '' } : {})
        })
      }
    }
  }

  return {
    agentPickerOpen,
    setAgentPickerOpen,
    scriptAgentsLoading,
    videoAgentsLoading,
    activeAgentPickerList,
    agentPickerDefaultQuery,
    agentPickerFuncCode,
    agentPickerModelType,
    agentPickerInitialModelCode,
    loadScriptAgents,
    loadVideoAgents,
    openAgentPicker,
    onUnifiedAgentPicked
  }
}
