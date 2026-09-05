'use client'

import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  CheckOutlined,
  CloseOutlined,
  CommentOutlined,
  CopyOutlined,
  DownOutlined,
  FileTextOutlined,
  ReloadOutlined,
  UpOutlined
} from '@ant-design/icons'
import { message, Tooltip } from 'antd'
import agentIconRaw from '~/assets/img/home/agent-icon.svg'
import AgentConversationPanel from '~/components/common/agent-conversation/AgentConversationPanel'
import { ComposerSendButton } from '~/components/common/composer-send/ComposerSendButton'
import { type AgentConversationViewportHandle } from '~/components/common/agent-conversation/AgentConversationViewport'
import AgentThinkingProcess from '~/components/common/agent-conversation/AgentThinkingProcess'
import AgentResponseStoppedDivider from '~/components/common/agent-conversation/AgentResponseStoppedDivider'
import StoryScriptAgentSkillPicker from '~/components/steps/story-script-agent/StoryScriptAgentSkillPicker'
import StoryScriptAgentInputRequestCard from '~/components/steps/story-script-agent/StoryScriptAgentInputRequestCard'
import type { StoryScriptAgentViewMessage } from '~/hooks/useStoryScriptAgent'
import type {
  UserSkillDefinition,
  UserSkillInputAnswer,
  UserSkillInputRequest
} from '~/types/user-skill'
import { assetUrl } from '~/utils/assetUrl'
import AgentPausedRuntimeNotice from '~/components/common/agent-conversation/AgentPausedRuntimeNotice'
import { buildFlowAgentPreflightStep } from '~/utils/agentThinkingSteps'
import { resolveStoryScriptAgentDocumentView } from '~/utils/storyScriptAgentDocumentView'
import { resolveStoryScriptAgentMessagePresentation } from '~/utils/storyScriptAgentMessagePresentation'
import {
  normalizeStoryScriptAgentReferences,
  storyScriptAgentResponseForDisplay
} from '~/utils/storyScriptAgentReference'
import {
  formatEditorSelectionLocation,
  type EditorTextSelection
} from '~/utils/quill/editorTextSelection'
import './story-script-agent.css'
import '@/components/common/composer-flow.css'

const agentIconUrl = assetUrl(agentIconRaw)

interface StoryScriptAgentPanelProps {
  open: boolean
  onClose: () => void
  skills: UserSkillDefinition[]
  selectedSkillCode: string
  onSkillChange: (skillCode: string) => void
  skillsLoading: boolean
  skillsError?: string
  onSkillsRequest?: () => void
  messages: StoryScriptAgentViewMessage[]
  messagesLoading?: boolean
  conversationScopeKey: string
  hasOlderMessages?: boolean
  olderMessagesLoading?: boolean
  onLoadOlderMessages?: () => Promise<unknown> | void
  sending: boolean
  paused: boolean
  statusText: string
  lastError: string
  canRetry: boolean
  canStop: boolean
  stopping: boolean
  onRetry: () => void
  onSend: (prompt: string, references?: EditorTextSelection[]) => boolean
  onSubmitInputRequest: (
    inputRequest: UserSkillInputRequest,
    answers: UserSkillInputAnswer[]
  ) => boolean | Promise<boolean>
  onStop: () => void
  onPauseReceiving: () => void
  onResumeReceiving: () => void
  references: EditorTextSelection[]
  onReferencesChange: (references: EditorTextSelection[]) => void
  onApplyScript: (content: string, references?: EditorTextSelection[]) => void
  emptyHint?: string
  contextSlot?: ReactNode
  composerContextSlot?: ReactNode
  runtimeFeedbackEnabled?: boolean
}

function AgentReferenceQuotes({ references }: { references: EditorTextSelection[] }) {
  return (
    <div className="story-agent-reference-quotes">
      {references.map((reference, index) => (
        <div
          key={`${reference.index}:${reference.length}:${reference.text}`}
          className="story-agent-reference-quote"
        >
          <span>
            批注 {index + 1} · {formatEditorSelectionLocation(reference)}
          </span>
          <p>{reference.text}</p>
        </div>
      ))}
    </div>
  )
}

