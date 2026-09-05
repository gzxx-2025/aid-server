'use client'

import { AgentThinkingProcess } from '~/components/common/agent-conversation/AgentThinkingProcess'
import type { AgentThinkingStep } from '@/utils/agentThinkingSteps'

export interface AgentThinkingPreviewProps {
  reasoning?: string
  steps?: AgentThinkingStep[]
  live: boolean
  startedAt?: string
  completedAt?: string
  className?: string
  /** @deprecated use AgentThinkingProcess labels via live prop */
  liveLabel?: string
  /** @deprecated use AgentThinkingProcess labels via live prop */
  detailsLabel?: string
}

export function AgentThinkingPreview({
  reasoning = '',
  steps,
  live,
  startedAt,
  completedAt,
  className = ''
}: AgentThinkingPreviewProps) {
  return (
    <AgentThinkingProcess
      reasoning={reasoning}
      steps={steps}
      live={live}
      startedAt={startedAt}
      completedAt={completedAt}
      className={className}
    />
  )
}

export default AgentThinkingPreview
