'use client'

import { useCallback, useEffect } from 'react'
import { message } from 'antd'
import type { StudioCanvasNode } from '@/types/studio'
import { useCreationStore } from '@/stores/creation'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { getRouteLikeSnapshot } from '@/hooks/useRouteLike'
import { flushStoryScriptAutoSave, queueStoryScriptAutoSave, type StoryScriptFlushResult } from '@/utils/storyScriptPersistence'
import { resolveStoryScriptSaveContext } from '@/utils/storyScriptSaveContext'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'
import { useStudioUiStore } from '@/stores/studioUi'

export function StudioFlowStoryScriptComposer({ node }: { node: StudioCanvasNode }) {
  const runtime = useStudioFlowRuntime()
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)

  const flushScript = useCallback(async (): Promise<StoryScriptFlushResult> => {
    const ctx = await resolveStoryScriptSaveContext(useCreationStore.getState(), getRouteLikeSnapshot())
    if (!ctx) return 'error'
    const content = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)?.data.prompt ?? node.data.prompt
    queueStoryScriptAutoSave(ctx, content)
    return flushStoryScriptAutoSave(ctx)
  }, [node.data.prompt, node.id])

  useEffect(() => {
    runtime?.registerStoryScriptFlush(flushScript)
    return () => runtime?.registerStoryScriptFlush(null)
  }, [flushScript, runtime])

  const saveScript = async (prompt: string) => {
    updateNodeData(node.id, { prompt, resultSummary: prompt.slice(0, 180) })
    useCreationStore.getState().updateFormData({
      storyScript: { ...useCreationStore.getState().formData.storyScript, content: prompt }
    })
    const result = await flushScript()
    if (result === 'saved') message.success('剧本已保存')
    else if (result === 'clean') message.success('剧本无变更')
    else if (result === 'conflict') message.error('剧本与服务端冲突，请点击节点查看并处理')
    else message.error('剧本保存失败')
  }

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader node={node} subtitle="故事剧本 · 服务端同步" />
      <StudioComposerFrame
        node={node}
        placeholder="在此编辑剧本正文，Enter 保存到服务端"
        onSubmit={saveScript}
      />
    </div>
  )
}
