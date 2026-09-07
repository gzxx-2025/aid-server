import { DEFAULT_STUDIO_ZONES } from '@/config/studioMeta'
import type { StudioWorkspaceData } from '@/types/studio'

const STORAGE_PREFIX = 'aid-studio-flow-layout:v1:'

function getStorage(): Storage | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function createEmptyStudioFlowWorkspace(scopeKey: string): StudioWorkspaceData {
  return {
    version: 3,
    scopeKey,
    title: 'AID Studio',
    nodes: [],
    edges: [],
    zones: structuredClone(DEFAULT_STUDIO_ZONES),
    viewport: { x: 38, y: 72, zoom: 0.78 },
    tasks: [],
    savedAt: new Date(0).toISOString()
  }
}

export async function loadStudioFlowLayout(scopeKey: string): Promise<StudioWorkspaceData> {
  const storage = getStorage()
  const raw = storage?.getItem(`${STORAGE_PREFIX}${scopeKey}`)
  if (!raw) return createEmptyStudioFlowWorkspace(scopeKey)
  try {
    const parsed = JSON.parse(raw) as Partial<StudioWorkspaceData> & {
      version?: number
      scopeKey?: string
    }
    if (![1, 2, 3].includes(Number(parsed.version)) || parsed.scopeKey !== scopeKey) {
      return createEmptyStudioFlowWorkspace(scopeKey)
    }
    return {
      ...createEmptyStudioFlowWorkspace(scopeKey),
      title: String(parsed.title || 'AID Studio'),
      nodes: Array.isArray(parsed.nodes) ? parsed.nodes : [],
      edges: Array.isArray(parsed.edges) ? parsed.edges : [],
      zones: Array.isArray(parsed.zones) && parsed.zones.length
        ? parsed.zones
        : structuredClone(DEFAULT_STUDIO_ZONES),
      viewport: parsed.viewport ?? { x: 38, y: 72, zoom: 0.78 },
      savedAt: String(parsed.savedAt || new Date(0).toISOString())
    }
  } catch {
    return createEmptyStudioFlowWorkspace(scopeKey)
  }
}

export async function saveStudioFlowLayout(workspace: StudioWorkspaceData): Promise<{ savedAt: string }> {
  return saveStudioFlowLayoutSync(workspace)
}

export function saveStudioFlowLayoutSync(workspace: StudioWorkspaceData): { savedAt: string } {
  const savedAt = new Date().toISOString()
  getStorage()?.setItem(
    `${STORAGE_PREFIX}${workspace.scopeKey}`,
    JSON.stringify({ ...workspace, savedAt })
  )
  return { savedAt }
}
