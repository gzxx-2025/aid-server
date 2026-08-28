/** 编辑弹窗「导入参考图」条：超过 N 张时折叠展示（仅 UI，与业务上传上限无关） */

export const COLLAPSE_VISIBLE_COUNT = 4

/** 是否需要展示展开/收起箭头 */
export function shouldShowReferenceStripCollapseToggle(
  count: number,
  collapseCount = COLLAPSE_VISIBLE_COUNT
): boolean {
  return Number(count) > collapseCount
}

function toEntries<T>(
  items: T[] | null | undefined,
  start: number,
  end: number
): Array<{ item: T; originalIndex: number }> {
  if (!Array.isArray(items) || items.length === 0 || start >= end) return []
  const entries: Array<{ item: T; originalIndex: number }> = []
  for (let i = start; i < end; i++) {
    entries.push({ item: items[i] as T, originalIndex: i })
  }
  return entries
}

/** 首行固定展示的前 N 项（导入按钮始终跟在其后） */
export function getPinnedReferenceStripEntries<T>(
  items: T[] | null | undefined,
  collapseCount = COLLAPSE_VISIBLE_COUNT
): Array<{ item: T; originalIndex: number }> {
  if (!Array.isArray(items) || items.length === 0) return []
  const end = Math.min(items.length, collapseCount)
  return toEntries(items, 0, end)
}

/** 展开后追加展示的溢出项（不含前 N 项，也不含导入按钮） */
export function getOverflowReferenceStripEntries<T>(
  items: T[] | null | undefined,
  collapseCount = COLLAPSE_VISIBLE_COUNT
): Array<{ item: T; originalIndex: number }> {
  if (!Array.isArray(items) || items.length <= collapseCount) return []
  return toEntries(items, collapseCount, items.length)
}

/**
 * 按展开态裁剪可见列表。收起时始终取前缀，保证下标与完整列表一致。
 * @deprecated 布局已拆为首行固定 + 溢出行，优先用 getPinned / getOverflow
 */
export function getVisibleReferenceStripItems<T>(
  items: T[] | null | undefined,
  expanded: boolean,
  collapseCount = COLLAPSE_VISIBLE_COUNT
): T[] {
  return getVisibleReferenceStripEntries(items, expanded, collapseCount).map((e) => e.item)
}

/** 可见项 + 在完整列表中的原始下标（删除时必须用 originalIndex） */
export function getVisibleReferenceStripEntries<T>(
  items: T[] | null | undefined,
  expanded: boolean,
  collapseCount = COLLAPSE_VISIBLE_COUNT
): Array<{ item: T; originalIndex: number }> {
  const pinned = getPinnedReferenceStripEntries(items, collapseCount)
  if (!expanded) return pinned
  return [...pinned, ...getOverflowReferenceStripEntries(items, collapseCount)]
}

/** 数量回落到折叠阈值及以下时，应自动收起 */
export function shouldAutoCollapseReferenceStrip(
  count: number,
  collapseCount = COLLAPSE_VISIBLE_COUNT
): boolean {
  return Number(count) <= collapseCount
}
