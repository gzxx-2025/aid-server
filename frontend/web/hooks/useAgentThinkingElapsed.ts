'use client'

import { useEffect, useState } from 'react'

export function useAgentThinkingElapsed(
  startedAt: string | number | null | undefined,
  live: boolean,
  completedAt?: string | number | null
): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!live) {
      setNow(Date.now())
      return
    }
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [live])

  const started = (() => {
    if (startedAt == null || startedAt === '') return Date.now()
    if (typeof startedAt === 'number') return startedAt
    const parsed = Date.parse(startedAt)
    return Number.isFinite(parsed) ? parsed : Date.now()
  })()

  const ended = (() => {
    if (!live && completedAt != null && completedAt !== '') {
      if (typeof completedAt === 'number') return completedAt
      const parsed = Date.parse(String(completedAt))
      if (Number.isFinite(parsed)) return parsed
    }
    return now
  })()

  return Math.max(0, Math.round((ended - started) / 1000))
}
