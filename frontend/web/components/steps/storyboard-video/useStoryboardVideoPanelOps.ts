'use client'

import { useState, type MutableRefObject } from 'react'
import { Modal, message } from 'antd'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'
import { useCreationStore } from '~/stores/creation'
import {
  STORYBOARD_WORKBENCH_NEED_PROJECT_MSG,
  useStoryboardWorkbenchMutations
} from '~/composables/useStoryboardWorkbenchMutations'
import { createDefaultVideoPanel } from '~/composables/useCreateFlowStoryboardSync'
import { mapStoryboardListRowToPanel } from '~/utils/storyboardPanelMap'
import {
  extractStoryboardTitleSuffix,
  formatStoryboardScriptTitle,
  formatStoryboardVideoTitle
} from '~/utils/storyboardPanelTitle'
import { removeStoryboardStepPanelsByIds } from '~/utils/storyboardPanelState'
import { setStoryboardVideoStepFormPanels } from '~/utils/storyboardVideoBatchShared'
import { reorderStoryboardVideoPanels } from './storyboardVideoPanelReorderOps'
import { cancelStoryboardVideo, downloadStoryboardVideo, previewStoryboardVideo } from './storyboardVideoPanelMediaOps'
import { clearPanelVideoGenFailureIfMainVideoSet, isSameStoryboardVideoRecordList, scrollToLatestStoryboardVideoPanel } from './storyboardVideoPanelStateOps'

