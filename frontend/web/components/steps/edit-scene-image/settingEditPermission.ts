import type { EditSceneImageModalProps } from './types'

export function resolveSettingEditBlockedTooltip(
  source: EditSceneImageModalProps['manualSettingEditBlockedTooltip'],
  sceneIndex: number
): string | null {
  const value = typeof source === 'function' ? source(sceneIndex) : source
  const text = typeof value === 'string' ? value.trim() : ''
  return text || null
}
