'use client'

import type { ReactNode, Ref } from 'react'
import agentIconRaw from '~/assets/img/home/agent-icon.svg'
import foldIconRaw from '~/assets/img/icon/icon-fold.svg'
import AgentConversationViewport, {
  type AgentConversationViewportHandle
} from '@/components/common/agent-conversation/AgentConversationViewport'
import { assetUrl } from '@/utils/assetUrl'
import './agent-conversation-panel.css'

const agentIconUrl = assetUrl(agentIconRaw)
const foldIconUrl = assetUrl(foldIconRaw)

export interface AgentConversationPanelProps {
  open?: boolean
  title?: string
  onClose?: () => void
  closeAriaLabel?: string
  className?: string
  surfaceClassName?: string
  headerClassName?: string
  brandClassName?: string
  messagesClassName?: string
  composerClassName?: string
  conversationRef?: Ref<AgentConversationViewportHandle>
  updateToken: string | number
  scopeKey?: string | number
  initialLoading?: boolean
  hasOlder?: boolean
  loadingOlder?: boolean
  onLoadOlder?: () => Promise<unknown> | void
  statusText?: string
  contextSlot?: ReactNode
  emptySlot?: ReactNode
  children: ReactNode
  composer: ReactNode
}

export function AgentConversationPanel({
  open = true,
  title = '新对话',
  onClose,
  closeAriaLabel = '收起对话',
  className = '',
  surfaceClassName = 'agent-conversation-panel__surface',
  headerClassName = 'agent-conversation-panel__header',
  brandClassName = 'agent-conversation-panel__brand',
  messagesClassName = 'agent-conversation-panel__messages',
  composerClassName = 'agent-conversation-panel__composer',
  conversationRef,
  updateToken,
  scopeKey,
  initialLoading,
  hasOlder,
  loadingOlder,
  onLoadOlder,
  statusText,
  contextSlot,
  emptySlot,
  children,
  composer
}: AgentConversationPanelProps) {
  return (
    <div className={`agent-conversation-panel ${className}`.trim()}>
      <div className={surfaceClassName}>
        <header className={headerClassName}>
          <div className={brandClassName}>
            <img src={agentIconUrl} alt="" className="agent-conversation-panel__brand-icon" />
            <strong>{title}</strong>
          </div>
          {onClose ? (
            <button type="button" aria-label={closeAriaLabel} onClick={onClose}>
              <img src={foldIconUrl} alt="" width={20} height={20} />
            </button>
          ) : null}
        </header>
        {statusText ? (
          <span className="sr-only" aria-live="polite">{statusText}</span>
        ) : null}
        <AgentConversationViewport
          ref={conversationRef}
          open={open}
          className={messagesClassName}
          updateToken={updateToken}
          scopeKey={scopeKey}
          initialLoading={initialLoading}
          hasOlder={hasOlder}
          loadingOlder={loadingOlder}
          onLoadOlder={onLoadOlder}
        >
          {emptySlot}
          {children}
        </AgentConversationViewport>
        {contextSlot}
        <footer className={composerClassName}>{composer}</footer>
      </div>
    </div>
  )
}

export default AgentConversationPanel
