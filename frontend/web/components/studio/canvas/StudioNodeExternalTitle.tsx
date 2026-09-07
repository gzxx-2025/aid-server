'use client'

import type { StudioNodeKind } from '@/types/studio'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import { StudioNodeIcon } from './StudioNodeIcon'

export function StudioNodeExternalTitle({
  kind,
  title
}: {
  kind: StudioNodeKind
  title: string
}) {
  const meta = STUDIO_NODE_META[kind]
  const showInstanceName = Boolean(title.trim()) && title.trim() !== meta.label && !title.startsWith(`${meta.label}节点`)
  return (
    <div className="studio-node-external-title" title={title}>
      <span className="studio-node-external-title__chip" aria-hidden>
        <StudioNodeIcon kind={kind} variant="chip" />
      </span>
      <span className="studio-node-external-title__label">{meta.label}</span>
      {showInstanceName ? (
        <span className="studio-node-external-title__name">{title}</span>
      ) : null}
    </div>
  )
}

export function StudioImagePendingPreview({
  kind = 'image',
  title,
  actionLabel,
  onAction
}: {
  kind?: StudioNodeKind
  title?: string
  actionLabel?: string
  onAction?: () => void
}) {
  const meta = STUDIO_NODE_META[kind]
  const heading = title ?? `尚未生成${meta.label}`
  return (
    <div className="studio-image-pending-preview">
      <div className="studio-node-empty-preview__stage studio-image-pending-preview__stage">
        <span className="studio-node-empty-preview__watermark" aria-hidden>
          <StudioNodeIcon kind={kind} variant="watermark" />
        </span>
        <span className="studio-image-pending-preview__title">{heading}</span>
      </div>
      {actionLabel && onAction ? (
        <button
          type="button"
          className="studio-image-pending-preview__action nodrag"
          onClick={(event) => {
            event.stopPropagation()
            onAction()
          }}
        >
          {actionLabel}
        </button>
      ) : null}
    </div>
  )
}
