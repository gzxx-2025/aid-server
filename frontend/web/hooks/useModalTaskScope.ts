/** 弹窗内异步任务 overlay / loading 作用域键，避免列表 A/B 切换时串流 */

import { parseFormEditorScopeKey } from '~/utils/step3FormEditorScopeKey'

export function buildModalEditorScopeKey(
  editorScopeKey: string,
  sceneIndex: number
): string {
  const base = String(editorScopeKey || '').trim()
  if (!base) return ''
  // 形态弹窗（新）：`character-0-1` / `prop-0-1`；tab 切换时 sceneIndex 为形态下标
  const formTyped = parseFormEditorScopeKey(base)
  if (formTyped) {
    return `${formTyped.tab}-${formTyped.assetIndex}-${sceneIndex}`
  }
  // 历史形态弹窗：base 为裸 `角色下标-形态下标`（易与道具同槽串流，仅兼容旧会话）
  if (/^\d+-\d+$/.test(base)) {
    const charIdx = base.split('-')[0]
    return `${charIdx}-${sceneIndex}`
  }
  // 场景/角色/道具主资产编辑弹窗：base 为打开时的条目，tab 为列表内切换 —— 须按 tab 下标隔离
  const typedMatch = base.match(/^(scene|character|prop)-(\d+)$/i)
  if (typedMatch) {
    return `${typedMatch[1].toLowerCase()}-${sceneIndex}`
  }
  return `${base}#${sceneIndex}`
}

export function buildModalTaskOverlayKey(parts: {
  editorScopeKey: string
  sceneIdx: number
  entityId?: string | number | null
  itemIdx?: number | null
  taskKind?: string
}): string {
  const scope = buildModalEditorScopeKey(parts.editorScopeKey, parts.sceneIdx)
  const prefix = scope || 'modal'
  const entity =
    parts.entityId != null && String(parts.entityId).trim() !== ''
      ? String(parts.entityId)
      : ''
  const item =
    parts.itemIdx != null && Number(parts.itemIdx) >= 0 ? String(parts.itemIdx) : '-1'
  const kind = String(parts.taskKind || 'default').trim()
  return `${prefix}|ent-${entity}|${parts.sceneIdx}|${item}|${kind}`
}

export function matchesModalTaskOverlayKey(
  targetKey: string,
  parts: {
    editorScopeKey: string
    sceneIdx: number
    entityId?: string | number | null
    itemIdx?: number | null
    taskKind?: string
  }
): boolean {
  if (!targetKey) return false
  return targetKey === buildModalTaskOverlayKey(parts)
}

export function matchesAnyModalTaskOverlayKey(
  targetKey: string,
  parts: {
    editorScopeKey: string
    sceneIdx: number
    entityId?: string | number | null
    itemIdx?: number | null
    taskKinds: string[]
  }
): boolean {
  if (!targetKey) return false
  return parts.taskKinds.some((taskKind) =>
    matchesModalTaskOverlayKey(targetKey, { ...parts, taskKind })
  )
}
