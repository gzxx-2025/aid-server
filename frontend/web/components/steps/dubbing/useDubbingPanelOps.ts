'use client'

import { Modal,message } from 'antd'
import { useState,type MutableRefObject } from 'react'
import {
createDefaultDubbingPanel,
createDefaultVideoPanel
} from '~/composables/useCreateFlowStoryboardSync'
import {
STORYBOARD_WORKBENCH_NEED_PROJECT_MSG,
useStoryboardWorkbenchMutations
} from '~/composables/useStoryboardWorkbenchMutations'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel,StoryboardPanel,StoryboardVideoPanel } from '~/types'
import { moveItemBeforeIndex } from '~/utils/moveItemBeforeIndex'
import { mapStoryboardListRowToPanel } from '~/utils/storyboardPanelMap'
import { removeStoryboardStepPanelsByIds } from '~/utils/storyboardPanelState'
import {
extractStoryboardTitleSuffix,
formatStoryboardDubbingTitle,
formatStoryboardScriptTitle,
formatStoryboardVideoTitle
} from '~/utils/storyboardPanelTitle'
import { setDubbingStepFormPanels } from './dubbingViewShared'

export function storyboardApiErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

/**
 * Dubbing 步骤的分镜行操作（原 Dubbing.vue script 内标题编辑 / 拖拽排序 /
 * 批量删除 / 复制 / 删除 / 弹窗回写部分原样搬迁）。
 */
