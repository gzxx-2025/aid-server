'use client'

import { useCallback,useEffect,useMemo,useRef,type RefObject } from 'react'
import { useInfiniteScrollPagination } from '~/composables/useInfiniteScrollPagination'
import {
dedupeStyleLibraryCardsPreferOfficial,
type StyleLibraryCard
} from '~/composables/usePromptDictionary'
import { userAssetMergedPage } from '~/utils/businessApi'
import {
isOfficialStyleLibraryCard,
mapMergedAssetsToStyleCards,
orderStyleLibraryCardsCustomFirst,
STYLE_LIBRARY_PAGE_SIZE
} from '~/utils/mapMergedStyleAssets'

/**
 * 精选风格库：合并资产分页（30/页）+ “更多”面板触底加载。
 * 滚动容器由调用方提供（创建弹窗右侧 / 流程页配置同一套 GlobalSetting）。
 * 触底监听请用 onScrollWhenExpanded，避免默认常用风格区域误加载。
 */
export function useStyleLibraryMergedPagination(
  scrollRootRef: RefObject<HTMLElement | null>,
  categoryCode: string
) {
  /** 供 fetchPage 读取当前已加载列表（避免闭包引用尚未赋值的 pagination） */
  const loadedCardsRef = useRef<StyleLibraryCard[]>([])
  /** 原 Vue 里 hasMore/loadError ref 可同步读取；React 侧用 ref 镜像供 async 流程使用 */
  const flagsRef = useRef({ hasMore: true, loadError: false })

  const pagination = useInfiniteScrollPagination<StyleLibraryCard>(
    scrollRootRef,
    async (pageNum, pageSize) => {
      try {
        const { list, total } = await userAssetMergedPage({
          assetType: 'style',
          categoryCode,
          pageNum,
          pageSize
        })
        const existing = pageNum === 1 ? [] : loadedCardsRef.current
        const skipIds = new Set(existing.map((c) => c.id))
        const officialFeaturedStart = existing.filter(isOfficialStyleLibraryCard).length
        const rows = mapMergedAssetsToStyleCards(list, { officialFeaturedStart, skipIds })
        const hasMore = pageNum * pageSize < total
        // 原 flush:'sync' watch 的等价物：翻页解析完成即同步镜像，连续 loadNextPage 之间不依赖渲染提交
        loadedCardsRef.current = [...existing, ...rows]
        flagsRef.current = { hasMore, loadError: false }
        return {
          rows,
          hasMore
        }
      } catch (e) {
        flagsRef.current = { ...flagsRef.current, loadError: true }
        throw e
      }
    },
    { pageSize: STYLE_LIBRARY_PAGE_SIZE }
  )

  const { items, setItems, loadNextPage, unbindScroll, onScroll } = pagination

  // 提交后回写镜像（覆盖 setItems 直改等场景），与原 watch(items, immediate) 对齐
  useEffect(() => {
    loadedCardsRef.current = items
  }, [items])

  const customStyles = useMemo(
    () => items.filter((c) => !isOfficialStyleLibraryCard(c)),
    [items]
  )

  const officialStyles = useMemo(
    () => items.filter(isOfficialStyleLibraryCard),
    [items]
  )

  const mergedStyleList = useMemo(
    () =>
      dedupeStyleLibraryCardsPreferOfficial(
        orderStyleLibraryCardsCustomFirst(items)
      ),
    [items]
  )

  const moveCustomStyleToFront = useCallback(
    (id: string) => {
      const list = loadedCardsRef.current
      const idx = list.findIndex((s) => s.id === id)
      if (idx <= 0) return
      const item = list[idx]!
      const next = [item, ...list.filter((s) => s.id !== id)]
      loadedCardsRef.current = next
      setItems(next)
    },
    [setItems]
  )

  const onScrollWhenExpanded = useCallback(
    (expanded: boolean) => {
      if (!expanded) return
      onScroll()
    },
    [onScroll]
  )

  /** 重置加载首屏；不 bindScroll（由组件滚动事件 + “更多”面板门闩控制）。 */
  const reload = useCallback(async () => {
    unbindScroll()
    await loadNextPage(true)
  }, [unbindScroll, loadNextPage])

  /** async 流程读取最新已加载列表（Vue 侧 ref.value 的等价物） */
  const getLoadedCardsNow = useCallback(() => loadedCardsRef.current, [])

  const getCustomStylesNow = useCallback(
    () => loadedCardsRef.current.filter((c) => !isOfficialStyleLibraryCard(c)),
    []
  )

  const getOfficialStylesNow = useCallback(
    () => loadedCardsRef.current.filter(isOfficialStyleLibraryCard),
    []
  )

  const getMergedStyleListNow = useCallback(
    () =>
      dedupeStyleLibraryCardsPreferOfficial(
        orderStyleLibraryCardsCustomFirst(loadedCardsRef.current)
      ),
    []
  )

  /** async 流程读取最新分页标志（hasMore / loadError） */
  const getFlagsNow = useCallback(() => flagsRef.current, [])

  return {
    items: pagination.items,
    hasMore: pagination.hasMore,
    loading: pagination.loading,
    loadingMore: pagination.loadingMore,
    initialLoaded: pagination.initialLoaded,
    loadError: pagination.loadError,
    isEmpty: pagination.isEmpty,
    appendTick: pagination.appendTick,
    reload,
    loadNextPage: pagination.loadNextPage,
    unbindScroll: pagination.unbindScroll,
    customStyles,
    officialStyles,
    mergedStyleList,
    moveCustomStyleToFront,
    onScrollWhenExpanded,
    pageSize: STYLE_LIBRARY_PAGE_SIZE,
    getLoadedCardsNow,
    getCustomStylesNow,
    getOfficialStylesNow,
    getMergedStyleListNow,
    getFlagsNow
  }
}