function assistantThinkingState(
  item: StoryScriptAgentViewMessage,
  paused: boolean,
  displayContent: string,
  skillLabel: string
) {
  const hasActiveThinking = Boolean(
    !item.thinkingCompletedAt && item.thinkingSteps?.some((step) => step.status === 'active')
  )
  const hasLiveReasoning = Boolean(
    !item.thinkingCompletedAt && String(item.reasoning || '').trim()
  )
  const thinkingLive = item.status === 'streaming' && !paused && !item.thinkingCompletedAt && (
    !displayContent.trim() || hasActiveThinking || hasLiveReasoning
  )
  const fallbackStep = !item.thinkingSteps?.length && (thinkingLive || item.thinkingStartedAt)
    ? [{
        ...buildFlowAgentPreflightStep(skillLabel),
        status: thinkingLive ? 'active' as const : 'done' as const
      }]
    : undefined
  const thinkingSteps = item.thinkingSteps?.length ? item.thinkingSteps : fallbackStep
  return { thinkingLive, thinkingSteps }
}

function AgentChatMessage({
  item,
  paused,
  statusText
}: {
  item: StoryScriptAgentViewMessage
  paused: boolean
  statusText: string
}) {
  const display = storyScriptAgentResponseForDisplay(item.content, item.references)
  const displayContent = display.text
  const hasContent = Boolean(displayContent.trim()) && display.placeholder !== true
  async function copyContent() {
    if (!displayContent) return
    try {
      await navigator.clipboard.writeText(displayContent)
      message.success('内容已复制')
    } catch {
      message.error('复制失败')
    }
  }

  if (!hasContent && item.status !== 'error' && item.status !== 'stopped') {
    if (item.status === 'streaming' && !paused) return null
    if (item.status === 'streaming' && paused) {
      return (
        <article className="story-agent-assistant-message is-streaming is-paused">
          <div className="story-agent-assistant-message__bubble is-placeholder">
            <p>{statusText || '已暂停'}</p>
            <span className="story-agent-assistant-message__paused-label">已暂停</span>
          </div>
        </article>
      )
    }
  }

  return (
    <article
      className={`story-agent-assistant-message${item.status === 'streaming' ? ' is-streaming' : ''}${
        item.status === 'error' ? ' is-error' : ''
      }${paused ? ' is-paused' : ''}`}
    >
      {hasContent || item.status === 'error' ? (
        <div className={`story-agent-assistant-message__bubble${display.placeholder ? ' is-placeholder' : ''}`}>
          <p aria-live={item.status === 'streaming' ? 'polite' : undefined}>
            {hasContent ? displayContent : displayContent || '暂无内容'}
          </p>
          {item.status === 'streaming' && !paused ? (
            <span className="story-agent-assistant-message__stream-dot" aria-label="生成中" />
          ) : paused ? (
            <span className="story-agent-assistant-message__paused-label">已暂停</span>
          ) : null}
        </div>
      ) : null}
      {hasContent && item.status !== 'streaming' && item.status !== 'error' ? (
        <div className="story-agent-assistant-message__toolbar">
          <Tooltip title="复制">
            <button type="button" aria-label="复制" onClick={() => void copyContent()}>
              <CopyOutlined />
            </button>
          </Tooltip>
        </div>
      ) : null}
      {item.status === 'stopped' ? <AgentResponseStoppedDivider /> : null}
    </article>
  )
}

