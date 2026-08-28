/**
 * 素材准备 · 形态编辑弹窗 SSE 作用域键。
 *
 * 列表槽位键仍是 `${assetIndex}-${formIndex}`（分属 character/prop 两套 map）。
 * 弹窗 modalSseTasks 必须带类型前缀，否则角色/道具同槽（如双方都是 0-0）会串 loading。
 */

export type Step3FormAssetTab = 'character' | 'prop'

export type ParsedFormEditorScopeKey = {
  tab: Step3FormAssetTab
  assetIndex: number
  formIndex: number
}

/** 列表 / generationStatus 槽位键：`${assetIndex}-${formIndex}` */
export function buildFormSlotKey(assetIndex: number, formIndex: number): string {
  return `${assetIndex}-${formIndex}`
}

/** 弹窗 SSE 键：`character-0-1` / `prop-0-1` */
export function buildFormEditorScopeKey(
  tab: Step3FormAssetTab,
  assetIndex: number,
  formIndex: number
): string {
  return `${tab}-${assetIndex}-${formIndex}`
}

export function formSlotKeyToEditorScopeKey(
  tab: Step3FormAssetTab,
  slotKey: string
): string | null {
  const bare = parseBareFormSlotKey(slotKey)
  if (!bare) return null
  return buildFormEditorScopeKey(tab, bare.assetIndex, bare.formIndex)
}

/** 仅匹配带类型前缀的形态键（不含主资产 character-0 / prop-0） */
export function parseFormEditorScopeKey(key: string): ParsedFormEditorScopeKey | null {
  const raw = String(key || '').trim()
  const m = raw.match(/^(character|prop)-(\d+)-(\d+)$/i)
  if (!m) return null
  return {
    tab: m[1].toLowerCase() as Step3FormAssetTab,
    assetIndex: Number(m[2]),
    formIndex: Number(m[3])
  }
}

/** 历史裸键 `0-1`（迁移前写入 modalSseTasks，易跨角色/道具串流） */
export function parseBareFormSlotKey(
  key: string
): { assetIndex: number; formIndex: number } | null {
  const raw = String(key || '').trim()
  if (!/^\d+-\d+$/.test(raw)) return null
  const [a, f] = raw.split('-')
  const assetIndex = Number(a)
  const formIndex = Number(f)
  if (!Number.isFinite(assetIndex) || !Number.isFinite(formIndex)) return null
  return { assetIndex, formIndex }
}

export function isLegacyBareFormSlotScopeKey(key: string): boolean {
  return parseBareFormSlotKey(key) != null
}

/** 弹窗 onUpdate 的 scopeKey → 列表槽位键；兼容新前缀与历史裸键 */
export function formEditorScopeKeyToSlotKey(key: string): string | null {
  const parsed = parseFormEditorScopeKey(key)
  if (parsed) return buildFormSlotKey(parsed.assetIndex, parsed.formIndex)
  const bare = parseBareFormSlotKey(key)
  if (bare) return buildFormSlotKey(bare.assetIndex, bare.formIndex)
  return null
}

/** 历史裸键在确认归属后可迁到带前缀键 */
export function legacyBareFormSlotAliasOf(editorScopeKey: string): string | null {
  const parsed = parseFormEditorScopeKey(editorScopeKey)
  if (!parsed) return null
  return buildFormSlotKey(parsed.assetIndex, parsed.formIndex)
}
