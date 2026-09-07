import type { ComponentType } from 'react'
import {
  AudioOutlined,
  FileTextOutlined,
  PictureOutlined,
  PlaySquareOutlined,
  ReadOutlined
} from '@ant-design/icons'
import type { StudioNodeKind } from '@/types/studio'

const STUDIO_NODE_ICON: Record<StudioNodeKind, ComponentType<{ className?: string }>> = {
  image: PictureOutlined,
  video: PlaySquareOutlined,
  text: FileTextOutlined,
  storyboard_script: ReadOutlined,
  voice: AudioOutlined
}

export function StudioNodeIcon({
  kind,
  variant = 'default'
}: {
  kind: StudioNodeKind
  variant?: 'default' | 'watermark' | 'chip'
}) {
  const Icon = STUDIO_NODE_ICON[kind]
  return <Icon className={`studio-node-icon studio-node-icon--${variant}`} aria-hidden={variant !== 'default'} />
}
