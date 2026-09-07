import type { Edge, Node, Viewport, XYPosition } from '@xyflow/react'

/** 流程画布仅投影七步业务需要的节点类型。 */
export type StudioNodeKind = 'image' | 'video' | 'text' | 'storyboard_script' | 'voice'

export type StudioImagePurpose = 'generic' | 'character' | 'scene' | 'prop' | 'storyboard'
export type StudioNodeStatus = 'empty' | 'queued' | 'generating' | 'success' | 'failed'
export type StudioMediaKind = 'image' | 'video' | 'audio' | 'text'
export type StudioEdgeKind = 'sequence' | 'reference' | 'flow'
export type StudioCanvasTool = 'select' | 'pan'
export type StudioVideoGenMode = 'imageToVideo' | 'multiParam' | 'gridVideo'
export type StudioTaskStatus = 'queued' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'cancelled'

export type StudioFlowStep =
  | 'global-setting'
  | 'story-script'
  | 'scene-character'
  | 'storyboard-script'
  | 'storyboard-video'
  | 'dubbing'
  | 'preview'

export type StudioFlowEntityType =
  | 'project'
  | 'story_script'
  | 'rps_asset'
  | 'storyboard'
  | 'storyboard_video'
  | 'dubbing'
  | 'preview'

export interface StudioFlowBinding {
  role: 'step' | 'item'
  step: StudioFlowStep
  entityType: StudioFlowEntityType
  projectId: number
  episodeId: number
  serverId?: number
  assetType?: 'scene' | 'character' | 'prop'
  bindingKey: string
}

export interface StudioComposerRef {
  id: string
  kind: 'node' | 'asset' | 'library' | 'upload' | 'frame' | 'likeness'
  nodeId?: string
  assetId?: string
  title: string
  mediaKind: StudioMediaKind
  previewUrl?: string
}

export type StudioStoryboardDialogueKind = 'dialogue' | 'narration'

export interface StudioStoryboardDialogueItem {
  id: string
  kind: StudioStoryboardDialogueKind
  speaker?: string
  content: string
}

export interface StudioStoryboardTableRow {
  id: string
  shot: string
  duration: number
  description: string
  shotSize: string
  lighting: string
  dialogue: StudioStoryboardDialogueItem[]
  soundEffect: string
  cameraMovement: string
  finalPrompt: string
  motionPrompt: string
  rowColor?: string
}

export type StudioStoryboardAssetKind = 'scene' | 'character' | 'prop'

export interface StudioStoryboardAssetRef {
  kind: StudioStoryboardAssetKind
  nodeId: string
  title: string
  imageUrl: string
}

export interface StudioFlowAssetImagePreview {
  imageId: number | string
  name?: string
  imageUrl: string
  selected: boolean
}

export interface StudioFlowAssetFormPreview {
  formId: number
  name: string
  prompt: string
  selectedImageUrl?: string
  images: StudioFlowAssetImagePreview[]
}

export interface StudioNodeData extends Record<string, unknown> {
  kind: StudioNodeKind
  title: string
  subtitle?: string
  zoneId?: string
  status: StudioNodeStatus
  prompt: string
  imagePurpose?: StudioImagePurpose
  composerAgentOpen?: boolean
  composerSkillId?: string
  composerRefs?: StudioComposerRef[]
  negativePrompt?: string
  model?: string
  modelName?: string
  modelCostCredits?: number | null
  modelIsFree?: boolean
  recommendedDurationSeconds?: number | null
  recommendedDurationSource?: string | null
  recommendedDurationDescription?: string | null
  composerAgentCode?: string
  composerAgentName?: string
  mediaKind?: StudioMediaKind
  generationOptions?: {
    aspectRatio?: string
    duration?: number
    motion?: string
    shootingTechnique?: string
    voice?: string
    quality?: string
    count?: number
    videoMode?: StudioVideoGenMode
    endFrameEnabled?: boolean
    startFrameUrl?: string
    endFrameUrl?: string
    styleId?: string
    styleName?: string
    stylePreviewUrl?: string
  }
  mediaUrl?: string
  resultSummary?: string
  storyboardRows?: StudioStoryboardTableRow[]
  storyboardAssets?: StudioStoryboardAssetRef[]
  flowAssetForms?: StudioFlowAssetFormPreview[]
  storyboardWorkflowStep?: 1 | 2 | 3
  storyboardPromptMode?: 'smart' | 'balanced'
  storyboardPromptTaskId?: number
  storyboardPromptTaskStatus?: 'QUEUED' | 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  storyboardPromptTaskRowIds?: string[]
  errorMessage?: string
  storyboardId?: string
  assetId?: string
  bindingKey?: string
  flowBinding?: StudioFlowBinding
  /** 本地持久化只保存绑定键和布局，业务内容始终由接口快照恢复。 */
  flowLayoutOnly?: boolean
  createdAt: string
  updatedAt: string
}

export type StudioCanvasNode = Node<StudioNodeData, 'studioNode'>

export interface StudioEdgeData extends Record<string, unknown> {
  kind: StudioEdgeKind
  label: string
  flowManaged?: boolean
  bindingKey?: string
  energy?: boolean
}

export type StudioFlowEdge = Edge<StudioEdgeData>

export interface StudioZone {
  id: string
  title: string
  caption: string
  color: string
  position: XYPosition
  width: number
  height: number
  collapsed: boolean
}

export interface StudioTask {
  id: string
  title: string
  nodeId?: string
  runId?: string
  stage: string
  progress: number
  status: StudioTaskStatus
  errorMessage?: string
  remoteTaskId?: number
  remoteTaskType?: 'storyboard_prompt'
  completedNodeIds?: string[]
  createdAt: string
  updatedAt: string
}

export interface StudioWorkspaceSnapshot {
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
  zones: StudioZone[]
}

export interface StudioWorkspaceData extends StudioWorkspaceSnapshot {
  version: 3
  scopeKey: string
  title: string
  viewport: Viewport
  tasks: StudioTask[]
  savedAt: string
}
