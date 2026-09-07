'use client'

import { useCallback } from 'react'
import { message } from 'antd'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import { openRechargeModalFromInsufficientBalance } from '@/utils/api'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { studioSseTaskProgressPatch } from '@/utils/studio/studioSseTaskProgress'
import type { TaskSseProgressInput } from '@/utils/taskSseProgressText'

type FlowTaskCallbacks = {
  onSubmitted: (info: { taskId: number; recordId?: number | null }) => void
  onProgress: (info: TaskSseProgressInput & { percent?: number }) => void
}

type FlowTaskExecuteResult = {
  ok: boolean
  errorMessage?: string
  deferred?: boolean
  imageUrl?: string | null
  videoUrl?: string | null
  recordId?: number | null
  items?: Array<{ imageUrl?: string; recordId?: number; imageId?: number }>
}

export function useStudioFlowNodeTask(nodeId: string, nodeTitle: string) {
  const runtime = useStudioFlowRuntime()

  const runOnNode = useCallback(async (input: {
    label: string
    mediaKind?: 'image' | 'video'
    mediaUrl?: string | null
    execute: (callbacks: FlowTaskCallbacks) => Promise<FlowTaskExecuteResult>
  }) => {
    const scopeKey = useStudioUiStore.getState().scopeKey
    return runStudioRequestSingleFlight(`${scopeKey}:${nodeId}:generate`, async () => {
      let studioTaskId = ''
      useStudioUiStore.getState().updateNodeData(nodeId, {
        status: 'generating',
        errorMessage: undefined
      })

      let result: FlowTaskExecuteResult
      try {
        result = await input.execute({
          onSubmitted: ({ taskId }) => {
            studioTaskId = `studio-flow-${taskId}`
            const task = createStudioTask(`${nodeTitle} · ${input.label}`, {
              nodeId,
              remoteTaskId: taskId,
              stage: '任务已提交',
              progress: 0,
              status: 'running'
            })
            useStudioUiStore.getState().upsertTask({ ...task, id: studioTaskId })
          },
          onProgress: (progress) => {
            if (!studioTaskId) return
            useStudioUiStore.getState().patchTask(
              studioTaskId,
              studioSseTaskProgressPatch(progress)
            )
          }
        })
      } catch (error: unknown) {
        const store = useStudioUiStore.getState()
        if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === nodeId)) return false
        const errorMessage = safeFlowTaskError(error, `${input.label}任务异常，请重试`)
        clearNodeSurfaceError(store, nodeId)
        if (studioTaskId) {
          store.patchTask(studioTaskId, { status: 'failed', stage: '任务异常终止', errorMessage })
        }
        message.error(errorMessage)
        openRechargeModalFromInsufficientBalance(errorMessage)
        return false
      }

      const store = useStudioUiStore.getState()
      if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === nodeId)) return false

      if (!result.ok) {
        if (result.deferred) {
          store.updateNodeData(nodeId, {
            status: 'generating',
            resultSummary: '任务仍在后台执行，请在任务中心查看'
          })
          if (studioTaskId) store.patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
          return false
        }
        const errorMessage = result.errorMessage || `${input.label}失败`
        clearNodeSurfaceError(store, nodeId)
        if (studioTaskId) store.patchTask(studioTaskId, { status: 'failed', stage: '执行失败', errorMessage })
        message.error(errorMessage)
        openRechargeModalFromInsufficientBalance(errorMessage)
        return false
      }

      const item = result.items?.findLast?.((entry) => Boolean(entry.imageUrl))
        ?? [...(result.items ?? [])].reverse().find((entry) => Boolean(entry.imageUrl))
      const mediaUrl = String(result.imageUrl || result.videoUrl || item?.imageUrl || '').trim()
      const recordId = result.recordId ?? item?.recordId ?? item?.imageId ?? null
      if (mediaUrl) {
        store.updateNodeData(nodeId, {
          status: 'success',
          mediaKind: input.mediaKind ?? (result.videoUrl ? 'video' : 'image'),
          mediaUrl,
          resultSummary: `${input.label}完成`,
          errorMessage: undefined,
          ...(recordId ? { genRecordId: Number(recordId) } : {})
        })
      } else {
        store.updateNodeData(nodeId, {
          status: 'success',
          resultSummary: `${input.label}完成`,
          errorMessage: undefined
        })
      }
      if (studioTaskId) store.patchTask(studioTaskId, { status: 'succeeded', stage: '已完成', progress: 100 })
      message.success(`${input.label}完成`)
      void runtime?.refresh({ taskIntent: 'mutate' })
      return true
    })
  }, [nodeId, nodeTitle, runtime])

  return { runOnNode }
}

/** 失败只走 toast / 任务中心；节点本体不挂错误文案，避免与弹窗重复。 */
function clearNodeSurfaceError(
  store: ReturnType<typeof useStudioUiStore.getState>,
  nodeId: string
) {
  const node = store.nodes.find((item) => item.id === nodeId)
  const hasMedia = Boolean(node?.data.mediaUrl?.trim())
  store.updateNodeData(nodeId, {
    status: hasMedia ? 'success' : 'empty',
    errorMessage: undefined
  })
}

function safeFlowTaskError(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : ''
  const normalized = raw.replace(/\s+/g, ' ').trim()
  if (!normalized) return fallback
  if (/https?:\/\/|authorization|bearer|api[-_ ]?key|access[-_ ]?key|secret|token|signature/i.test(normalized)) {
    return fallback
  }
  return normalized.slice(0, 160)
}