export function useDubbingPanelOps(opts: {
  panelsRef: MutableRefObject<DubbingPanel[]>
  onChangeRef: MutableRefObject<(next: DubbingPanel[]) => void>
  onStoryboardVideoPanelsChange: (value: StoryboardVideoPanel[]) => void
  dubbingEditSceneIndexRef: MutableRefObject<number>
}) {
  const {
    panelsRef,
    onChangeRef,
    onStoryboardVideoPanelsChange,
    dubbingEditSceneIndexRef
  } = opts
  const wb = useStoryboardWorkbenchMutations()

  const [batchDeleteSubmitting, setBatchDeleteSubmitting] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editingTitle, setEditingTitle] = useState('')

  async function onDubbingListDragChange(from: number, to: number) {
    const insertBeforeIndex = from < to ? to + 1 : to
    await applyDubbingStepReorder(from, insertBeforeIndex)
  }

  async function applyDubbingStepReorder(from: number, insertBefore: number) {
    const state = useCreationStore.getState()
    const s = [...(state.formData.storyboardScript.panels as StoryboardPanel[])]
    const v = [...(state.formData.storyboardVideo.panels as StoryboardVideoPanel[])]
    const d = [...(state.formData.dubbing.panels as DubbingPanel[])]
    if (d.length <= 1) return
    const nextS = moveItemBeforeIndex(s, from, insertBefore)
    const nextV = v.length === nextS.length ? moveItemBeforeIndex(v, from, insertBefore) : v
    const nextD = moveItemBeforeIndex(d, from, insertBefore)
    setDubbingStepFormPanels({ script: nextS, video: nextV, dubbing: nextD })
    onChangeRef.current(nextD)
    if (nextS.length > 0 && nextS.every((p) => wb.parseServerStoryboardId(p.id) != null)) {
      try {
        await wb.sortRemoteToMatchPanels(nextS)
      } catch (err: unknown) {
        message.warning(`排序同步失败：${storyboardApiErr(err)}`)
      }
    }
  }

  async function handleBatchDeleteDubbingPanels(selectedStoryboardIds: number[]) {
    const ids = [...new Set(selectedStoryboardIds.filter((id) => Number.isFinite(id) && id > 0))]
    if (!ids.length || batchDeleteSubmitting) return
    setBatchDeleteSubmitting(true)
    try {
      const ctx = await wb.getProjectEpisodeContext()
      if (!ctx) throw new Error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      await wb.deleteRemoteBatch(ids.map(String))
      const next = removeStoryboardStepPanelsByIds(ids)
      onChangeRef.current(next.dubbing)
      message.success(`已删除 ${ids.length} 个分镜内容`)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      throw e
    } finally {
      setBatchDeleteSubmitting(false)
    }
  }

  function onDubbingPanelsSave(next: DubbingPanel[]) {
    const cloned = next.map((p) => ({ ...p }))
    // 弹窗内大量 update:panels 仅用于本地 UI（生成历史 / 对口型状态），须先回写本地
    onChangeRef.current(cloned)
    setDubbingStepFormPanels({ dubbing: cloned })

    const idx = dubbingEditSceneIndexRef.current
    if (idx < 0 || idx >= cloned.length) return

    const scripts = [
      ...(useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[])
    ]
    const sp = scripts[idx]
    if (!sp) return

    const prevDialogue = sp.dialogueText != null ? String(sp.dialogueText).trim() : ''
    const dialogue = cloned[idx]?.dialogue != null ? String(cloned[idx]!.dialogue).trim() : ''
    // 台词未变：不调 /api/user/storyboard/update（打开弹窗同步 compose 历史时会连发多次 panels）
    if (dialogue === prevDialogue) return

    const sid = wb.parseServerStoryboardId(sp.id)
    if (sid == null) {
      message.warning('该分镜未同步到服务器，无法保存配音信息，请刷新分镜列表后重试')
      return
    }

    scripts[idx] = {
      ...sp,
      dialogueText: dialogue || null,
      // 用户改过台词后，服务端清洗字幕需等下次 list 刷新；先清空以免预览字幕过期
      subtitleText: null
    }
    if (cloned[idx]) {
      cloned[idx] = { ...cloned[idx]!, subtitleText: null }
    }
    setDubbingStepFormPanels({ script: scripts, dubbing: cloned })
    onChangeRef.current(cloned)

    void (async () => {
      try {
        await wb.saveRemote({
          id: sid,
          title: scripts[idx]!.title,
          ...(dialogue ? { dialogueText: dialogue } : {})
        })
      } catch (e: unknown) {
        message.warning(`配音信息同步失败：${storyboardApiErr(e)}`)
      }
    })()
  }

  const startEditTitle = (panel: DubbingPanel) => {
    setEditingId(panel.id)
    setEditingTitle(panel.title)
  }

  const finishEditTitle = async (panel: DubbingPanel) => {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      setEditingId(null)
      return
    }

    const idx = panelsRef.current.findIndex((p) => p.id === panel.id)
    const nextTitleRaw = editingTitle.trim() || panel.title
    const nextTitle =
      idx >= 0
        ? formatStoryboardDubbingTitle(idx, extractStoryboardTitleSuffix(nextTitleRaw))
        : nextTitleRaw
    const nextPanels = panelsRef.current.map((item) =>
      item.id === panel.id ? { ...item, title: nextTitle } : item
    )
    onChangeRef.current(nextPanels)
    setEditingId(null)

    if (idx < 0) return
    const scriptList = [
      ...(useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[])
    ]
    const sp = scriptList[idx]
    const sid = sp ? wb.parseServerStoryboardId(sp.id) : null
    if (!sp || sid == null) {
      message.warning('该分镜未同步到服务器，无法保存标题，请刷新分镜列表后重试')
      return
    }
    scriptList[idx] = {
      ...sp,
      title: formatStoryboardScriptTitle(idx, extractStoryboardTitleSuffix(nextTitle))
    }
    setDubbingStepFormPanels({ script: scriptList })
    try {
      await wb.saveRemote({ id: sid, title: scriptList[idx]!.title })
    } catch (e: unknown) {
      message.warning(`标题同步失败：${storyboardApiErr(e)}`)
    }
  }

  const cancelEditTitle = () => {
    setEditingId(null)
    setEditingTitle('')
  }

  const handleCopyPanel = async (index: number) => {
    const ctx = await wb.getProjectEpisodeContext()
    if (!ctx) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }
    const state = useCreationStore.getState()
    const scripts = [...(state.formData.storyboardScript.panels as StoryboardPanel[])]
    const srcScript = scripts[index]
    const srcSid = srcScript ? wb.parseServerStoryboardId(srcScript.id) : null
    if (!srcScript || srcSid == null) {
      message.warning('该分镜未同步到服务器，无法复制，请刷新分镜列表后重试')
      return
    }

    const panel = panelsRef.current[index]
    if (!panel) return
    const nextIndex = panelsRef.current.length
    const suffix = extractStoryboardTitleSuffix(panel.title) || `${panel.title}_副本`
    let newScript: StoryboardPanel
    try {
      const newScriptTitle = formatStoryboardScriptTitle(
        nextIndex,
        extractStoryboardTitleSuffix(srcScript.title) || '未命名'
      )
      const data = await wb.createRemote(newScriptTitle, srcSid)
      if (!data) throw new Error('no data')
      newScript = mapStoryboardListRowToPanel(data, nextIndex)
    } catch (e: unknown) {
      message.error(storyboardApiErr(e))
      return
    }

    const videos = [
      ...(useCreationStore.getState().formData.storyboardVideo.panels as StoryboardVideoPanel[])
    ]
    const srcVideo = videos[index]
    const newVideo: StoryboardVideoPanel = {
      ...createDefaultVideoPanel(newScript, nextIndex),
      ...(srcVideo
        ? {
            title: formatStoryboardVideoTitle(nextIndex, extractStoryboardTitleSuffix(srcVideo.title)),
            videoMode: srcVideo.videoMode,
            detailDescription: srcVideo.detailDescription
          }
        : {})
    }

    const newPanel: DubbingPanel = createDefaultDubbingPanel(newScript, nextIndex)
    newPanel.id = `dubbing-${newScript.id}-${nextIndex}`
    newPanel.dialogue = newScript.dialogueText || ''
    newPanel.subtitleText = newScript.subtitleText
    newPanel.dubbingType = panel.dubbingType
    newPanel.speakerRole = panel.speakerRole
    newPanel.status = 'pending'
    if (suffix && suffix !== `${panel.title}_副本`) {
      newPanel.title = formatStoryboardDubbingTitle(nextIndex, suffix)
    }
    const nextScripts = [...scripts, newScript]
    const nextVideos = [...videos, newVideo]
    const nextDubbings = [...panelsRef.current, newPanel]
    setDubbingStepFormPanels({ script: nextScripts, video: nextVideos })
    onStoryboardVideoPanelsChange(nextVideos)
    onChangeRef.current(nextDubbings)
    message.success('分镜已复制')
  }

  const removePanel = (idx: number) => {
    Modal.confirm({
      title: '确认删除分镜?',
      content: '将同时删除该条音画同步结果及对应的分镜脚本、分镜视频。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const ctx = await wb.getProjectEpisodeContext()
        if (!ctx) {
          message.error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
          throw new Error('no project context')
        }
        const state = useCreationStore.getState()
        const scripts = [...(state.formData.storyboardScript.panels as StoryboardPanel[])]
        const sp = scripts[idx]
        const sid = sp ? wb.parseServerStoryboardId(sp.id) : null
        if (!sp || sid == null) {
          message.error('该分镜未同步到服务器，无法删除，请刷新分镜列表后重试')
          throw new Error('no server storyboard id')
        }
        try {
          await wb.deleteRemote(sp.id)
        } catch (e: unknown) {
          message.error(storyboardApiErr(e))
          throw e
        }

        const nextScripts = scripts.filter((_, i) => i !== idx)
        const nextVideos = (
          useCreationStore.getState().formData.storyboardVideo.panels as StoryboardVideoPanel[]
        ).filter((_, i) => i !== idx)
        const nextPanels = panelsRef.current.filter((_, i) => i !== idx)
        setDubbingStepFormPanels({ script: nextScripts, video: nextVideos })
        onStoryboardVideoPanelsChange(nextVideos)
        onChangeRef.current(nextPanels)
        message.success('分镜已删除')
      }
    })
  }

  return {
    batchDeleteSubmitting,
    editingId,
    editingTitle,
    setEditingTitle,
    startEditTitle,
    finishEditTitle,
    cancelEditTitle,
    onDubbingListDragChange,
    handleBatchDeleteDubbingPanels,
    handleCopyPanel,
    removePanel,
    onDubbingPanelsSave
  }
}