function AgentDocumentCard({
  item,
  expanded,
  paused,
  statusText,
  onToggle,
  onApply
}: {
  item: StoryScriptAgentViewMessage
  expanded: boolean
  paused: boolean
  statusText: string
  onToggle: () => void
  onApply: () => void
}) {
  const display = storyScriptAgentResponseForDisplay(item.content, item.references)
  const displayContent = display.text
  const stoppedPartialTrusted = item.status !== 'stopped' || item.partialOutputTrusted === true
  const view = resolveStoryScriptAgentDocumentView({
    status: item.status,
    content: displayContent,
    contentIsPlaceholder: display.placeholder,
    actionsAllowed: display.applicable && stoppedPartialTrusted,
    statusText: item.status === 'streaming' ? statusText : undefined,
    responseMode: item.responseMode === 'DIAGNOSTIC' ? 'DIAGNOSTIC' : 'SCREENPLAY'
  })
  async function copyContent() {
    if (!displayContent) return
    try {
      await navigator.clipboard.writeText(displayContent)
      message.success('剧本内容已复制')
    } catch {
      message.error('复制失败')
    }
  }

  return (
    <article
      className={`story-agent-document${item.status === 'streaming' ? ' is-streaming' : ''}${
        item.status === 'error' ? ' is-error' : ''
      }${paused ? ' is-paused' : ''}`}
    >
      <header className="story-agent-document__header">
        <span className="story-agent-document__icon" aria-hidden="true">
          <FileTextOutlined />
        </span>
        <span className="story-agent-document__identity">
          <strong>{view.title}</strong>
          <small>{view.subtitle}</small>
        </span>
        {item.status === 'streaming' && !paused ? (
          <span className="story-agent-document__stream-dot" aria-label="生成中" />
        ) : paused ? (
          <span className="story-agent-document__paused-label">已暂停</span>
        ) : null}
      </header>

      {view.showBody ? (
        <div
          className={`story-agent-document__content${expanded ? ' is-expanded' : ''}${
            view.bodyIsPlaceholder ? ' is-placeholder' : ''
          }`}
          aria-live={item.status === 'streaming' ? 'polite' : undefined}
        >
          {view.bodyText}
        </div>
      ) : null}

      {view.showActions ? (
        <footer className="story-agent-document__actions">
          <Tooltip title={expanded ? '收起全文' : '展开全文'}>
            <button
              type="button"
              aria-label={expanded ? '收起全文' : '展开全文'}
              aria-expanded={expanded}
              onClick={onToggle}
            >
              {expanded ? <UpOutlined /> : <DownOutlined />}
            </button>
          </Tooltip>
          <Tooltip title="复制剧本">
            <button type="button" aria-label="复制剧本" onClick={() => void copyContent()}>
              <CopyOutlined />
            </button>
          </Tooltip>
          {(item.responseMode ?? 'SCREENPLAY') === 'SCREENPLAY' ? (
            <button
              type="button"
              className="story-agent-document__apply"
              onClick={onApply}
            >
              <CheckOutlined aria-hidden="true" />
              <span>
                {item.references?.length
                  ? item.references.length > 1
                    ? `应用 ${item.references.length} 个批注`
                    : '替换选中段落'
                  : '带入当前剧本'}
              </span>
            </button>
          ) : null}
        </footer>
      ) : null}
      {item.status === 'stopped' ? <AgentResponseStoppedDivider /> : null}
    </article>
  )
}

function StoryScriptAgentAssistantTurn({
  item,
  expanded,
  paused,
  statusText,
  skillLabel,
  collapsibleThinking,
  presentation,
  onToggle,
  onApply
}: {
  item: StoryScriptAgentViewMessage
  expanded: boolean
  paused: boolean
  statusText: string
  skillLabel: string
  collapsibleThinking: boolean
  presentation: 'chat' | 'document'
  onToggle: () => void
  onApply: () => void
}) {
  const display = storyScriptAgentResponseForDisplay(item.content, item.references)
  const { thinkingLive, thinkingSteps } = assistantThinkingState(
    item,
    paused,
    display.text,
    skillLabel
  )
  const showThinking = thinkingLive
    || Boolean(thinkingSteps?.length)
    || Boolean(item.thinkingStartedAt)
    || Boolean(String(item.reasoning || '').trim())
  // Keep the document card mounted for screenplay/diagnostic turns, including empty streaming shells.
  const showDocument = presentation === 'document'

  return (
    <div className="story-agent-assistant-turn">
      {showThinking ? (
        <AgentThinkingProcess
          steps={thinkingSteps}
          reasoning={item.reasoning}
          live={thinkingLive}
          startedAt={item.thinkingStartedAt}
          completedAt={item.thinkingCompletedAt}
          collapsibleLive={collapsibleThinking}
        />
      ) : null}
      {presentation === 'chat' ? (
        <AgentChatMessage
          item={item}
          paused={paused}
          statusText={statusText}
        />
      ) : null}
      {showDocument ? (
        <AgentDocumentCard
          item={item}
          expanded={expanded}
          paused={paused}
          statusText={statusText}
          onToggle={onToggle}
          onApply={onApply}
        />
      ) : null}
    </div>
  )
}

