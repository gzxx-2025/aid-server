'use client'

import { useCallback,useEffect,useRef,useState,type RefObject } from 'react'
import {
INFINITE_SCROLL_APPEND_DELAY_MS,
waitInfiniteScrollAppendDelay
} from '~/utils/infiniteScrollDelay'
interface InfiniteScrollPaginationOptions {
  pageSize?: number
  /** 距底部多少 px 触发加载 */
  threshold?: number
  /** 上拉加载后追加内容的最低等待时间（ms），默认 1000 */
  appendDelayMs?: number
}

/**
 * 列表触底分页加载：默认 20 条/页，带加载态与平滑追加动画 tick。
 */
export function useInfiniteScrollPagination<T>(
  scrollRootRef: RefObject<HTMLElement | null>,
  fetchPage: (pageNum: number, pageSize: number) => Promise<{ rows: T[]; hasMore: boolean }>,
  options: InfiniteScrollPaginationOptions = {}
) {
  const pageSize = options.pageSize ?? 20
  const threshold = options.threshold ?? 120
  const appendDelayMs = options.appendDelayMs ?? INFINITE_SCROLL_APPEND_DELAY_MS

  const [items, setItems] = useState<T[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [initialLoaded, setInitialLoaded] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [appendTick, setAppendTick] = useState(0)

  // 回调中的并发闸门用 ref 镜像，避免闭包读到旧 state
  const gateRef = useRef({ loading: false, loadingMore: false, hasMore: true, pageNum: 0 })
  const fetchPageRef = useRef(fetchPage)
  fetchPageRef.current = fetchPage
  const boundElRef = useRef<HTMLElement | null>(null)

  const isEmpty = initialLoaded && !loading && items.length === 0

  const loadNextPage = useCallback(
    async (reset = false) => {
      const gate = gateRef.current
      if (gate.loading || gate.loadingMore) return
      if (!reset && !gate.hasMore) return

      const startedAt = Date.now()

      if (reset) {
        gate.loading = true
        gate.pageNum = 0
        gate.hasMore = true
        setLoading(true)
        setHasMore(true)
        setItems([])
        setLoadError(false)
      } else {
        gate.loadingMore = true
        setLoadingMore(true)
      }

      const nextPage = reset ? 1 : gate.pageNum + 1
      try {
        const { rows, hasMore: more } = await fetchPageRef.current(nextPage, pageSize)
        if (!reset) {
          await waitInfiniteScrollAppendDelay(startedAt, appendDelayMs)
        }
        if (reset) {
          setItems(rows)
        } else if (rows.length) {
          setItems((prev) => [...prev, ...rows])
          setAppendTick((t) => t + 1)
        }
        gate.pageNum = nextPage
        gate.hasMore = more
        setHasMore(more)
        setLoadError(false)
      } catch {
        setLoadError(true)
        if (reset) setItems([])
      } finally {
        gate.loading = false
        gate.loadingMore = false
        setLoading(false)
        setLoadingMore(false)
        setInitialLoaded(true)
      }
    },
    [pageSize, appendDelayMs]
  )

  const onScroll = useCallback(() => {
    const el = scrollRootRef.current
    const gate = gateRef.current
    if (!el || gate.loading || gate.loadingMore || !gate.hasMore) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    if (distance <= threshold) {
      void loadNextPage(false)
    }
  }, [scrollRootRef, threshold, loadNextPage])

  const unbindScroll = useCallback(() => {
    boundElRef.current?.removeEventListener('scroll', onScroll)
    boundElRef.current = null
  }, [onScroll])

  const bindScroll = useCallback(() => {
    unbindScroll()
    const el = scrollRootRef.current
    if (!el) return
    el.addEventListener('scroll', onScroll, { passive: true })
    boundElRef.current = el
  }, [scrollRootRef, onScroll, unbindScroll])

  const reload = useCallback(async () => {
    await loadNextPage(true)
    requestAnimationFrame(() => bindScroll())
  }, [loadNextPage, bindScroll])

  useEffect(() => unbindScroll, [unbindScroll])

  return {
    items,
    /** 对应原 Vue 直改 pagination.items.value（如置顶重排）；不影响分页闸门 */
    setItems,
    hasMore,
    loading,
    loadingMore,
    initialLoaded,
    loadError,
    isEmpty,
    appendTick,
    reload,
    loadNextPage,
    bindScroll,
    unbindScroll,
    onScroll
  }
}
