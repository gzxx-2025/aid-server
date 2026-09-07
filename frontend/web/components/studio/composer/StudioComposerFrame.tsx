'use client'

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { CompressOutlined, ExpandAltOutlined } from '@ant-design/icons'
import { Tooltip } from 'antd'
import { useBillingQuote } from '@/hooks/useBillingQuote'
import { useStudioUiStore } from '@/stores/studioUi'
import type { BillingQuoteRequest } from '@/types/business-api'
import type { StudioCanvasNode } from '@/types/studio'
import { billingQuoteCreditsForDisplay } from '@/utils/billingQuoteDisplay'
import { estimateStudioCredits } from '@/utils/studio/studioCreditEstimate'
import { StudioCreditSendButton } from './StudioCreditSendButton'

/** 流程节点的统一输入框；引用关系由七步业务弹窗管理，不在画布内另建一套资产关系。 */
export function StudioComposerFrame({
  node,
  topLeft,
  topRight,
  bottomLeft,
  placeholder = '描述你想要生成的内容',
  showCredits,
  showSend = true,
  readOnly = false,
  onSubmit,
  submitDisabledReason,
  resolveBillingRequest
}: {
  node: StudioCanvasNode
  topLeft?: ReactNode
  topRight?: ReactNode
  bottomLeft?: ReactNode
  placeholder?: string
  showCredits?: boolean
  showSend?: boolean
  readOnly?: boolean
  onSubmit?: (prompt: string) => unknown | Promise<unknown>
  submitDisabledReason?: string | null
  resolveBillingRequest?: (prompt: string) => BillingQuoteRequest | null
}) {
  const [draft, setDraft] = useState(() => ({ nodeId: node.id, source: node.data.prompt, value: node.data.prompt }))
  const [submitting, setSubmitting] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const submittingRef = useRef(false)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const task = useStudioUiStore((state) => state.tasks.find((item) =>
    item.nodeId === node.id && (item.status === 'running' || item.status === 'queued')
  ))
  const promptDraft = draft.nodeId === node.id && draft.source === node.data.prompt
    ? draft.value
    : node.data.prompt
  const billingRequest = resolveBillingRequest && promptDraft.trim()
    ? resolveBillingRequest(promptDraft.trim())
    : null
  const billingQuote = useBillingQuote(billingRequest)
  const quotedCredits = billingQuoteCreditsForDisplay(billingQuote.quote)
  const authoritative = quotedCredits != null
  const localCredits = estimateStudioCredits({
    costCredits: node.data.modelCostCredits,
    mediaKind: node.data.mediaKind,
    count: node.data.generationOptions?.count,
    durationSeconds: node.data.generationOptions?.duration
  })
  const credits = authoritative ? quotedCredits : localCredits
  const isFree = authoritative
    ? Boolean(billingQuote.quote?.isFree || quotedCredits === 0)
    : Boolean(node.data.modelIsFree)

  useEffect(() => {
    setExpanded(false)
  }, [node.id])

  const commitPrompt = useCallback((value = promptDraft) => {
    if (value !== node.data.prompt) updateNodeData(node.id, { prompt: value })
  }, [node.data.prompt, node.id, promptDraft, updateNodeData])

  const submit = async () => {
    const prompt = promptDraft.trim()
    if (!prompt || submittingRef.current || !onSubmit || submitDisabledReason) return
    commitPrompt(prompt)
    submittingRef.current = true
    setSubmitting(true)
    try {
      await onSubmit(prompt)
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <div className={`studio-composer${expanded ? ' is-expanded' : ''}`}>
      <div className="studio-composer__top">
        <div>{topLeft}</div>
        <div className="studio-composer__top-end">
          {topRight}
          <Tooltip title={expanded ? '还原输入框' : '放大输入框'}>
            <button type="button" className={`studio-composer__expand${expanded ? ' is-active' : ''}`} aria-label={expanded ? '还原输入框' : '放大输入框'} onClick={() => setExpanded((value) => !value)}>
              {expanded ? <CompressOutlined /> : <ExpandAltOutlined />}
            </button>
          </Tooltip>
        </div>
      </div>
      {!readOnly ? (
        <div className="studio-composer__prompt-wrap">
          <textarea
            value={promptDraft}
            rows={expanded ? 10 : 3}
            placeholder={placeholder}
            onChange={(event) => setDraft({ nodeId: node.id, source: node.data.prompt, value: event.target.value })}
            onBlur={() => commitPrompt()}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing) return
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                void submit().catch(() => undefined)
              }
            }}
          />
        </div>
      ) : null}
      <div className="studio-composer__footer">
        <div className="studio-composer__tools">{bottomLeft}</div>
        <div className="studio-composer__actions">
          {showSend ? (
            <StudioCreditSendButton
              credits={credits}
              isFree={isFree}
              showCredits={showCredits ?? Boolean(resolveBillingRequest)}
              hasContent={Boolean(promptDraft.trim())}
              mode="agent"
              loading={Boolean(task || submitting)}
              className="studio-composer__send"
              onSend={() => void submit().catch(() => undefined)}
              authoritative={authoritative}
              estimateLoading={Boolean(billingRequest && billingQuote.loading)}
              estimateError={billingQuote.error}
              disabled={Boolean(submitDisabledReason || !onSubmit)}
              disabledReason={submitDisabledReason || (!onSubmit ? '当前节点尚未接入提交接口' : '')}
            />
          ) : null}
        </div>
      </div>
    </div>
  )
}
