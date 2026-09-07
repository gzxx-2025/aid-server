import type { CreationStep } from '@/types'
import { creationStepToRoutePath } from '@/utils/createFlowRoutes'
import { parsePositiveStudioId, parseStudioEpisodeId } from './studioFlowEntry'

type StudioFlowHrefInput = {
  projectId?: number | null
  episodeId?: number | null
  from?: string | null
  extraQuery?: Record<string, string | null | undefined>
}

function applyStudioFlowScope(query: URLSearchParams, input: StudioFlowHrefInput): void {
  if (input.projectId && input.projectId > 0) query.set('projectId', String(input.projectId))
  else query.delete('projectId')

  if (input.episodeId != null && input.episodeId >= 0) query.set('episodeId', String(input.episodeId))
  else query.delete('episodeId')

  if (input.from) query.set('from', input.from)
  else if (input.from === null) query.delete('from')

  for (const [key, value] of Object.entries(input.extraQuery ?? {})) {
    if (value == null || value === '') query.delete(key)
    else query.set(key, value)
  }
}

function href(path: string, query: URLSearchParams): string {
  const search = query.toString()
  return search ? `${path}?${search}` : path
}

export function buildStudioFlowCanvasHref(input: StudioFlowHrefInput = {}): string {
  const query = new URLSearchParams()
  applyStudioFlowScope(query, input)
  return href('/create/studio', query)
}

export function buildStudioFlowCanvasEntryHref(search = ''): string {
  const query = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
  const projectId = parsePositiveStudioId(query.get('projectId') || query.get('id') || query.get('workId'))
  const episodeId = parseStudioEpisodeId(query.get('episodeId'))
  query.delete('id')
  query.delete('workId')
  applyStudioFlowScope(query, { projectId, episodeId })
  return href('/create/studio', query)
}

export function buildStudioStepsFlowHref(input: StudioFlowHrefInput & { step: CreationStep }): string {
  const query = new URLSearchParams()
  if (input.projectId && input.projectId > 0) {
    query.set('projectId', String(input.projectId))
    query.set('id', String(input.projectId))
  }
  if (input.episodeId != null && input.episodeId >= 0) {
    query.set('episodeId', String(input.episodeId))
  }
  if (input.from) query.set('from', input.from)
  for (const [key, value] of Object.entries(input.extraQuery ?? {})) {
    if (value == null || value === '') query.delete(key)
    else query.set(key, value)
  }
  return href(creationStepToRoutePath(input.step), query)
}
