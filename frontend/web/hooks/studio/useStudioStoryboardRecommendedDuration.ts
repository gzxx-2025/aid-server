'use client'

import { useEffect } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { readRecommendedDurationSeconds } from '@/utils/resolveVideoDurationOption'
import { fetchUserStoryboardDetailOnce } from '@/utils/storyboardDetailOnce'

/**
 * 与编辑分镜视频弹窗共用分镜详情中的推荐时长真相。
 * 提示词变化后强制刷新详情，确保 Agent 刚生成的新建议能立即进入节点参数。
 * 不监听通用任务提交事件：该事件同时覆盖任务开始和终态，会在视频生成期间重复拉详情。
 */
export function useStudioStoryboardRecommendedDuration(node: StudioCanvasNode): number | null {
  const storyboardId = Number(node.data.flowBinding?.serverId ?? node.data.storyboardId)
  const prompt = node.data.prompt

  useEffect(() => {
    if (!Number.isSafeInteger(storyboardId) || storyboardId <= 0) return
    let active = true
    const refresh = () => {
      void fetchUserStoryboardDetailOnce(storyboardId, { force: true }).then((row) => {
        if (!active) return
        const store = useStudioUiStore.getState()
        const current = store.nodes.find((item) => item.id === node.id)
        if (!current) return
        const recommendedDurationSeconds = readRecommendedDurationSeconds(row)
        if (
          current.data.recommendedDurationSeconds === recommendedDurationSeconds
          && current.data.recommendedDurationSource === row.recommendedDurationSource
          && current.data.recommendedDurationDescription === row.recommendedDurationDescription
        ) return
        store.updateNodeData(node.id, {
          recommendedDurationSeconds,
          recommendedDurationSource: row.recommendedDurationSource,
          recommendedDurationDescription: row.recommendedDurationDescription
        })
      })
      .catch(() => {
        // 推荐时长不可用时沿用当前模型默认档位，不阻断视频生成。
      })
    }
    refresh()
    return () => {
      active = false
    }
  }, [node.id, prompt, storyboardId])

  return node.data.recommendedDurationSeconds ?? null
}
