'use client'

import {
  AppstoreOutlined,
  BulbOutlined,
  CheckOutlined,
  DownOutlined,
  EditOutlined,
  FileSearchOutlined,
  LoadingOutlined,
  SearchOutlined
} from '@ant-design/icons'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { AgentThinkingStep, AgentThinkingStepKind } from '@/utils/agentThinkingSteps'
import {
  resolveAgentThinkingTrace,
  type AgentThinkingTraceInput
} from '@/utils/agentThinkingSteps'
import { resolveAgentReasoningPreview } from '@/utils/agentReasoningPreview'
import { useAgentThinkingElapsed } from '@/hooks/useAgentThinkingElapsed'
import './agent-thinking.css'

function StepIcon({
  kind,
  spinning = false
}: {
  kind: AgentThinkingStepKind
  spinning?: boolean
}) {
  if (spinning || kind === 'organize') {
    return <LoadingOutlined spin aria-hidden />
  }
  switch (kind) {
    case 'read_document':
      return <FileSearchOutlined aria-hidden />
    case 'read_skill':
      return <AppstoreOutlined aria-hidden />
    case 'brainstorm':
      return <BulbOutlined aria-hidden />
    case 'read_file':
      return <SearchOutlined aria-hidden />
    case 'edit_file':
      return <EditOutlined aria-hidden />
    case 'analyze':
      return <SearchOutlined aria-hidden />
    default:
      return <CheckOutlined aria-hidden />
  }
}

function ThinkingStepRow({
  step,
  durationSeconds,
  shimmer = false
}: {
  step: AgentThinkingStep
  durationSeconds?: number
  shimmer?: boolean
}) {
  return (
    <div className={`agent-thinking-step is-${step.status}`}>
      <span className="agent-thinking-step__icon">
        <StepIcon kind={step.kind} spinning={step.status === 'active' && step.kind === 'organize'} />
      </span>
      <span className={`agent-thinking-step__label${shimmer ? ' is-shimmer' : ''}`}>{step.label}</span>
      {step.status === 'active' && durationSeconds != null ? (
        <span className="agent-thinking-step__duration">{durationSeconds}s</span>
      ) : step.durationMs != null && step.durationMs >= 1000 ? (
        <span className="agent-thinking-step__duration">{Math.round(step.durationMs / 1000)}s</span>
      ) : null}
    </div>
  )
}

export interface AgentThinkingProcessProps extends AgentThinkingTraceInput {
  className?: string
  collapsibleLive?: boolean
}

function ThinkingReasoningViewport({
  reasoning,
  live
}: {
  reasoning?: string | null
  live: boolean
}) {
  const viewportRef = useRef<HTMLDivElement | null>(null)
  const preview = resolveAgentReasoningPreview(String(reasoning || ''), live)
  const text = preview.text

  useLayoutEffect(() => {
    if (!live) return
    const viewport = viewportRef.current
    if (!viewport) return
    viewport.scrollTop = viewport.scrollHeight
  }, [live, text])

  if (!text.trim()) return null

  return (
    <div
      ref={viewportRef}
      className={`agent-thinking-process__reasoning-viewport${live ? ' is-live' : ' is-complete'}`}
      aria-live={live ? 'polite' : undefined}
    >
      <div className={`agent-thinking-process__reasoning${live ? ' is-live' : ''}`}>
        {text}
      </div>
    </div>
  )
}

