'use client'

/**
 * StoryboardScript 步骤共享类型与纯展示助手
 * （原 StoryboardScript.vue script 内模块级 / 无状态函数拆分）。
 */

import { looksLikeHtmlFragment, scriptApiTextToEditorHtml } from '~/utils/htmlPlain'
import { sanitizeDisplayHtml } from '~/utils/safeDisplayHtml'
import {
  pickStoryboardCoverImage,
  resolveStoryboardPanelCoverImage
} from '~/utils/storyboardImageCover'
import {
  getPreviewableStoryboardReferenceImages,
  resolveStoryboardReferenceImageTitle
} from '~/utils/storyboardReferenceImages'
import { resolveStoryboardListDisplayTitle } from '~/utils/storyboardPanelTitle'
import type { StoryboardPanel } from '~/types'
import type { BillingQuoteRequest } from '~/types/business-api'

export type { StoryboardPanel }

export function storyboardApiErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export function displayPanelTitle(panel: StoryboardPanel, index: number): string {
  return resolveStoryboardListDisplayTitle(panel.title, index, 'script')
}

export function getPanelCoverImage(panel: StoryboardPanel) {
  return resolveStoryboardPanelCoverImage(panel)
}

export function getPanelCoverImageIndex(panel: StoryboardPanel): number {
  const cover = getPanelCoverImage(panel)
  if (!cover?.id || !panel.images?.length) return -1
  return panel.images.findIndex((im) => String(im?.id ?? '') === String(cover.id))
}

export function getPanelReferencePreviewImages(panel: StoryboardPanel) {
  return getPreviewableStoryboardReferenceImages(panel.referenceImages)
}

export function getPanelReferencePreviewTitle(panel: StoryboardPanel) {
  const first = getPanelReferencePreviewImages(panel)[0]
  return first ? resolveStoryboardReferenceImageTitle(first) : '参考图'
}

export function renderStoryboardScriptContent(content?: string): string {
  const raw = (content ?? '').trim()
  if (!raw) return ''
  const html = looksLikeHtmlFragment(raw) ? raw : scriptApiTextToEditorHtml(raw)
  return sanitizeDisplayHtml(html)
}

export { pickStoryboardCoverImage, resolveStoryboardReferenceImageTitle }

/** 失败项（部分失败 banner 下的失败卡片） */
export interface StoryboardFailedPanelItem {
  id: string
  title: string
  message: string
}

/** 卡片/列表视图共享回调契约 */
export interface StoryboardScriptViewSharedProps {
  panels: StoryboardPanel[]
  isProMode: boolean
  editingId: string | null
  editingTitle: string
  onEditingTitleChange: (v: string) => void
  onStartEditTitle: (panel: StoryboardPanel) => void
  onFinishEditTitle: (panel: StoryboardPanel) => void
  onCancelEditTitle: () => void
  isPanelImageGenerating: (panel: StoryboardPanel) => boolean
  onOpenStoryboardScriptModal: (index: number) => void
  onOpenStoryboardImage: (index: number) => void
  onCopyPanel: (index: number) => void
  onRemovePanel: (index: number) => void
  onJumpToVideoWithModal: (index: number) => void
  onPreviewStoryboardImage: (panelIndex: number, imageIndex: number) => void
  onDownloadStoryboardImage: (panelIndex: number, imageIndex: number) => void
  onDeleteStoryboardImage: (panelIndex: number, imageIndex: number) => void
  /* 部分失败提示区 */
  showStoryboardPartialBanner: boolean
  generationError: string | null
  canResumePartialFailed: boolean
  isResumingPartialFailed: boolean
  resumeBillingRequest: BillingQuoteRequest | null
  onResumePartialFailed: () => void
  failedPanels: StoryboardFailedPanelItem[]
}
