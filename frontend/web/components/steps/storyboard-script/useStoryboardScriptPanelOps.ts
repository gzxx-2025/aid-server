'use client'

import type { MutableRefObject } from 'react'
import { Modal, message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import {
  STORYBOARD_WORKBENCH_NEED_PROJECT_MSG,
  useStoryboardWorkbenchMutations
} from '~/composables/useStoryboardWorkbenchMutations'
import {
  extractStoryboardTitleSuffix,
  formatStoryboardScriptTitle
} from '~/utils/storyboardPanelTitle'
import { mapStoryboardListRowToPanel } from '~/utils/storyboardPanelMap'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import { openImageGalleryPreviewModal } from '~/utils/openImageGalleryPreviewModal'
import { resolveStoryboardReferenceImageTitle } from '~/utils/storyboardReferenceImages'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { removeStoryboardStepPanelsByIds } from '~/utils/storyboardPanelState'
import {
  getPanelReferencePreviewImages,
  pickStoryboardCoverImage,
  storyboardApiErr,
  type StoryboardPanel
} from './storyboardScriptShared'
import { createStoryboardScriptScrollOps } from './storyboardScriptScrollOps'
import { createStoryboardScriptReorderOps } from './storyboardScriptReorderOps'
import { useStoryboardScriptPanelUiState } from './useStoryboardScriptPanelUiState'

interface StoryboardScriptModalSavePayload {
  title: string
  content: string
}

export function useStoryboardScriptPanelOps(opts: {
  panelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  pageDisposedRef: MutableRefObject<boolean>
  stepRootRef: MutableRefObject<HTMLDivElement | null>
  listRef: MutableRefObject<HTMLDivElement | null>
  bottomAddBarRef: MutableRefObject<HTMLDivElement | null>
  currentScriptPanelIndexRef: MutableRefObject<number>
  setShowStoryboardScriptModal: (v: boolean) => void
  clearEditScriptTooltipStateRef: MutableRefObject<() => void>
}) {
  const {
    panelsRef,
    onChangeRef,
    pageDisposedRef,
    stepRootRef,
    listRef,
    bottomAddBarRef,
    currentScriptPanelIndexRef,
    setShowStoryboardScriptModal,
    clearEditScriptTooltipStateRef
  } = opts

  const wb = useStoryboardWorkbenchMutations()
  const getStore = () => useCreationStore.getState()
  const { onShotListDragChange } =
    createStoryboardScriptReorderOps(panelsRef, onChangeRef, wb)

  const {
    isShotDragging,
    editingId,
    setEditingId,
    editingTitle,
    setEditingTitle,
    editingTitleRef,
    batchDeleteSubmitting,
    setBatchDeleteSubmitting,
    batchDeleteSubmittingRef,
    activeInsertSlot,
    onShotDragStart,
    onShotDragEnd,
    onInsertSlotEnter,
    onInsertSlotLeave,
    clearInsertSlotImmediate
  } = useStoryboardScriptPanelUiState()
  const { scrollToLatestPanel, scrollToPanelIndex } = createStoryboardScriptScrollOps({
    stepRootRef,
    listRef,
    bottomAddBarRef
  })

  async function insertBlankPanelAt(atIndex: number) {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }

    const defaultTitle = formatStoryboardScriptTitle(atIndex, '未命名')
    let newPanel: StoryboardPanel

    try {
      const data = await wb.createRemote(defaultTitle)
      if (!data) throw new Error('no data')
      newPanel = {
        id: String(data.id),
        title: (data.title && data.title.trim()) || defaultTitle
      }
      getStore().addManualStoryboard(data.id)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return
    }

    const next = [...panelsRef.current]
    next.splice(atIndex, 0, newPanel)
    onChangeRef.current(next)
    if (next.every((p) => wb.parseServerStoryboardId(p.id) != null)) {
      try {
        await wb.sortRemoteToMatchPanels(next)
      } catch (e: unknown) {
        message.warning(storyboardApiErr(e))
      }
    }
    clearInsertSlotImmediate()
    message.success('已插入空白分镜')
    scrollToLatestPanel()
  }

  const addPanel = async () => {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }

    const nextIndex = panelsRef.current.length
    const defaultTitle = formatStoryboardScriptTitle(nextIndex, '未命名')
    let newPanel: StoryboardPanel

    try {
      const data = await wb.createRemote(defaultTitle)
      if (!data) throw new Error('no data')
      newPanel = {
        id: String(data.id),
        title: (data.title && data.title.trim()) || defaultTitle
      }
      getStore().addManualStoryboard(data.id)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return
    }

    onChangeRef.current([...panelsRef.current, newPanel])
    scrollToLatestPanel()
  }

  const startEditTitle = (panel: StoryboardPanel) => {
    setEditingId(panel.id)
    setEditingTitle(panel.title)
  }

  const finishEditTitle = async (panel: StoryboardPanel) => {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      setEditingId(null)
      return
    }
    const sid = wb.parseServerStoryboardId(panel.id)
    if (sid == null) {
      message.warning('该分镜未同步到服务器，无法保存标题，请刷新分镜列表后重试')
      setEditingId(null)
      return
    }

    const nextTitleRaw = editingTitleRef.current.trim() || panel.title
    const idx = panelsRef.current.findIndex((p) => p.id === panel.id)
    const nextTitle =
      idx >= 0
        ? formatStoryboardScriptTitle(idx, extractStoryboardTitleSuffix(nextTitleRaw))
        : nextTitleRaw
    const nextPanels = panelsRef.current.map((item) =>
      item.id === panel.id ? { ...item, title: nextTitle } : item
    )
    onChangeRef.current(nextPanels)
    setEditingId(null)

    try {
      await wb.saveRemote({ id: sid, title: nextTitle })
    } catch (e: unknown) {
      message.warning(`标题同步失败：${storyboardApiErr(e)}`)
    }
  }

  const cancelEditTitle = () => {
    setEditingId(null)
    setEditingTitle('')
  }

  // 复制分镜（与场景/角色/道具复制逻辑一致）
  const handleCopyPanel = async (index: number) => {
    const panel = panelsRef.current[index]
    if (!panel) return

    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }
    const sourceStoryboardId = wb.parseServerStoryboardId(panel.id)
    if (sourceStoryboardId == null) {
      message.warning('该分镜未同步到服务器，无法复制，请刷新分镜列表后重试')
      return
    }

    const nextIndex = panelsRef.current.length
    const suffix = extractStoryboardTitleSuffix(panel.title) || `${panel.title}_副本`
    const newTitle = formatStoryboardScriptTitle(nextIndex, suffix)

    try {
      const data = await wb.createRemote(newTitle, sourceStoryboardId)
      if (!data) throw new Error('no data')
      const newPanel = mapStoryboardListRowToPanel(data, nextIndex)
      getStore().addManualStoryboard(data.id)
      onChangeRef.current([...panelsRef.current, newPanel])
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return
    }

    message.success('分镜已复制')
  }

  // 删除分镜（与场景/角色/道具删除逻辑一致：确认后删除）
  const removePanel = (idx: number) => {
    Modal.confirm({
      title: '确认删除分镜?',
      content: '将同时删除该分镜的分镜图、脚本内容及对应的分镜视频、音画同步结果。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const panel = panelsRef.current[idx]
        if (!panel) return

        const ctx = await wb.getProjectEpisodeContext()
        if (!ctx) {
          message.error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
          throw new Error('no project context')
        }
        const sid = wb.parseServerStoryboardId(panel.id)
        if (sid == null) {
          message.error('该分镜未同步到服务器，无法删除，请刷新分镜列表后重试')
          throw new Error('no server storyboard id')
        }

        try {
          await wb.deleteRemote(panel.id)
          getStore().removeManualStoryboard(sid)
        } catch (e: unknown) {
          message.error(storyboardApiErr(e))
          throw e
        }

        const nextPanels = panelsRef.current.filter((_, i) => i !== idx)
        onChangeRef.current(nextPanels)
        message.success('分镜已删除')
      }
    })
  }

  async function handleBatchDeleteStoryboardPanels(selectedStoryboardIds: number[]) {
    const ids = [...new Set(selectedStoryboardIds.filter((id) => Number.isFinite(id) && id > 0))]
    if (!ids.length || batchDeleteSubmittingRef.current) return
    setBatchDeleteSubmitting(true)
    try {
      const ctx = await wb.getProjectEpisodeContext()
      if (!ctx) throw new Error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      await wb.deleteRemoteBatch(ids.map(String))
      const next = removeStoryboardStepPanelsByIds(ids)
      onChangeRef.current(next.script)
      message.success(`已删除 ${ids.length} 个分镜内容`)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      throw e
    } finally {
      setBatchDeleteSubmitting(false)
    }
  }

  /* ---------- 脚本弹窗保存 / 编辑分镜图回写 ---------- */

  const handleSaveStoryboardScript = async (payload: StoryboardScriptModalSavePayload) => {
    const idx = currentScriptPanelIndexRef.current
    if (idx < 0 || idx >= panelsRef.current.length) return

    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }
    const sid = wb.parseServerStoryboardId(panelsRef.current[idx]!.id)
    if (sid == null) {
      message.warning('该分镜未同步到服务器，无法保存脚本，请刷新分镜列表后重试')
      return
    }

    const nextTitle = payload.title?.trim() || panelsRef.current[idx]!.title || '未命名'
    const nextContent = payload.content ?? ''
    const nextPanels = panelsRef.current.map((panel, i) =>
      i === idx ? { ...panel, title: nextTitle, scriptContent: nextContent } : panel
    )
    onChangeRef.current(nextPanels)
    setShowStoryboardScriptModal(false)
    clearEditScriptTooltipStateRef.current()

    const updated = nextPanels[idx]!
    try {
      const storyScript = wb.scriptHtmlToStoryScriptApi(nextContent)
      await wb.saveRemote({
        id: sid,
        title: nextTitle,
        ...(storyScript !== undefined ? { storyScript } : {}),
        ...(updated.dialogueText != null && String(updated.dialogueText).trim()
          ? { dialogueText: String(updated.dialogueText).trim() }
          : {})
      })
    } catch (e: unknown) {
      message.warning(`脚本同步失败：${storyboardApiErr(e)}`)
    }
    message.success('分镜脚本已保存')
  }

  const handleStoryboardUpdate = async (sceneIndex: number, data: any) => {
    if (sceneIndex < 0 || sceneIndex >= panelsRef.current.length) return
    const prevPanel = panelsRef.current[sceneIndex]
    const nextPanels = panelsRef.current.map((panel, i) => {
      if (i !== sceneIndex) return panel
      const next: StoryboardPanel = { ...panel }
      if (Array.isArray(data?.images)) {
        next.images = data.images.map((img: any) => ({ ...img }))
        const cover = pickStoryboardCoverImage(next.images)
        const coverUrl = String(cover?.url || cover?.thumbnail || '').trim()
        next.finalImageUrl = coverUrl || undefined
      }
      if (data?.scriptContent !== undefined) {
        next.scriptContent = data.scriptContent
      }
      if (data?.title !== undefined && String(data.title).trim()) {
        next.title = String(data.title).trim()
      }
      return next
    })
    const nextPanel = nextPanels[sceneIndex]
    const panelUnchanged =
      !!prevPanel &&
      !!nextPanel &&
      String(prevPanel.title ?? '') === String(nextPanel.title ?? '') &&
      String(prevPanel.scriptContent ?? '') === String(nextPanel.scriptContent ?? '') &&
      String(prevPanel.finalImageUrl ?? '') === String(nextPanel.finalImageUrl ?? '') &&
      JSON.stringify(prevPanel.images ?? []) === JSON.stringify(nextPanel.images ?? [])
    if (!panelUnchanged) {
      onChangeRef.current(nextPanels)
    }

    const updated = nextPanels[sceneIndex]!
    const needSave =
      data?.scriptContent !== undefined ||
      (data?.title !== undefined && String(data.title).trim().length > 0)
    if (!needSave) return

    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }
    const sid = wb.parseServerStoryboardId(updated.id)
    if (sid == null) {
      message.warning('该分镜未同步到服务器，无法保存，请刷新分镜列表后重试')
      return
    }

    try {
      const storyScript =
        data?.scriptContent !== undefined
          ? wb.scriptHtmlToStoryScriptApi(String(data.scriptContent))
          : undefined
      await wb.saveRemote({
        id: sid,
        ...(data?.title !== undefined && String(data.title).trim()
          ? { title: String(data.title).trim() }
          : { title: updated.title }),
        ...(storyScript !== undefined ? { storyScript } : {}),
        ...(updated.dialogueText != null && String(updated.dialogueText).trim()
          ? { dialogueText: String(updated.dialogueText).trim() }
          : {})
      })
    } catch (e: unknown) {
      message.warning(`分镜同步失败：${storyboardApiErr(e)}`)
    }
  }

  /* ---------- 分镜图：预览、下载、删除（与素材准备一致） ---------- */

  const handlePreviewStoryboardImage = (panelIndex: number, imageIndex: number) => {
    const panel = panelsRef.current[panelIndex]
    const img = panel?.images?.[imageIndex]
    if (img?.url) {
      openImagePreviewModal({
        url: img.url,
        title: img.title || `分镜图${imageIndex + 1}`
      })
    } else {
      message.warning('暂无图片可预览')
    }
  }

  const handleDownloadStoryboardImage = (panelIndex: number, imageIndex: number) => {
    const panel = panelsRef.current[panelIndex]
    const img = panel?.images?.[imageIndex]
    if (img?.url) {
      void downloadMediaFileWithToast(
        img.url,
        img.title || `分镜图${panelIndex + 1}-${imageIndex + 1}.png`,
        '图片'
      )
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeleteStoryboardImage = (panelIndex: number, imageIndex: number) => {
    const panel = panelsRef.current[panelIndex]
    if (!panel?.images?.length || imageIndex < 0 || imageIndex >= panel.images.length) return
    const nextImages = panel.images.filter((_, i) => i !== imageIndex)
    const nextPanels = panelsRef.current.map((p, i) =>
      i === panelIndex ? { ...p, images: nextImages } : p
    )
    onChangeRef.current(nextPanels)
    message.success('分镜图已删除')
  }

  function handlePreviewReferenceImages(panel: StoryboardPanel) {
    const images = getPanelReferencePreviewImages(panel)
    if (!images.length) {
      message.warning('暂无参考图可预览')
      return
    }
    openImageGalleryPreviewModal(
      images.map((img) => ({
        url: String(img.url || ''),
        title: resolveStoryboardReferenceImageTitle(img)
      }))
    )
  }

  void pageDisposedRef

  return {
    wb,
    isShotDragging,
    onShotDragStart,
    onShotDragEnd,
    onShotListDragChange,
    activeInsertSlot,
    onInsertSlotEnter,
    onInsertSlotLeave,
    clearInsertSlotImmediate,
    insertBlankPanelAt,
    scrollToLatestPanel,
    scrollToPanelIndex,
    addPanel,
    editingId,
    editingTitle,
    setEditingTitle,
    startEditTitle,
    finishEditTitle,
    cancelEditTitle,
    handleCopyPanel,
    removePanel,
    batchDeleteSubmitting,
    handleBatchDeleteStoryboardPanels,
    handleSaveStoryboardScript,
    handleStoryboardUpdate,
    handlePreviewStoryboardImage,
    handleDownloadStoryboardImage,
    handleDeleteStoryboardImage,
    handlePreviewReferenceImages
  }
}
