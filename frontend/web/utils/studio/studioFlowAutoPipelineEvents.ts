import type { StudioFlowAutoPipelineStage } from './studioFlowAutoPipelineRunner'

export const STUDIO_FLOW_AUTO_PIPELINE_START_EVENT = 'studio-flow-auto-pipeline-start'
export const STUDIO_FLOW_AUTO_PIPELINE_PREPARE_EVENT = 'studio-flow-auto-pipeline-prepare'

export interface StudioFlowAutoPipelineStartDetail {
  projectId: number
  episodeId: number
  /** 从指定阶段续跑（充值后 / 失败重试） */
  fromStage?: StudioFlowAutoPipelineStage
}

const RESUME_STORAGE_PREFIX = 'studio-flow-auto-pipeline-resume:'
const PENDING_STORAGE_PREFIX = 'studio-flow-auto-pipeline-pending:'

export function studioFlowAutoPipelineResumeKey(projectId: number, episodeId: number): string {
  return `${RESUME_STORAGE_PREFIX}${projectId}:${episodeId}`
}

export function writeStudioFlowAutoPipelineResume(
  projectId: number,
  episodeId: number,
  failedStage: StudioFlowAutoPipelineStage
): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(
    studioFlowAutoPipelineResumeKey(projectId, episodeId),
    JSON.stringify({ failedStage, updatedAt: new Date().toISOString() })
  )
}

export function readStudioFlowAutoPipelineResume(
  projectId: number,
  episodeId: number
): StudioFlowAutoPipelineStage | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(studioFlowAutoPipelineResumeKey(projectId, episodeId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as { failedStage?: StudioFlowAutoPipelineStage }
    return parsed.failedStage ?? null
  } catch {
    return null
  }
}

export function clearStudioFlowAutoPipelineResume(projectId: number, episodeId: number): void {
  if (typeof window === 'undefined') return
  window.localStorage.removeItem(studioFlowAutoPipelineResumeKey(projectId, episodeId))
}

export function studioFlowAutoPipelinePendingKey(projectId: number, episodeId: number): string {
  return `${PENDING_STORAGE_PREFIX}${projectId}:${episodeId}`
}

export function writeStudioFlowAutoPipelinePending(projectId: number, episodeId: number): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(
    studioFlowAutoPipelinePendingKey(projectId, episodeId),
    JSON.stringify({ updatedAt: new Date().toISOString() })
  )
}

export function readStudioFlowAutoPipelinePending(projectId: number, episodeId: number): boolean {
  if (typeof window === 'undefined') return false
  return Boolean(window.localStorage.getItem(studioFlowAutoPipelinePendingKey(projectId, episodeId)))
}

export function clearStudioFlowAutoPipelinePending(projectId: number, episodeId: number): void {
  if (typeof window === 'undefined') return
  window.localStorage.removeItem(studioFlowAutoPipelinePendingKey(projectId, episodeId))
}

export function dispatchStudioFlowAutoPipelinePrepare(detail: StudioFlowAutoPipelineStartDetail): void {
  if (typeof window === 'undefined') return
  clearStudioFlowAutoPipelineResume(detail.projectId, detail.episodeId)
  writeStudioFlowAutoPipelinePending(detail.projectId, detail.episodeId)
  window.dispatchEvent(new CustomEvent<StudioFlowAutoPipelineStartDetail>(
    STUDIO_FLOW_AUTO_PIPELINE_PREPARE_EVENT,
    { detail }
  ))
}

export function dispatchStudioFlowAutoPipelineStart(detail: StudioFlowAutoPipelineStartDetail): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<StudioFlowAutoPipelineStartDetail>(
    STUDIO_FLOW_AUTO_PIPELINE_START_EVENT,
    { detail }
  ))
}