function ThinkingProcessBody({
  trace,
  elapsedSeconds,
  reasoning,
  live
}: {
  trace: ReturnType<typeof resolveAgentThinkingTrace>
  elapsedSeconds: number
  reasoning?: string | null
  live: boolean
}) {
  const doneSteps = trace.steps.filter((step) => step.status === 'done')
  const activeStep = trace.activeStep
  const showOrganizing = live && trace.showOrganizing && (!activeStep || activeStep.kind === 'organize')
  const hasReasoning = Boolean(String(reasoning || '').trim())

  return (
    <div className="agent-thinking-process__body">
      {trace.operationCount > 0 ? (
        <p className="agent-thinking-process__summary">
          {live ? `已完成 ${doneSteps.length} 个操作` : `已完成 ${trace.operationCount} 个操作`}
        </p>
      ) : null}
      {(live ? doneSteps : trace.steps).map((step) => (
        <ThinkingStepRow
          key={step.id}
          step={live ? step : { ...step, status: 'done' }}
        />
      ))}
      {live && activeStep && activeStep.kind !== 'organize' ? (
        <ThinkingStepRow step={activeStep} durationSeconds={elapsedSeconds} shimmer />
      ) : null}
      {showOrganizing || (live && !trace.steps.length && !hasReasoning) ? (
        <div className="agent-thinking-process__organizing">
          <span className="agent-thinking-step__icon">
            <LoadingOutlined spin aria-hidden />
          </span>
          <span className="agent-thinking-step__label is-shimmer">整理中…</span>
          <span className="agent-thinking-step__duration">{elapsedSeconds}s</span>
        </div>
      ) : null}
      <ThinkingReasoningViewport reasoning={reasoning} live={live} />
    </div>
  )
}

export function AgentThinkingProcess({
  steps,
  reasoning,
  live,
  startedAt,
  completedAt,
  className = '',
  collapsibleLive = false
}: AgentThinkingProcessProps) {
  const [expanded, setExpanded] = useState(live)
  const elapsedSeconds = useAgentThinkingElapsed(startedAt, live, completedAt)
  const trace = resolveAgentThinkingTrace({
    steps,
    reasoning,
    live,
    startedAt,
    completedAt
  })

  useEffect(() => {
    if (live) setExpanded(true)
    else setExpanded(false)
  }, [live])

  const hasTimeRange = startedAt != null && String(startedAt).trim() !== ''
  if (!live && !trace.steps.length && !String(reasoning || '').trim() && !hasTimeRange) return null

  if (live && !collapsibleLive) {
    const doneSteps = trace.steps.filter((step) => step.status === 'done')
    const activeStep = trace.activeStep
    const hasReasoning = Boolean(String(reasoning || '').trim())
    const showOrganizing = trace.showOrganizing && (!activeStep || activeStep.kind === 'organize')

    return (
      <div className={`agent-thinking-process is-live${className ? ` ${className}` : ''}`}>
        {doneSteps.map((step) => (
          <ThinkingStepRow key={step.id} step={step} />
        ))}
        {activeStep && activeStep.kind !== 'organize' ? (
          <ThinkingStepRow step={activeStep} durationSeconds={elapsedSeconds} shimmer />
        ) : null}
        {showOrganizing || (!trace.steps.length && !hasReasoning) ? (
          <div className="agent-thinking-process__organizing">
            <span className="agent-thinking-step__icon">
              <LoadingOutlined spin aria-hidden />
            </span>
            <span className="agent-thinking-step__label is-shimmer">整理中…</span>
            <span className="agent-thinking-step__duration">{elapsedSeconds}s</span>
          </div>
        ) : null}
        <ThinkingReasoningViewport reasoning={reasoning} live />
      </div>
    )
  }

  const totalSeconds = completedAt != null ? trace.totalSeconds : elapsedSeconds

  return (
    <details
      className={`agent-thinking-process ${live ? 'is-live' : 'is-complete'} is-collapsible${className ? ` ${className}` : ''}`}
      open={expanded}
      onToggle={(event) => setExpanded(event.currentTarget.open)}
    >
      <summary>
        <span>{live ? `正在处理 · ${totalSeconds}s` : `处理了 ${totalSeconds}s`}</span>
        <DownOutlined className="agent-thinking-process__chevron" aria-hidden />
      </summary>
      <ThinkingProcessBody
        trace={trace}
        elapsedSeconds={elapsedSeconds}
        reasoning={reasoning}
        live={live}
      />
    </details>
  )
}

export default AgentThinkingProcess
