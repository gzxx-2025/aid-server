interface CreateFlowStepLoadingProps {
  label?: string
}

export function CreateFlowStepLoading({
  label = '正在准备流程页面…'
}: CreateFlowStepLoadingProps) {
  return (
    <div
      className="create-step-swap-placeholder"
      role="status"
      aria-live="polite"
      aria-label={label}
    >
      <span className="create-step-swap-placeholder__spinner" aria-hidden="true" />
      <span className="create-step-swap-placeholder__text">{label}</span>
    </div>
  )
}
