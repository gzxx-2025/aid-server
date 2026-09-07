'use client'

import { memo, useCallback, useEffect, useMemo, useState } from 'react'
import { Handle, Position, useUpdateNodeInternals, type NodeProps } from '@xyflow/react'
import { message } from 'antd'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { openStudioFlowBoundEntity } from '@/utils/studio/studioFlowEntityEditor'
import {
  dispatchStudioFlowGenerateStoryboardImage,
  dispatchStudioFlowGenerateVideo,
  isStudioFlowStoryboardStillNode
} from '@/utils/studio/studioFlowStoryboardMedia'
import { rememberStudioMediaAspectRatio, studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import { StudioImagePendingPreview, StudioNodeExternalTitle } from './StudioNodeExternalTitle'
import { StudioMediaPreview } from './StudioMediaPreview'
import { StudioNodeIcon } from './StudioNodeIcon'
import { StudioScriptNodePreview } from './StudioScriptNodePreview'

function StudioNodeCardComponent({ id, data, selected }: NodeProps<StudioCanvasNode>) {
  const updateNodeInternals = useUpdateNodeInternals()
  const task = useStudioUiStore((state) => state.tasks.find((item) =>
    item.nodeId === id && ['queued', 'running', 'waiting'].includes(item.status)
  ))
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const [intrinsicRatio, setIntrinsicRatio] = useState<number | null>(null)
  const dimensions = studioNodeDimensions({ data }, intrinsicRatio)
  const meta = STUDIO_NODE_META[data.kind]
  const mediaKind = data.mediaKind ?? (data.kind === 'image' ? 'image' : data.kind === 'video' ? 'video' : data.kind === 'voice' ? 'audio' : 'text')
  const mediaFillNode = Boolean(data.mediaUrl && (mediaKind === 'image' || mediaKind === 'video'))
  const scriptNode = data.kind === 'text' || data.kind === 'storyboard_script'
  const generating = data.status === 'queued' || data.status === 'generating'
  const textContent = String(data.prompt || data.resultSummary || data.subtitle || '').trim()
  const canGenerateStill = isStudioFlowStoryboardStillNode(data) && !data.mediaUrl
  const canGenerateVideo = data.flowBinding?.entityType === 'storyboard_video' && !data.mediaUrl

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => updateNodeInternals(id))
    return () => window.cancelAnimationFrame(frame)
  }, [dimensions.height, dimensions.width, id, updateNodeInternals])

  const openEditor = useCallback(() => {
    const binding = data.flowBinding
    if (!binding) return
    selectNodes([id])
    const creation = useCreationStore.getState()
    if (!openStudioFlowBoundEntity(
      binding,
      creation.formData.storyboardScript.panels,
      creation.currentProjectId
    )) {
      message.warning('未找到对应的流程实体，请刷新后重试')
    }
  }, [data.flowBinding, id, selectNodes])

  const body = useMemo(() => {
    if (generating) {
      const progress = Math.max(0, Math.min(100, Number(task?.progress) || 0))
      return (
        <div className="studio-node-card__generating">
          <i className="studio-node-card__sheen" />
          <i className="studio-node-card__loader" />
          <span>{task?.stage || '等待实时进度'}</span>
          <b role="progressbar" aria-label="生成进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
            <em style={{ width: `${progress}%` }} />
          </b>
        </div>
      )
    }
    if (data.mediaUrl && mediaKind) {
      return (
        <StudioMediaPreview
          kind={data.kind}
          mediaKind={mediaKind}
          mediaUrl={data.mediaUrl}
          title={data.title}
          onIntrinsicSize={(width, height) => {
            const ratio = rememberStudioMediaAspectRatio(data.mediaUrl!, width, height)
            if (ratio) setIntrinsicRatio(ratio)
          }}
        />
      )
    }
    if ((data.kind === 'text' || data.kind === 'storyboard_script') && textContent) {
      return <StudioScriptNodePreview content={textContent} viewLabel="编辑 →" onView={openEditor} />
    }
    if (canGenerateStill) {
      return <StudioImagePendingPreview title="尚未生成分镜图" actionLabel="生成分镜图" onAction={() => { selectNodes([id]); dispatchStudioFlowGenerateStoryboardImage(id) }} />
    }
    if (canGenerateVideo) {
      return <StudioImagePendingPreview kind="video" title="尚未生成视频" actionLabel="生成视频" onAction={() => { selectNodes([id]); dispatchStudioFlowGenerateVideo(id) }} />
    }
    return (
      <div className="studio-node-card__flow-content">
        <StudioNodeIcon kind={data.kind} variant="watermark" />
        <span>{data.errorMessage || '打开流程编辑器完善此内容'}</span>
      </div>
    )
  }, [canGenerateStill, canGenerateVideo, data, generating, id, mediaKind, openEditor, selectNodes, task?.progress, task?.stage, textContent])

  return (
    <div
      className={`studio-node-shell studio-node-shell--titled${selected ? ' is-selected' : ''}`}
      style={{ width: dimensions.width, height: dimensions.height }}
    >
      <StudioNodeExternalTitle kind={data.kind} title={data.title} />
      <article
        className={`studio-node-card studio-node-card--${data.status}${selected ? ' is-selected' : ''}${mediaFillNode ? ' studio-node-card--image-only' : ''}${scriptNode ? ' studio-node-card--script' : ''}`}
        aria-label={`${meta.label}节点：${data.title}`}
        aria-busy={generating}
        style={{ width: dimensions.width, height: dimensions.height }}
      >
        <div className="studio-node-card__media-stage">{body}</div>
      </article>
      <Handle
        type="target"
        position={Position.Left}
        isConnectable={false}
        className="studio-flow-node-handle studio-flow-node-handle--target"
        aria-label="流程输入连接点"
      />
      <Handle
        type="source"
        position={Position.Right}
        isConnectable={false}
        className="studio-flow-node-handle studio-flow-node-handle--source"
        aria-label="流程输出连接点"
      />
    </div>
  )
}

export const StudioNodeCard = memo(StudioNodeCardComponent)
