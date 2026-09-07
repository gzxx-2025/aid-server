'use client'

import { useEffect, useMemo, useState } from 'react'
import { Button, message } from 'antd'
import {
  SettingOutlined,
  SkinOutlined
} from '@ant-design/icons'
import AgentConversationPanel from '@/components/common/agent-conversation/AgentConversationPanel'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import { useCreateFlowGlobalSettingModal } from '@/hooks/useCreateFlowGlobalSettingModal'
import { useStudioFlowRuntime } from '../flow/StudioFlowRuntimeProvider'
import {
  isStudioFlowProjectConfigCompatible,
  studioFlowConversationScopeKey
} from '@/utils/studio/studioFlowAssistant'
import {
  isCreationModeDisabledForScriptType,
  isScriptTypeLockedToPlot
} from '@/utils/creationModeUiRules'
import { StudioFlowAgentContextCard } from '../flow/agent/StudioFlowAgentContextCard'
import { StudioFlowSeriesSplitWizard } from '../flow/agent/StudioFlowSeriesSplitWizard'
import { StudioFlowStoryScriptAgentShell } from '../flow/agent/StudioFlowStoryScriptAgentShell'
import { StudioFlowSetupSummary } from '../flow/agent/StudioFlowSetupSummary'
import { useStudioFlowAutoPipeline } from '@/hooks/studio/useStudioFlowAutoPipeline'
import { resolveStudioFlowBatchSelection } from '@/utils/studio/studioFlowBatchSelection'
import { useStudioFlowBatchActions } from '@/hooks/studio/useStudioFlowBatchActions'
import {
  useStudioPanelPresence,
  useStudioExitingPanelRef
} from '@/hooks/studio/useStudioPanelPresence'

export function StudioFlowRightPanel() {
  const collapsed = useStudioUiStore((state) => state.rightCollapsed)
  const setCollapsed = useStudioUiStore((state) => state.setRightCollapsed)
  const { rendered, exiting } = useStudioPanelPresence(!collapsed)
  const panelRef = useStudioExitingPanelRef(exiting)

  return rendered ? (
    <aside
      ref={panelRef}
      className={`studio-right-panel absolute inset-y-3.5 right-3.5 z-[12] flex min-h-0 min-w-0 w-[min(27.5rem,calc(100%-1.75rem))] max-w-[calc(100%-1.75rem)] flex-col overflow-hidden max-[1440px]:w-[min(20.75rem,calc(100%-1.75rem))] max-[1100px]:w-[min(19rem,calc(100%-1.75rem))]${exiting ? ' is-exiting' : ''}`}
      data-studio-guide="conversation"
      aria-hidden={exiting}
      inert={exiting}
    >
      <div className="studio-right-panel__content flex min-h-0 min-w-0 flex-1 flex-col overflow-auto pb-2.5">
        <StudioFlowAgentConsolePanel onClose={() => setCollapsed(true)} />
      </div>
    </aside>
  ) : null
}

