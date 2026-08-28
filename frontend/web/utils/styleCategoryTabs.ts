type StyleCategoryLike = {
  code?: string | null
  label?: string | null
}

const ALL_STYLE_CATEGORY_CODE = 'all'
const ALL_STYLE_CATEGORY_LABEL = '全部'

export function isAllStyleCategory(category: StyleCategoryLike): boolean {
  const code = String(category.code || '').trim().toLowerCase()
  if (code === ALL_STYLE_CATEGORY_CODE) return true
  return String(category.label || '').trim() === ALL_STYLE_CATEGORY_LABEL
}

export function visibleStyleCategories<T extends StyleCategoryLike>(rows: T[]): T[] {
  return rows.filter((row) => !isAllStyleCategory(row))
}

export function resolveActiveStyleCategoryCode(
  visible: Array<Pick<StyleCategoryLike, 'code'>>,
  currentCode: string
): string {
  const current = String(currentCode || '').trim()
  if (visible.some((row) => String(row.code || '').trim() === current)) return current
  return String(visible[0]?.code || '').trim() || current
}