export function StoryScriptAgentPanel({
  open,
  onClose,
  skills,
  selectedSkillCode,
  onSkillChange,
  skillsLoading,
  skillsError = '',
  onSkillsRequest,
  messages,
  messagesLoading = false,
  conversationScopeKey,
  hasOlderMessages = false,
  olderMessagesLoading = false,
  onLoadOlderMessages,
  sending,
  paused,
  statusText,
  lastError,
  canRetry,
  canStop,
  stopping,
  onRetry,
  onStop,
  onPauseReceiving,
  onResumeReceiving,
  onSend,
  onSubmitInputRequest,
  references,
  onReferencesChange,
  onApplyScript,
  emptyHint = '结合当前项目风格生成剧本，完成后可直接带入左侧编辑器。',
  contextSlot,
  composerContextSlot,
  runtimeFeedbackEnabled = false
}: StoryScriptAgentPanelProps) {
  const [draft, setDraft] = useState('')
  const [expandedIds, setExpandedIds] = useState<ReadonlySet<string>>(new Set())
  const [composerFlowing, setComposerFlowing] = useState(false)
  const conversationRef = useRef<AgentConversationViewportHandle | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const latestMessage = messages[messages.length - 1]
  const activeResponseMode = [...messages]
    .reverse()
    .find((item) => item.role === 'assistant' && item.status === 'streaming')?.responseMode
  const hasPendingInput = messages.some((item) => Boolean(item.inputRequest))
  const messageUpdateToken = latestMessage
    ? `${messages.length}:${latestMessage.id}:${latestMessage.content.length}:${latestMessage.status}:${latestMessage.thinkingCompletedAt ?? ''}`
    : `empty:${messagesLoading}`
  const showErrorNotice = Boolean(
    lastError &&
      !messages.some((item) => item.status === 'error' && item.content.trim() === lastError.trim())
  )

  useEffect(() => {
    if (!open) return undefined
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      const activeElement = document.activeElement
      if (
        activeElement instanceof Element &&
        activeElement.closest('.skill-picker-trigger, .skill-picker-popover')
      ) {
        return
      }
      onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose, open])

  useEffect(() => {
    if (!open || references.length === 0) return
    window.requestAnimationFrame(() => textareaRef.current?.focus())
  }, [open, references.length])

  const draftHasContent = Boolean(draft.trim())
  const showResumeButton = paused && (!sending || runtimeFeedbackEnabled)
  const selectedSkill = skills.find((skill) => skill.skillCode === selectedSkillCode)
  const selectedSkillLabel = selectedSkill?.name || selectedSkill?.skillCode || '创作'
  const softPauseEnabled = runtimeFeedbackEnabled && !hasPendingInput
  const pauseAction = softPauseEnabled ? onPauseReceiving : onStop
  const pauseTooltip = hasPendingInput
    ? '取消本次任务'
    : softPauseEnabled
      ? '暂停接收（任务继续在后台处理）'
      : activeResponseMode === 'DIAGNOSTIC'
        ? '停止诊断'
        : '停止生成'
  const pauseAriaLabel = hasPendingInput
    ? '取消本次任务'
    : softPauseEnabled
      ? '暂停接收'
      : activeResponseMode === 'DIAGNOSTIC'
        ? '停止诊断'
        : '停止生成'

  function submit() {
    const value = draft.trim()
    if (!value || sending) return
    if (!onSend(value, references)) return
    setDraft('')
    onReferencesChange([])
    conversationRef.current?.scrollToLatest('smooth')
  }

  function toggleExpanded(id: string) {
    setExpandedIds((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <aside
      className={`story-script-agent-panel${open ? ' is-open' : ''}`}
      aria-label="新对话"
      aria-hidden={!open}
      inert={!open}
    >
      <AgentConversationPanel
        open={open}
        title="新对话"
        onClose={onClose}
        statusText={statusText}
        conversationRef={conversationRef}
        updateToken={messageUpdateToken}
        scopeKey={conversationScopeKey}
        initialLoading={messagesLoading}
        hasOlder={hasOlderMessages}
        loadingOlder={olderMessagesLoading}
        onLoadOlder={onLoadOlderMessages}
        surfaceClassName="story-script-agent-panel__surface"
        headerClassName="story-script-agent-panel__header"
        brandClassName="story-script-agent-panel__brand"
        messagesClassName="story-script-agent-panel__messages"
        composerClassName="story-script-agent-panel__composer"
        emptySlot={
          !messagesLoading && messages.length === 0 ? (
            <div className="story-agent-empty">
              <img src={agentIconUrl} alt="" width={28} height={28} />
              <strong>从一个创作要求开始</strong>
              <p className="min-w-0 max-w-full break-words">{emptyHint}</p>
            </div>
          ) : null
        }
        contextSlot={contextSlot}
        composer={
          <div
            className={`story-script-agent-panel__input-wrap composer-flow composer-flow--dialog${
              composerFlowing ? ' is-flowing' : ''
            }${references.length ? ' has-references' : ''}${composerContextSlot ? ' has-context-chips' : ''}`}
            onMouseEnter={() => setComposerFlowing(true)}
            onMouseLeave={() => setComposerFlowing(false)}
          >
            {composerContextSlot}
            {references.length ? (
              <div className="story-script-agent-panel__references">
                <div className="story-script-agent-panel__references-head">
                  <span>
                    <CommentOutlined />
                    已添加 {references.length} 个剧本批注
                  </span>
                  <button
                    type="button"
                    disabled={sending}
                    onClick={() => onReferencesChange([])}
                  >
                    清空
                  </button>
                </div>
                <div className="story-script-agent-panel__reference-list">
                  {references.map((reference, index) => (
                    <div
                      key={`${reference.index}:${reference.length}:${reference.text}`}
                      className="story-script-agent-panel__reference"
                    >
                      <div>
                        <span>
                          批注 {index + 1} · {formatEditorSelectionLocation(reference)}
                        </span>
                      </div>
                      <p>{reference.text}</p>
                      <button
                        type="button"
                        aria-label={`移除批注 ${index + 1}`}
                        disabled={sending}
                        onClick={() =>
                          onReferencesChange(
                            references.filter((candidate) => candidate !== reference)
                          )
                        }
                      >
                        <CloseOutlined />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
            <textarea
              ref={textareaRef}
              value={draft}
              rows={3}
              maxLength={100_000}
              placeholder={references.length ? '输入针对这些选段的统一修改要求' : '输入内容'}
              disabled={sending}
              onFocus={() => setComposerFlowing(true)}
              onBlur={() => setComposerFlowing(false)}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing) return
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault()
                  submit()
                }
              }}
            />
            <div className="story-script-agent-panel__composer-tools">
              <StoryScriptAgentSkillPicker
                skills={skills}
                selectedSkillCode={selectedSkillCode}
                loading={skillsLoading}
                error={skillsError}
                disabled={sending || canRetry || paused}
                onSelect={onSkillChange}
                onRequestLoad={onSkillsRequest}
              />
              <div className="story-script-agent-panel__composer-actions">
                <ComposerSendButton
                  hasContent={draftHasContent}
                  mode="agent"
                  generating={sending && canStop && !(runtimeFeedbackEnabled && paused)}
                  pauseDisabled={stopping || (sending && !canStop)}
                  showResume={showResumeButton}
                  pauseTooltip={pauseTooltip}
                  pauseAriaLabel={pauseAriaLabel}
                  pauseBusyTooltip={softPauseEnabled ? '正在暂停…' : '正在停止…'}
                  resumeTooltip="恢复生成"
                  resumeAriaLabel="恢复生成"
                  disabled={!selectedSkillCode || stopping}
                  className="story-script-agent-panel__send"
                  onSend={submit}
                  onPause={pauseAction}
                  onResume={onResumeReceiving}
                />
              </div>
            </div>
          </div>
        }
      >
        {messagesLoading ? (
          <div className="story-agent-loading" role="status">
            <span />
            <span />
            <span />
            正在读取对话…
          </div>
        ) : null}
        {messages.map((item, index) => {
          if (item.role === 'user') {
            return (
              <article key={item.id} className="story-agent-user-message">
                {item.references?.length ? (
                  <AgentReferenceQuotes references={normalizeStoryScriptAgentReferences(item.references)} />
                ) : null}
                <p>{item.content}</p>
              </article>
            )
          }
          if (item.inputRequest) {
            return (
              <StoryScriptAgentInputRequestCard
                key={`${item.id}:${item.inputRequest.requestId}`}
                inputRequest={item.inputRequest}
                savedResponse={item.pendingInputResponse}
                disabled={sending || stopping}
                onSubmit={onSubmitInputRequest}
              />
            )
          }
          const precedingUser = [...messages.slice(0, index)].reverse().find((row) => row.role === 'user')
          const presentation = resolveStoryScriptAgentMessagePresentation({
            content: item.content,
            responseMode: item.responseMode,
            status: item.status,
            userPrompt: precedingUser?.content
          })
          return (
            <StoryScriptAgentAssistantTurn
              key={item.id}
              item={item}
              expanded={expandedIds.has(item.id)}
              paused={paused && item.status === 'streaming'}
              statusText={statusText}
              skillLabel={selectedSkillLabel}
              collapsibleThinking={runtimeFeedbackEnabled}
              presentation={presentation}
              onToggle={() => toggleExpanded(item.id)}
              onApply={() => onApplyScript(item.content, item.references)}
            />
          )
        })}
        {runtimeFeedbackEnabled && paused ? (
          <AgentPausedRuntimeNotice onResume={onResumeReceiving} disabled={stopping} />
        ) : null}
        {showErrorNotice ? (
          <div className="story-agent-error-notice" role="alert">
            {lastError}
          </div>
        ) : null}
        {lastError && canRetry ? (
          <button type="button" className="story-agent-retry" onClick={onRetry}>
            <ReloadOutlined />
            恢复上次生成
          </button>
        ) : null}
      </AgentConversationPanel>
    </aside>
  )
}

export default StoryScriptAgentPanel
