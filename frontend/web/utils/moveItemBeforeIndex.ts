/**
 * 将 `fromIndex` 处的元素移动到「插入到原列表中 `insertBeforeIndex` 下标所指元素之前」后的结果。
 * 例如 [A,B,C,D] 将最后一项移到最前：fromIndex=3, insertBeforeIndex=0 → [D,A,B,C]
 */
export function moveItemBeforeIndex<T>(items: T[], fromIndex: number, insertBeforeIndex: number): T[] {
  const len = items.length
  if (len <= 1) return [...items]
  if (fromIndex < 0 || fromIndex >= len) return [...items]
  if (insertBeforeIndex < 0 || insertBeforeIndex > len) return [...items]
  if (fromIndex === insertBeforeIndex) return [...items]
  const next = [...items]
  const [moved] = next.splice(fromIndex, 1)
  let at = insertBeforeIndex
  if (fromIndex < insertBeforeIndex) at = insertBeforeIndex - 1
  at = Math.max(0, Math.min(at, next.length))
  next.splice(at, 0, moved)
  return next
}
