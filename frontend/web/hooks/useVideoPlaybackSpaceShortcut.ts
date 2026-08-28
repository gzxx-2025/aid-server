'use client'

import { useEffect,useRef } from 'react'
type PlaybackShortcutEntry = {
  enabled: boolean
  toggle: () => void | Promise<void>
}

const activeEntries: PlaybackShortcutEntry[] = []
let listenerAttached = false

function isInteractiveTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  return Boolean(
    target.closest(
      'input, textarea, select, button, a, [contenteditable="true"], [role="button"], [role="slider"]'
    )
  )
}

function onWindowKeydown(event: KeyboardEvent) {
  if (
    event.defaultPrevented ||
    event.repeat ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    (event.code !== 'Space' && event.key !== ' ' && event.key !== 'Spacebar') ||
    isInteractiveTarget(event.target)
  ) {
    return
  }

  const entry = [...activeEntries].reverse().find((item) => item.enabled)
  if (!entry) return
  event.preventDefault()
  void entry.toggle()
}

function attachListener() {
  if (listenerAttached || typeof window === 'undefined') return
  window.addEventListener('keydown', onWindowKeydown)
  listenerAttached = true
}

function detachListenerIfIdle() {
  if (!listenerAttached || activeEntries.length > 0 || typeof window === 'undefined') return
  window.removeEventListener('keydown', onWindowKeydown)
  listenerAttached = false
}

function removeEntry(entry: PlaybackShortcutEntry) {
  const index = activeEntries.indexOf(entry)
  if (index >= 0) activeEntries.splice(index, 1)
}

function promoteEntry(entry: PlaybackShortcutEntry) {
  removeEntry(entry)
  activeEntries.push(entry)
}

/** 最后激活的可见视频弹窗独占空格播放/暂停，输入控件和按钮保持原生键盘行为。 */
export function useVideoPlaybackSpaceShortcut(
  enabled: boolean,
  toggle: () => void | Promise<void>
) {
  const entryRef = useRef<PlaybackShortcutEntry | null>(null)
  if (!entryRef.current) {
    entryRef.current = { enabled: false, toggle }
  }
  entryRef.current.toggle = toggle

  useEffect(() => {
    attachListener()
    const entry = entryRef.current!
    return () => {
      removeEntry(entry)
      detachListenerIfIdle()
    }
  }, [])

  useEffect(() => {
    const entry = entryRef.current!
    entry.enabled = enabled
    if (enabled) promoteEntry(entry)
    else removeEntry(entry)
  }, [enabled])
}
