'use client'

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  type ReactNode,
  type UIEvent
} from 'react'
import './agent-conversation-viewport.css'

const STICK_TO_LATEST_DISTANCE_PX = 72
const LOAD_OLDER_THRESHOLD_PX = 48

export interface AgentConversationViewportHandle {
  scrollToLatest: (behavior?: ScrollBehavior) => void
}

interface AgentConversationViewportProps {
  open?: boolean
  className?: string
  children: ReactNode
  updateToken: string | number
  scopeKey?: string | number
  initialLoading?: boolean
  hasOlder?: boolean
  loadingOlder?: boolean
  onLoadOlder?: () => Promise<unknown> | void
  ariaLive?: 'off' | 'polite' | 'assertive'
}

/** 对话区统一滚动行为：默认跟随最新消息，用户上翻后停止抢夺滚动位置。 */
export const AgentConversationViewport = forwardRef<
  AgentConversationViewportHandle,
  AgentConversationViewportProps
>(function AgentConversationViewport(
  {
    open = true,
    className = '',
    children,
    updateToken,
    scopeKey = 'default',
    initialLoading = false,
    hasOlder = false,
    loadingOlder = false,
    onLoadOlder,
    ariaLive = 'off'
  },
  forwardedRef
) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const initializedRef = useRef(false)
  const stickToLatestRef = useRef(true)
  const loadingOlderRef = useRef(false)
  const loadRequestVersionRef = useRef(0)
  const activeScopeKeyRef = useRef<string | number>(scopeKey)

  const scrollToLatest = useCallback((behavior: ScrollBehavior = 'auto') => {
    stickToLatestRef.current = true
    window.requestAnimationFrame(() => {
      const root = rootRef.current
      if (!root) return
      root.scrollTo({ top: root.scrollHeight, behavior })
    })
  }, [])

  useImperativeHandle(forwardedRef, () => ({ scrollToLatest }), [scrollToLatest])

  useEffect(() => {
    if (open) return
    initializedRef.current = false
    stickToLatestRef.current = true
    loadingOlderRef.current = false
    loadRequestVersionRef.current += 1
  }, [open])

  useLayoutEffect(() => {
    if (!Object.is(activeScopeKeyRef.current, scopeKey)) {
      activeScopeKeyRef.current = scopeKey
      initializedRef.current = false
      stickToLatestRef.current = true
      loadingOlderRef.current = false
      loadRequestVersionRef.current += 1
    }
    if (!open || initialLoading) return
    const root = rootRef.current
    if (!root) return
    if (!initializedRef.current) {
      initializedRef.current = true
      stickToLatestRef.current = true
      root.scrollTop = root.scrollHeight
      return
    }
    if (stickToLatestRef.current && !loadingOlderRef.current) {
      root.scrollTop = root.scrollHeight
    }
  }, [initialLoading, open, scopeKey, updateToken])

  const loadOlder = useCallback(async () => {
    const root = rootRef.current
    if (!root || !hasOlder || loadingOlder || loadingOlderRef.current || !onLoadOlder) return
    loadingOlderRef.current = true
    stickToLatestRef.current = false
    const requestVersion = ++loadRequestVersionRef.current
    const requestedScopeKey = scopeKey
    const previousHeight = root.scrollHeight
    const previousTop = root.scrollTop
    try {
      await onLoadOlder()
    } catch {
      // 数据层负责展示错误；滚动容器始终恢复锚点并允许用户再次触发加载。
    } finally {
      window.requestAnimationFrame(() => {
        if (loadRequestVersionRef.current !== requestVersion) return
        const currentRoot = rootRef.current
        if (
          currentRoot === root &&
          Object.is(activeScopeKeyRef.current, requestedScopeKey)
        ) {
          const addedHeight = currentRoot.scrollHeight - previousHeight
          currentRoot.scrollTop = Math.max(0, previousTop + addedHeight)
        }
        loadingOlderRef.current = false
      })
    }
  }, [hasOlder, loadingOlder, onLoadOlder, scopeKey])

  function handleScroll(event: UIEvent<HTMLDivElement>) {
    const root = event.currentTarget
    const distanceToLatest = root.scrollHeight - root.scrollTop - root.clientHeight
    stickToLatestRef.current = distanceToLatest <= STICK_TO_LATEST_DISTANCE_PX
    if (root.scrollTop <= LOAD_OLDER_THRESHOLD_PX) void loadOlder()
  }

  return (
    <div
      ref={rootRef}
      className={`agent-conversation-viewport${className ? ` ${className}` : ''}`}
      aria-live={ariaLive}
      onScroll={handleScroll}
    >
      {hasOlder || loadingOlder ? (
        <div className="agent-conversation-viewport__history" role={loadingOlder ? 'status' : undefined}>
          {loadingOlder ? '正在读取更早对话…' : '继续上拉查看更早对话'}
        </div>
      ) : null}
      {children}
    </div>
  )
})

export default AgentConversationViewport