function StudioFlowAgentConsolePanel({ onClose }: { onClose: () => void }) {
  const runtime = useStudioFlowRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const selectedFlowNodes = useMemo(() => {
    const selected = new Set(selectedNodeIds)
    return nodes.filter((node) => selected.has(node.id) && node.data.flowBinding)
  }, [nodes, selectedNodeIds])
  const batchSelection = useMemo(
    () => (selectedFlowNodes.length >= 2 ? resolveStudioFlowBatchSelection(selectedFlowNodes) : null),
    [selectedFlowNodes]
  )
  const { runBatch } = useStudioFlowBatchActions()
  const [batchRunning, setBatchRunning] = useState(false)
  const [seriesSplitOpen, setSeriesSplitOpen] = useState(false)
  const projectType = useCreationStore((state) => state.currentProjectType)
  const runSelectedBatch = () => {
    if (!batchSelection?.ok) return
    setBatchRunning(true)
    void runBatch(batchSelection.intent, batchSelection.nodes).finally(() => setBatchRunning(false))
  }
  const removeSelectedNode = (nodeId: string) => {
    selectNodes(selectedNodeIds.filter((id) => id !== nodeId))
  }
  const contextChips = selectedFlowNodes.length ? (
    <StudioFlowAgentContextCard
      nodes={selectedFlowNodes}
      batchSelection={batchSelection}
      batchRunning={batchRunning}
      onRemove={removeSelectedNode}
      onFocus={(nodeId) => selectNodes([nodeId])}
      onRunBatch={runSelectedBatch}
    />
  ) : null
  const globalSettingStore = useCreationStore((state) => state.formData.globalSetting)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const config = useCreateFlowGlobalSettingModal()
  const [saving, setSaving] = useState(false)
  const resolvedEpisodeId = runtime?.episodeId ?? episodeId ?? 0
  const scopeKey = studioFlowConversationScopeKey(projectId, resolvedEpisodeId)
  const syncGlobalSettingDraftFromStore = config.syncGlobalSettingDraftFromStore
  const agentReady = isStudioFlowProjectConfigCompatible(globalSettingStore)
  const draft = config.creationGlobalSettingDraft
  const styleReady = Boolean(draft.selectedStyle?.id || draft.selectedStyle?.name)
  const allComplete = isStudioFlowProjectConfigCompatible(draft)
  const autoPipeline = useStudioFlowAutoPipeline(projectId, resolvedEpisodeId)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      syncGlobalSettingDraftFromStore()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [scopeKey, syncGlobalSettingDraftFromStore])

  const updateProjectChoice = <K extends 'creationMode' | 'aspectRatio' | 'scriptType' | 'modelStrategy'>(
    field: K,
    value: typeof draft[K]
  ) => {
    config.updateGlobalSettingDraftField(field, value)
  }

  const confirmSetup = async () => {
    if (!allComplete || saving || !isStudioFlowProjectConfigCompatible(draft)) return
    setSaving(true)
    try {
      await config.handleGlobalSettingConfirm({ navigateAfterSave: false })
      await runtime?.refresh()
      message.success('项目配置已保存')
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存项目参数失败，请重试')
    } finally {
      setSaving(false)
    }
  }

  if (agentReady && projectId) {
    return (
      <div className="studio-flow-agent-console">
        <StudioFlowSetupSummary draft={globalSettingStore} />
        {projectType === 'series' ? (
          <button
            type="button"
            className="studio-flow-series-split-link"
            onClick={() => setSeriesSplitOpen(true)}
          >
            剧集 · 上传剧本并拆集
          </button>
        ) : null}
        <StudioFlowStoryScriptAgentShell
          onClose={onClose}
          autoPipeline={autoPipeline}
          composerContextSlot={contextChips}
        />
        <StudioFlowSeriesSplitWizard
          open={seriesSplitOpen}
          projectId={projectId}
          onOpenChange={setSeriesSplitOpen}
          onCompleted={() => void runtime?.refresh({ taskIntent: 'read' })}
        />
      </div>
    )
  }

  return (
    <>
      <AgentConversationPanel
        title="流程创作助手"
        onClose={onClose}
        updateToken={scopeKey}
        scopeKey={scopeKey}
        emptySlot={null}
        composer={contextChips}
      >
        <article className="studio-flow-setup-message">
          <header><SettingOutlined /><strong>完善项目配置</strong></header>
          <p>当前项目尚未完成风格与模型配置。请补全后保存，即可开始与剧本 Agent 对话。</p>
          <button type="button" className={`studio-flow-setup-style${styleReady ? ' is-selected' : ''}`} onClick={() => runtime?.openEditor('global-setting')}>
            {draft.selectedStyle?.thumbnail
              ? <i className="studio-flow-setup-style__preview" style={{ backgroundImage: `url("${draft.selectedStyle.thumbnail.replace(/"/g, '%22')}")` }} />
              : <i><SkinOutlined /></i>}
            <span><small>创作风格</small><strong>{draft.selectedStyle?.name || '选择风格'}</strong></span>
          </button>
          <FlowChoiceGroup
            title="创作模型"
            disabled={!styleReady}
            value={draft.creationMode}
            options={[
              ['i2v', '图生视频'],
              ['multi', '多参', isCreationModeDisabledForScriptType('multi', draft.scriptType)],
              ['pro', '专业版', isCreationModeDisabledForScriptType('pro', draft.scriptType)],
              ['auto_grid', '自动宫格', isCreationModeDisabledForScriptType('auto_grid', draft.scriptType)]
            ]}
            onChange={(value) => {
              const creationMode = value as typeof draft.creationMode
              updateProjectChoice('creationMode', creationMode)
              if (isScriptTypeLockedToPlot(creationMode)) {
                config.updateGlobalSettingDraftField('scriptType', 'plot')
              }
            }}
          />
          <FlowChoiceGroup
            title="视频比例"
            disabled={!draft.creationMode}
            value={draft.aspectRatio}
            options={[['16:9', '16:9'], ['9:16', '9:16'], ['1:1', '1:1'], ['4:3', '4:3'], ['3:4', '3:4'], ['21:9', '21:9']]}
            onChange={(value) => updateProjectChoice('aspectRatio', value as typeof draft.aspectRatio)}
          />
          <FlowChoiceGroup
            title="剧本类型"
            disabled={!draft.aspectRatio}
            value={draft.scriptType}
            options={[
              ['plot', '剧情演绎'],
              ['monologue', '真人解说', isCreationModeDisabledForScriptType(draft.creationMode, 'monologue')]
            ]}
            onChange={(value) => updateProjectChoice('scriptType', value as typeof draft.scriptType)}
          />
          <FlowChoiceGroup
            title="模型策略"
            disabled={!draft.scriptType}
            value={draft.modelStrategy}
            options={[['economy', '经济模式'], ['performance', '性能模式']]}
            onChange={(value) => updateProjectChoice('modelStrategy', value as typeof draft.modelStrategy)}
          />
          <Button block type="primary" disabled={!allComplete} loading={saving} onClick={() => void confirmSetup()}>
            保存并进入创作
          </Button>
        </article>
      </AgentConversationPanel>
      {projectId ? (
        <StudioFlowSeriesSplitWizard
          open={seriesSplitOpen}
          projectId={projectId}
          onOpenChange={setSeriesSplitOpen}
          onCompleted={() => void runtime?.refresh({ taskIntent: 'read' })}
        />
      ) : null}
    </>
  )
}

function FlowChoiceGroup({
  title,
  value,
  options,
  disabled,
  onChange
}: {
  title: string
  value: string
  options: Array<[string, string, boolean?]>
  disabled?: boolean
  onChange: (value: string) => void
}) {
  return (
    <section className="studio-flow-choice-group">
      <strong>{title}</strong>
      <div>
        {options.map(([key, label, optionDisabled]) => (
          <button key={key} type="button" disabled={disabled || optionDisabled} className={value === key ? 'is-selected' : ''} onClick={() => onChange(key)}>
            {label}
          </button>
        ))}
      </div>
    </section>
  )
}
