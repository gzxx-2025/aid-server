export type AgentThinkingStepKind =
  | 'read_document'
  | 'read_skill'
  | 'brainstorm'
  | 'read_file'
  | 'edit_file'
  | 'organize'
  | 'analyze'
  | 'generic'
export type AgentThinkingStepStatus = 'active' | 'done'

export interface AgentThinkingStep {
  id: string
  kind: AgentThinkingStepKind
  label: string
  status: AgentThinkingStepStatus
  durationMs?: number
  startedAt?: string
}

export interface AgentThinkingTraceInput {
  steps?: AgentThinkingStep[]
  reasoning?: string
  live: boolean
  startedAt?: string | number | null
  completedAt?: string | number | null
}

export interface AgentThinkingTraceView {
  steps: AgentThinkingStep[]
  elapsedSeconds: number
  totalSeconds: number
  activeStep: AgentThinkingStep | null
  showOrganizing: boolean
  operationCount: number
}

const STEP_KIND_PATTERNS: Array<[RegExp, AgentThinkingStepKind]> = [
  [/正在阅读|已阅读.*文档|引用节点/, 'read_document'],
  [/技能/, 'read_skill'],
  [/头脑风暴/, 'brainstorm'],
  [/已编辑|正在编辑/, 'edit_file'],
  [/\.md\b|\.json\b|文件/, 'read_file'],
  [/整理中|正在整理|组织回复|拆分.*任务/, 'organize'],
  [/正在理解|上下文分析|识别可执行/, 'analyze']
]

export function inferAgentThinkingStepKind(label: string): AgentThinkingStepKind {
  const text = String(label || '').trim()
  for (const [pattern, kind] of STEP_KIND_PATTERNS) {
    if (pattern.test(text)) return kind
  }
  return 'generic'
}

export function parseReasoningToThinkingSteps(reasoning: string, live: boolean): AgentThinkingStep[] {
  const lines = String(reasoning || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  if (!lines.length) return []
  return lines.map((label, index) => ({
    id: `reasoning-${index}`,
    kind: inferAgentThinkingStepKind(label),
    label,
    status: live && index === lines.length - 1 ? 'active' : 'done'
  }))
}

export function resolveAgentThinkingSteps(
  steps: AgentThinkingStep[] | undefined,
  reasoning: string | undefined,
  live: boolean
): AgentThinkingStep[] {
  if (steps?.length) {
    if (!live) {
      return steps.map((step) => ({ ...step, status: 'done' as const }))
    }
    const activeIndex = steps.findIndex((step) => step.status === 'active')
    if (activeIndex >= 0) return steps
    return steps.map((step, index) => ({
      ...step,
      status: index === steps.length - 1 ? 'active' : 'done'
    }))
  }
  return parseReasoningToThinkingSteps(reasoning ?? '', live)
}

function toTimestamp(value: string | number | null | undefined): number | null {
  if (value == null || value === '') return null
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function formatAgentThinkingSeconds(totalMs: number): number {
  return Math.max(0, Math.round(totalMs / 1000))
}

export function resolveAgentThinkingTrace(input: AgentThinkingTraceInput): AgentThinkingTraceView {
  const steps = resolveAgentThinkingSteps(input.steps, input.reasoning, input.live)
  const startedAt = toTimestamp(input.startedAt) ?? Date.now()
  const completedAt = toTimestamp(input.completedAt)
  const now = Date.now()
  const elapsedMs = input.live
    ? now - startedAt
    : (completedAt ?? now) - startedAt
  const activeStep = input.live
    ? steps.find((step) => step.status === 'active') ?? steps.at(-1) ?? null
    : null
  const showOrganizing = input.live && (
    !steps.length
    || activeStep?.kind === 'organize'
    || /整理中/.test(activeStep?.label ?? '')
  )

  return {
    steps,
    elapsedSeconds: formatAgentThinkingSeconds(elapsedMs),
    totalSeconds: formatAgentThinkingSeconds(
      completedAt != null ? completedAt - startedAt : elapsedMs
    ),
    activeStep,
    showOrganizing,
    operationCount: input.live
      ? steps.filter((step) => step.status === 'done').length
      : steps.length
  }
}

export function buildFlowAgentPreflightStep(skillName: string): AgentThinkingStep {
  const label = String(skillName || '').trim() || '创作'
  return {
    id: 'flow-preflight-skill',
    kind: 'read_skill',
    label: `正在读取 ${label} 技能`,
    status: 'active'
  }
}
