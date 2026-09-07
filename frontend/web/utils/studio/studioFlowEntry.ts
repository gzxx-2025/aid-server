import type { UserProjectType } from '@/types/business-api'

export function safeStudioScopePart(
  value: string | null | undefined,
  fallback = 'default'
): string {
  return String(value ?? '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 64) || fallback
}

export function studioWorkspaceScopeKey(
  projectId: string | null | undefined,
  episodeId: string | null | undefined
): string {
  return `project-${safeStudioScopePart(projectId)}:episode-${safeStudioScopePart(episodeId)}:flow`
}

export function parsePositiveStudioId(value: string | null | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

export function parseStudioEpisodeId(value: string | null | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

export function canonicalStudioFlowEpisodeId(
  projectType: UserProjectType,
  requestedEpisodeId: number | null
): number | null {
  return projectType === 'movie' ? 0 : requestedEpisodeId
}