export function storyboardApiErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export function useStoryboardVideoPanelOps(opts: {
  panelsRef: MutableRefObject<StoryboardVideoPanel[]>
  scriptPanelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(next: StoryboardVideoPanel[]) => void>
  resolvePanelStoryboardId: (index: number) => number | null
  /**
   * 用户主动删到 0 条后，禁止「脚本有数据 + 视频为空」的 watcher 立刻把列表从脚本补回，
   * 否则删最后一条会表现为删不掉。
   */
  suppressEmptyResyncFromScriptRef: MutableRefObject<boolean>
  rootRef: MutableRefObject<HTMLDivElement | null>
  listRef: MutableRefObject<HTMLDivElement | null>
  bottomAddBarRef: MutableRefObject<HTMLDivElement | null>
}) {
  const {
    panelsRef,
    scriptPanelsRef,
    onChangeRef,
    resolvePanelStoryboardId,
    suppressEmptyResyncFromScriptRef,
    rootRef,
    listRef,
    bottomAddBarRef
  } = opts
  const wb = useStoryboardWorkbenchMutations()

  const [batchDeleteSubmitting, setBatchDeleteSubmitting] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editingTitle, setEditingTitle] = useState('')

  async function onVideoListDragChange(from: number, to: number) {
    await reorderStoryboardVideoPanels({
      from,
      to,
      onChange: onChangeRef.current,
      workbench: wb
    })
  }

  async function handleBatchDeleteVideoPanels(selectedStoryboardIds: number[]) {
    const ids = [...new Set(selectedStoryboardIds.filter((id) => Number.isFinite(id) && id > 0))]
    if (!ids.length || batchDeleteSubmitting) return
    setBatchDeleteSubmitting(true)
    try {
      const ctx = await wb.getProjectEpisodeContext()
      if (!ctx) throw new Error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      await wb.deleteRemoteBatch(ids.map(String))
      const next = removeStoryboardStepPanelsByIds(ids)
      if (next.video.length === 0) suppressEmptyResyncFromScriptRef.current = true
      onChangeRef.current(next.video)
      message.success(`已删除 ${ids.length} 个分镜内容`)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      throw e
    } finally {
      setBatchDeleteSubmitting(false)
    }
  }

  function handlePreviewStoryboardVideo(panelIndex: number) {
    previewStoryboardVideo(panelsRef.current[panelIndex], panelIndex)
  }

  function handleDownloadStoryboardVideo(panelIndex: number) {
    downloadStoryboardVideo(panelsRef.current[panelIndex], panelIndex)
  }

  async function handleCancelStoryboardVideo(panelIndex: number) {
    await cancelStoryboardVideo({
      panelIndex,
      panelsRef,
      scriptPanelsRef,
      resolvePanelStoryboardId,
      onChange: onChangeRef.current,
      workbench: wb
    })
  }

  function handleVideoUpdate(sceneIndex: number, data: any) {
    if (sceneIndex < 0 || sceneIndex >= panelsRef.current.length) return
    if (Array.isArray(data?.videos)) {
      clearPanelVideoGenFailureIfMainVideoSet({
        sceneIndex,
        videos: data.videos,
        resolvePanelStoryboardId
      })
    }
    // 只有编辑了分镜脚本/标题等“分镜配置”时，才需要同步到服务器；
    // 打开弹窗/刷新生成记录/设置为分镜视频只会更新 videos 列表，不应触发保存。
    const shouldSave =
      data?.scriptContent !== undefined ||
      (data?.scriptTitle !== undefined && String(data.scriptTitle).trim().length > 0)
    const scriptList = useCreationStore.getState().formData.storyboardScript
      .panels as StoryboardPanel[]
    if (
      (data?.scriptContent !== undefined || data?.scriptTitle !== undefined) &&
      Array.isArray(scriptList) &&
      sceneIndex < scriptList.length
    ) {
      setStoryboardVideoStepFormPanels({
        script: scriptList.map((p, i) =>
          i === sceneIndex
            ? {
                ...p,
                ...(data.scriptContent !== undefined && { scriptContent: data.scriptContent }),
                ...(data.scriptTitle !== undefined &&
                  String(data.scriptTitle).trim() && { title: String(data.scriptTitle).trim() })
              }
            : p
        )
      })
    }
    const nextPanels = panelsRef.current.map((panel, i) =>
      i === sceneIndex
        ? {
            ...panel,
            ...(data.name != null && { title: data.name }),
            ...(Array.isArray(data.videos) && { videos: data.videos.map((v: any) => ({ ...v })) })
          }
        : panel
    )
    const prevPanel = panelsRef.current[sceneIndex]
    const nextPanel = nextPanels[sceneIndex]
    const panelUnchanged =
      !!prevPanel &&
      !!nextPanel &&
      String(prevPanel.title ?? '') === String(nextPanel.title ?? '') &&
      isSameStoryboardVideoRecordList(prevPanel.videos, nextPanel.videos)
    if (!panelUnchanged) {
      onChangeRef.current(nextPanels)
    }

    if (!shouldSave) return
    void (async () => {
      const ctx = await wb.getProjectEpisodeContext()
      if (!ctx) {
        message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
        return
      }
      const scriptAfter = useCreationStore.getState().formData.storyboardScript
        .panels as StoryboardPanel[]
      const sp = scriptAfter[sceneIndex]
      if (!sp || wb.parseServerStoryboardId(sp.id) == null) {
        message.warning('该分镜未同步到服务器，无法保存，请刷新分镜列表后重试')
        return
      }
      const body = wb.buildSavePayload(sp, sceneIndex)
      if (body) {
        try {
          await wb.saveRemote(body)
        } catch (e: unknown) {
          message.warning(`分镜同步失败：${storyboardApiErr(e)}`)
        }
      }
    })()
  }

  async function insertBlankPanelAt(atIndex: number): Promise<boolean> {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return false
    }

    const scriptList = [
      ...(useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[])
    ]
    const defaultTitle = formatStoryboardScriptTitle(atIndex, '未命名')
    let newScript: StoryboardPanel
    try {
      const data = await wb.createRemote(defaultTitle)
      if (!data) throw new Error('no data')
      newScript = {
        id: String(data.id),
        title: (data.title && data.title.trim()) || defaultTitle
      }
      useCreationStore.getState().addManualStoryboard(data.id)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return false
    }

    scriptList.splice(atIndex, 0, newScript)
    setStoryboardVideoStepFormPanels({ script: scriptList })

    const newPanel: StoryboardVideoPanel = createDefaultVideoPanel(newScript, atIndex)
    newPanel.id = `video-${newScript.id}-${atIndex}`
    const next = [...panelsRef.current]
    next.splice(atIndex, 0, newPanel)
    suppressEmptyResyncFromScriptRef.current = false
    onChangeRef.current(next)

    if (scriptList.every((p) => wb.parseServerStoryboardId(p.id) != null)) {
      try {
        await wb.sortRemoteToMatchPanels(scriptList)
      } catch (e: unknown) {
        message.warning(storyboardApiErr(e))
      }
    }

    message.success('已插入空白分镜')
    return true
  }

  const addPanel = async () => {
    suppressEmptyResyncFromScriptRef.current = false

    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }

    const scriptList = [
      ...(useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[])
    ]
    const nextIndex = scriptList.length
    const defaultTitle = formatStoryboardScriptTitle(nextIndex, '未命名')

    let newScript: StoryboardPanel
    try {
      const data = await wb.createRemote(defaultTitle)
      if (!data) throw new Error('no data')
      newScript = {
        id: String(data.id),
        title: (data.title && data.title.trim()) || defaultTitle
      }
      useCreationStore.getState().addManualStoryboard(data.id)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return
    }

    setStoryboardVideoStepFormPanels({ script: [...scriptList, newScript] })
    scrollToLatestPanel()
    message.success('已添加分镜')
  }

  const scrollToLatestPanel = (behavior: ScrollBehavior = 'smooth') => {
    scrollToLatestStoryboardVideoPanel({ rootRef, listRef, bottomAddBarRef, behavior })
  }

  const startEditTitle = (panel: StoryboardVideoPanel) => {
    setEditingId(panel.id)
    setEditingTitle(panel.title)
  }

  const finishEditTitle = async (panel: StoryboardVideoPanel, editingTitleNow: string) => {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      setEditingId(null)
      return
    }

    const vIdxEarly = panelsRef.current.findIndex((p) => p.id === panel.id)
    if (vIdxEarly >= 0) {
      const sp0 = (
        useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[]
      )[vIdxEarly]
      if (!sp0 || wb.parseServerStoryboardId(sp0.id) == null) {
        message.warning('该分镜未同步到服务器，无法保存标题，请刷新分镜列表后重试')
        setEditingId(null)
        return
      }
    }

    const nextTitleRaw = editingTitleNow.trim() || panel.title
    const nextTitle =
      vIdxEarly >= 0
        ? formatStoryboardVideoTitle(vIdxEarly, extractStoryboardTitleSuffix(nextTitleRaw))
        : nextTitleRaw
    const nextPanels = panelsRef.current.map((item) =>
      item.id === panel.id ? { ...item, title: nextTitle } : item
    )
    onChangeRef.current(nextPanels)
    setEditingId(null)

    const vIdx = nextPanels.findIndex((p) => p.id === panel.id)
    if (vIdx < 0) return
    const scriptList = [
      ...(useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[])
    ]
    if (vIdx < scriptList.length) {
      const scriptTitle = formatStoryboardScriptTitle(vIdx, extractStoryboardTitleSuffix(nextTitle))
      const sp = { ...scriptList[vIdx], title: scriptTitle }
      scriptList[vIdx] = sp
      setStoryboardVideoStepFormPanels({ script: scriptList })
      const sid = wb.parseServerStoryboardId(sp.id)
      if (sid) {
        try {
          await wb.saveRemote({ id: sid, title: scriptTitle })
        } catch (e: unknown) {
          message.warning(`标题同步失败：${storyboardApiErr(e)}`)
        }
      }
    }
  }

  const cancelEditTitle = () => {
    setEditingId(null)
    setEditingTitle('')
  }

  const handleCopyPanel = async (index: number) => {
    const ctxFirst = await wb.getProjectEpisodeContext()
    if (!ctxFirst) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }
    const scripts = [
      ...(useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[])
    ]
    const srcScript = scripts[index]
    const sourceStoryboardId = srcScript ? wb.parseServerStoryboardId(srcScript.id) : null
    if (!srcScript || sourceStoryboardId == null) {
      message.warning('该分镜未同步到服务器，无法复制，请刷新分镜列表后重试')
      return
    }

    const panel = panelsRef.current[index]
    if (!panel) return
    const nextIndex = panelsRef.current.length
    const suffix = extractStoryboardTitleSuffix(panel.title) || `${panel.title}_副本`
    const newScriptTitle = formatStoryboardScriptTitle(
      nextIndex,
      extractStoryboardTitleSuffix(srcScript.title) || `${srcScript.title || '分镜'}_副本`
    )

    // 必须先 create 脚本再写 video：仅加长 video 会被「脚本有落库 id」的联动 watcher 立刻回滚
    let newScript: StoryboardPanel
    try {
      const data = await wb.createRemote(newScriptTitle, sourceStoryboardId)
      if (!data) throw new Error('no data')
      newScript = mapStoryboardListRowToPanel(data, nextIndex)
      useCreationStore.getState().addManualStoryboard(data.id)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return
    }

    const newPanel: StoryboardVideoPanel = {
      ...createDefaultVideoPanel(newScript, nextIndex),
      title: formatStoryboardVideoTitle(nextIndex, suffix),
      videoMode: panel.videoMode,
      detailDescription: panel.detailDescription
    }

    const nextScripts = [...scripts, newScript]
    const nextVideos = [...panelsRef.current, newPanel]
    suppressEmptyResyncFromScriptRef.current = false
    // 先写齐 script + video，避免脚本联动按旧 video 重建出空行后再被回滚
    setStoryboardVideoStepFormPanels({ script: nextScripts, video: nextVideos })
    onChangeRef.current(nextVideos)

    message.success('分镜已复制')
    scrollToLatestPanel()
  }

  const removePanel = (idx: number) => {
    Modal.confirm({
      title: '确认删除分镜?',
      content: '将同时删除该分镜的视频内容及对应的分镜脚本、音画同步结果。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const ctx = await wb.getProjectEpisodeContext()
        if (!ctx) {
          message.error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
          throw new Error('no project context')
        }
        const scriptList = useCreationStore.getState().formData.storyboardScript
          .panels as StoryboardPanel[]
        const sp = scriptList[idx]
        if (!sp || wb.parseServerStoryboardId(sp.id) == null) {
          message.error('该分镜未同步到服务器，无法删除，请刷新分镜列表后重试')
          throw new Error('no server storyboard id')
        }
        try {
          await wb.deleteRemote(sp.id)
          useCreationStore.getState().removeManualStoryboard(Number(sp.id))
        } catch (e: unknown) {
          message.error(storyboardApiErr(e))
          throw e
        }
        const nextPanels = panelsRef.current.filter((_, i) => i !== idx)
        if (nextPanels.length === 0) {
          suppressEmptyResyncFromScriptRef.current = true
        }
        onChangeRef.current(nextPanels)
        message.success('分镜已删除')
      }
    })
  }

  return {
    wb,
    batchDeleteSubmitting,
    editingId,
    editingTitle,
    setEditingTitle,
    onVideoListDragChange,
    handleBatchDeleteVideoPanels,
    handlePreviewStoryboardVideo,
    handleDownloadStoryboardVideo,
    handleCancelStoryboardVideo,
    handleVideoUpdate,
    insertBlankPanelAt,
    addPanel,
    startEditTitle,
    finishEditTitle,
    cancelEditTitle,
    handleCopyPanel,
    removePanel
  }
}
