'use client'

import { useEffect, useRef, useState, type CSSProperties } from 'react'
import './AidHoverLogo.css'

const LOGO_PARTS = [
  {
    id: 'letter-a',
    d: 'M12.6259 20.5236C14.5423 17.1958 19.5084 17.1958 21.4248 20.5236L25.0156 26.7588V35.6172L17.8597 23.0357C17.6218 22.6175 16.9989 22.6156 16.7585 23.0325L10.4222 34.0183H14.9816L16.7769 31.4227C17.0289 31.0584 17.5863 31.0639 17.8305 31.433L19.5407 34.0183L22.0387 38H3.62985C3.15047 38 2.84733 37.504 3.07984 37.1001L12.6259 20.5236Z',
    delayMs: 0
  },
  {
    id: 'letter-i',
    d: 'M25.9589 18.6061C25.9589 18.2713 26.2405 18 26.5878 18H29.732C30.0794 18 30.3609 18.2713 30.3609 18.6061V36.9968C30.3609 37.3315 30.0794 37.6029 29.732 37.6029H26.5878C26.2405 37.6029 25.9589 37.3315 25.9589 36.9968V18.6061Z',
    delayMs: 150
  },
  {
    id: 'letter-d',
    d: 'M42.8287 18C48.4461 18.0001 53 22.3883 53 27.8015C53 33.2146 48.4461 37.6029 42.8287 37.6029H33.1454C33.0401 37.6029 32.9447 37.563 32.8736 37.4985L37.3455 33.7742H42.7516C46.1747 33.7741 48.9497 31.1001 48.9497 27.8015C48.9497 24.5029 46.1747 21.8288 42.7516 21.8287H37.4186L32.8742 18.1033C32.9452 18.0393 33.0405 18 33.1454 18H42.8287Z',
    delayMs: 300
  },
  {
    id: 'letter-d-inner',
    d: 'M41.3448 27.2678L32.8791 21.0629V34.5399L41.3488 28.2297C41.6761 27.9858 41.6741 27.5091 41.3448 27.2678Z',
    delayMs: 450
  }
] as const

const DRAW_DURATION_MS = 680
const HOVER_DRAW_DURATION_MS = 400
const PERIODIC_INTERVAL_MS = 10_000
const STAGGER_END_MS = LOGO_PARTS[LOGO_PARTS.length - 1].delayMs

type DrawMode = 'intro' | 'hover'

export default function AidHoverLogo({
  alt = 'AID',
  className
}: {
  alt?: string
  className?: string
}) {
  const [isDrawing, setIsDrawing] = useState(false)
  const [isComplete, setIsComplete] = useState(false)
  const [reducedMotion, setReducedMotion] = useState(false)
  const [drawMode, setDrawMode] = useState<DrawMode>('intro')
  const [drawCycle, setDrawCycle] = useState(0)
  const drawingRef = useRef(false)
  const introFinishedRef = useRef(false)
  const completeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const periodicTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  function clearTimers() {
    if (completeTimerRef.current) clearTimeout(completeTimerRef.current)
    if (periodicTimerRef.current) clearInterval(periodicTimerRef.current)
  }

  function playDraw(mode: DrawMode) {
    if (drawingRef.current || reducedMotion) return
    drawingRef.current = true
    setDrawMode(mode)
    setDrawCycle((value) => value + 1)
    setIsComplete(false)
    setIsDrawing(false)
    requestAnimationFrame(() => setIsDrawing(true))
    const duration = STAGGER_END_MS + (mode === 'intro' ? DRAW_DURATION_MS : HOVER_DRAW_DURATION_MS) + 80
    completeTimerRef.current = setTimeout(() => {
      drawingRef.current = false
      introFinishedRef.current = true
      setIsDrawing(false)
      setIsComplete(true)
    }, duration)
  }

  useEffect(() => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    setReducedMotion(reduced)
    if (reduced) {
      introFinishedRef.current = true
      setIsComplete(true)
    } else {
      playDraw('intro')
      periodicTimerRef.current = setInterval(() => {
        if (introFinishedRef.current) playDraw('hover')
      }, PERIODIC_INTERVAL_MS)
    }
    return clearTimers
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const groupClass = [
    'aid-hover-logo__group',
    isDrawing ? 'is-drawing' : '',
    isComplete ? 'is-complete' : '',
    reducedMotion ? 'is-reduced' : '',
    drawMode === 'hover' ? 'is-hover-draw' : ''
  ].filter(Boolean).join(' ')

  return (
    <span
      className={className ? `aid-hover-logo ${className}` : 'aid-hover-logo'}
      role="img"
      aria-label={alt}
      onMouseEnter={() => introFinishedRef.current && playDraw('hover')}
      onFocus={() => introFinishedRef.current && playDraw('hover')}
    >
      <svg className="aid-hover-logo__svg" viewBox="0 0 56 56" width="56" height="56" fill="none" aria-hidden="true">
        <g className={groupClass}>
          {LOGO_PARTS.map((part) => (
            <path
              id={part.id}
              key={`${part.id}-${drawCycle}`}
              className="aid-hover-logo__path"
              d={part.d}
              pathLength={1}
              style={{ '--draw-delay': `${part.delayMs}ms` } as CSSProperties}
            />
          ))}
        </g>
      </svg>
    </span>
  )
}
