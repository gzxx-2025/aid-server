'use client'

export interface AgentResponseStoppedDividerProps {
  label?: string
  className?: string
}

export function AgentResponseStoppedDivider({
  label = '你已停止本次回复',
  className = ''
}: AgentResponseStoppedDividerProps) {
  return (
    <div
      className={`agent-response-stopped-divider${className ? ` ${className}` : ''}`}
      role="status"
      aria-label={label}
    >
      <span className="agent-response-stopped-divider__label">{label}</span>
    </div>
  )
}

export default AgentResponseStoppedDivider
