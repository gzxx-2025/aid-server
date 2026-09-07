'use client'

import { CloseOutlined, LoadingOutlined, PlayCircleOutlined } from '@ant-design/icons'
import type { StudioFlowAutoPipelineState } from '@/hooks/studio/useStudioFlowAutoPipeline'
import { studioFlowAutoPipelineStageTitle } from '@/utils/studio/studioFlowAutoPipelineRunner'

export function StudioFlowAutoPipelineBanner({
  state,
  onCancel,
  onDismiss,
  onExpand,
  onAbandon,
  onResume,
  onStart
}: {
  state: StudioFlowAutoPipelineState
  onCancel: () => void
  onDismiss: () => void
  onExpand?: () => void
  onAbandon?: () => void
  onResume?: () => void
  onStart?: () => void
}) {
  if (
    !state.running
    && !state.awaitingConfirmation
    && !state.progress
    && !state.errorMessage
    && !state.canResume
  ) return null

  if (state.awaitingConfirmation) {
    return (
      <article className="studio-flow-auto-pipeline is-awaiting-confirmation" role="status">
        <header>
          <strong>Agent 已准备好全流程创作</strong>
          <button type="button" className="studio-flow-auto-pipeline__icon-btn" aria-label="暂不开始" onClick={onDismiss}>
            <CloseOutlined />
          </button>
        </header>
        <p>剧本已经带入画布。点击开始后，我会保留已有节点和生成结果，只补齐尚未完成的创作步骤。</p>
        {onStart ? (
          <div className="studio-flow-auto-pipeline__footer">
            <button type="button" className="studio-flow-auto-pipeline__resume" onClick={onStart}>
              <PlayCircleOutlined />
              开始全流程自动创作
            </button>
          </div>
        ) : null}
      </article>
    )
  }

  const progress = state.progress
  const isDone = progress?.stage === 'done' && !state.running
  const resumeStage = state.failedStage
    ? studioFlowAutoPipelineStageTitle(state.failedStage)
    : ''
  const bodyMessage = progress?.message
    ?? state.errorMessage
    ?? (state.running ? '正在推进创作流程…' : '')

  if (state.dismissed && state.canResume && !state.running) {
    return (
      <div className="studio-flow-auto-pipeline studio-flow-auto-pipeline--compact" role="status">
        <div className="studio-flow-auto-pipeline__compact-copy">
          <strong>自动创作可继续</strong>
          <span>{resumeStage ? `从「${resumeStage}」接着跑` : '充值后可从中断处继续'}</span>
        </div>
        <div className="studio-flow-auto-pipeline__compact-actions">
          {onExpand ? (
            <button type="button" className="studio-flow-auto-pipeline__text-btn" onClick={onExpand}>
              详情
            </button>
          ) : null}
          {onResume ? (
            <button type="button" className="studio-flow-auto-pipeline__resume studio-flow-auto-pipeline__resume--compact" onClick={onResume}>
              <PlayCircleOutlined />
              继续
            </button>
          ) : null}
        </div>
      </div>
    )
  }

  return (
    <article
      className={[
        'studio-flow-auto-pipeline',
        state.running ? 'is-running' : '',
        isDone ? 'is-done' : '',
        state.canResume && !state.running ? 'is-interrupted' : ''
      ].filter(Boolean).join(' ')}
    >
      <header>
        {state.running ? <LoadingOutlined spin /> : null}
        <strong>{progress?.title ?? (state.canResume ? '自动创作已中断' : '自动创作')}</strong>
        {!state.running ? (
          <button type="button" className="studio-flow-auto-pipeline__icon-btn" aria-label="收起" title="收起后仍可从下方入口继续" onClick={onDismiss}>
            <CloseOutlined />
          </button>
        ) : (
          <button type="button" className="studio-flow-auto-pipeline__text-btn" onClick={onCancel}>取消</button>
        )}
      </header>
      {bodyMessage ? (
        <p className={state.errorMessage && !state.running && !progress?.message ? 'studio-flow-auto-pipeline__error' : undefined}>
          {bodyMessage}
        </p>
      ) : null}
      {resumeStage && state.canResume && !state.running ? (
        <p className="studio-flow-auto-pipeline__meta">下一步：{resumeStage}</p>
      ) : null}
      {typeof progress?.percent === 'number' ? (
        <div className="studio-flow-auto-pipeline__bar" aria-hidden>
          <span style={{ width: `${Math.max(0, Math.min(100, progress.percent))}%` }} />
        </div>
      ) : null}
      {state.canResume && !state.running && onResume ? (
        <div className="studio-flow-auto-pipeline__footer">
          <button type="button" className="studio-flow-auto-pipeline__resume" onClick={onResume}>
            <PlayCircleOutlined />
            从中断处继续
          </button>
          {onAbandon ? (
            <button type="button" className="studio-flow-auto-pipeline__text-btn" onClick={onAbandon}>
              放弃续跑
            </button>
          ) : null}
        </div>
      ) : null}
    </article>
  )
}
