import {
  WORKS_PAGE_TAB_FILM,
  WORKS_PAGE_TAB_QUERY_KEY,
  WORKS_PAGE_TAB_SERIES
} from './createFlowRoutes'

export type WorksPageTab = typeof WORKS_PAGE_TAB_FILM | typeof WORKS_PAGE_TAB_SERIES

export function resolveWorksPageTab(
  tabRaw?: string | null,
  typeRaw?: string | null
): WorksPageTab {
  const raw = String(tabRaw || typeRaw || '').trim().toLowerCase()
  if (raw === WORKS_PAGE_TAB_SERIES || raw === 'tv') return WORKS_PAGE_TAB_SERIES
  if (raw === WORKS_PAGE_TAB_FILM || raw === 'movie') return WORKS_PAGE_TAB_FILM
  return WORKS_PAGE_TAB_FILM
}

export function buildWorksPageHref(currentSearch: string, tab: WorksPageTab): string {
  const params = new URLSearchParams(currentSearch.startsWith('?') ? currentSearch.slice(1) : currentSearch)
  params.delete('type')
  if (tab === WORKS_PAGE_TAB_SERIES) {
    params.set(WORKS_PAGE_TAB_QUERY_KEY, WORKS_PAGE_TAB_SERIES)
  } else {
    params.delete(WORKS_PAGE_TAB_QUERY_KEY)
  }
  const qs = params.toString()
  return qs ? `/works?${qs}` : '/works'
}
