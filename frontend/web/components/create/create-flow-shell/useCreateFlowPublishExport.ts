'use client'

/**
 * 壳层「导出/发布」编排（原 CreateFlowShell.vue 导出与发布段拆分）：
 * 导出下拉、替换确认、成片自动保存本地、发布至案例广场链路。
 */

import { message,Modal } from 'antd'
import { useCallback,useEffect,useRef,useState } from 'react'
import { downloadExportedFinalVideo } from '~/composables/useEpisodeVideoExport'
import { useCreationStore } from '~/stores/creation'
import type { PreviewExportBridge } from '~/utils/createFlowInjection'
import {
isFullVideoExportStale,
isProjectPublished,
PUBLISH_STALE_EXPORT_TIP,
resolvePublishToCasePlazaBlockReason,
shouldConfirmReplacePublishedVideo
} from '~/utils/projectAudit'
import { syncFullVideoExportMediaToStore } from '~/utils/syncFullVideoExportMedia'
import {
retainPreviewExportBusyState,
type PreviewExportBusyState
} from '~/utils/reactUpdateGuards'

export interface CreateFlowPublishExportOptions {
  previewIsPublished: boolean
  getActiveProjectId: () => number | null
  handleSubmit: (opts?: {
    alsoPublish?: boolean
    coverUrl?: string
    projectDesc?: string
  }) => Promise<boolean>
}

export function useCreateFlowPublishExport(options: CreateFlowPublishExportOptions) {
  const currentFinalVideoUrl = useCreationStore((s) => s.currentFinalVideoUrl)
  const currentPendingVideoUrl = useCreationStore((s) => s.currentPendingVideoUrl)
  const currentExportStatus = useCreationStore((s) => s.currentExportStatus)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const currentEpisodeStatus = useCreationStore((s) => s.currentEpisodeStatus)
  const currentProjectStatus = useCreationStore((s) => s.currentProjectStatus)
  const currentProjectIsPublic = useCreationStore((s) => s.currentProjectIsPublic)
  const globalSettingDescription = useCreationStore(
    (s) => s.formData.globalSetting?.description || ''
  )

  const [exportMenuOpen, setExportMenuOpen] = useState(false)
  /** 已确认「新版替换旧版」的 pendingVideoUrl；换新片后需再确认 */
  const replacePublishedVideoAckedUrlRef = useRef('')
  /** 已确认「工程已改待重新导出」类替换提示；导出状态变化后重置 */
  const replaceStaleExportAckedRef = useRef(false)
  /**
   * 图二确认后若当前仍是「旧版待重导」，强制禁用发布直至本次重新导出完整视频成功。
   * 避免残留 pendingVideoUrl + 本地 status 未刷新时误开放发布入口。
   */
  const [publishBlockedUntilFreshExport, setPublishBlockedUntilFreshExport] = useState(false)
  const exportedEpisodeEditorIdRef = useRef<number | null>(null)

  const [saveExportedLocalLoading, setSaveExportedLocalLoading] = useState(false)
  const saveExportedLocalLoadingRef = useRef(false)
  /** 「发布至案例广场」链路执行中（更新项目 → 按需提审 → 发布；不再合成完整视频） */
  const [publishFlowRunning, setPublishFlowRunning] = useState(false)
  const publishFlowRunningRef = useRef(false)
  const [publishCasePlazaModalOpen, setPublishCasePlazaModalOpen] = useState(false)
  const publishCasePlazaModalOpenRef = useRef(false)

  /** 渲染仅订阅忙碌快照；桥接函数始终从 ref 取最新值。 */
  const [previewExportBusyState, setPreviewExportBusyState] =
    useState<PreviewExportBusyState>({ exporting: false, segmentsDownloading: false })
  const previewExportBridgeRef = useRef<PreviewExportBridge | null>(null)

  const optionsRef = useRef(options)
  optionsRef.current = options

  function getFullVideoExportState() {
    const s = useCreationStore.getState()
    return {
      finalVideoUrl: s.currentFinalVideoUrl,
      pendingVideoUrl: s.currentPendingVideoUrl,
      exportStatus: s.currentExportStatus
    }
  }

  const fullVideoExportState = {
    finalVideoUrl: currentFinalVideoUrl,
    pendingVideoUrl: currentPendingVideoUrl,
    exportStatus: currentExportStatus
  }

  /** 未导出 / 工程已改待重新导出：禁用并悬停提示 */
  const publishToCasePlazaTooltip = publishBlockedUntilFreshExport
    ? PUBLISH_STALE_EXPORT_TIP
    : resolvePublishToCasePlazaBlockReason(fullVideoExportState) || ''
  const publishToCasePlazaDisabled = Boolean(publishToCasePlazaTooltip)

  const getPublishTooltipPopupContainer = useCallback(() => document.body, [])

  function hasPublishedHistoryNow(): boolean {
    const s = useCreationStore.getState()
    if (optionsRef.current.previewIsPublished || isProjectPublished(s.currentProjectIsPublic)) {
      return true
    }
    const status =
      s.currentProjectType === 'series' ? s.currentEpisodeStatus : s.currentProjectStatus
    return status === 4
  }

  // 原 watch：导出状态/作品上下文变化后重置「工程已改」确认
  useEffect(() => {
    replaceStaleExportAckedRef.current = false
  }, [
    currentExportStatus,
    currentFinalVideoUrl,
    currentPendingVideoUrl,
    currentProjectId,
    currentEpisodeId
  ])

  const confirmReplacePublishedVideoIfNeeded = useCallback(async (): Promise<boolean> => {
    const pendingVideoUrl = String(
      useCreationStore.getState().currentPendingVideoUrl || ''
    ).trim()
    const exportState = getFullVideoExportState()
    if (
      !shouldConfirmReplacePublishedVideo({
        pendingVideoUrl,
        ackedPendingVideoUrl: replacePublishedVideoAckedUrlRef.current,
        finalVideoUrl: exportState.finalVideoUrl,
        exportStatus: exportState.exportStatus,
        hasPublishedHistory: hasPublishedHistoryNow(),
        staleExportAcked: replaceStaleExportAckedRef.current
      })
    ) {
      return true
    }
    const confirmingStale = isFullVideoExportStale(exportState)
    return new Promise((resolve) => {
      Modal.confirm({
        title: '提示',
        content: '您确定将发布的新版内容会替换旧版内容吗？',
        okText: '确定',
        cancelText: '取消',
        centered: true,
        onOk: () => {
          if (confirmingStale) {
            replaceStaleExportAckedRef.current = true
            // 改过内容后确认替换：必须先导出新版再发布
            setPublishBlockedUntilFreshExport(true)
          } else if (pendingVideoUrl) {
            replacePublishedVideoAckedUrlRef.current = pendingVideoUrl
          }
          resolve(true)
        },
        onCancel: () => resolve(false)
      })
    })
     
  }, [])

  const previewExportBusy =
    previewExportBusyState.exporting ||
    previewExportBusyState.segmentsDownloading ||
    publishFlowRunning ||
    saveExportedLocalLoading

  const previewExportBusyRef = useRef(previewExportBusy)
  previewExportBusyRef.current = previewExportBusy

  const onExportMenuOpenChange = useCallback(
    async (open: boolean) => {
      if (!open) {
        setExportMenuOpen(false)
        return
      }
      if (previewExportBusyRef.current) {
        setExportMenuOpen(false)
        return
      }
      // 打开前同步 exportStatus，避免本地仍是旧的「导出成功」态导致无法拦截发布
      try {
        await syncFullVideoExportMediaToStore(useCreationStore.getState())
      } catch {
        // 同步失败不阻断打开菜单，仍走本地已有状态判断
      }
      const ok = await confirmReplacePublishedVideoIfNeeded()
      setExportMenuOpen(ok)
    },
    [confirmReplacePublishedVideoIfNeeded]
  )

  const registerPreviewExportBridge = useCallback((bridge: PreviewExportBridge | null) => {
    previewExportBridgeRef.current = bridge
    setPreviewExportBusyState((current) => retainPreviewExportBusyState(current, bridge))
    if (!bridge) {
      setExportMenuOpen(false)
    }
  }, [])

  const saveExportedVideoToLocal = useCallback(async () => {
    if (saveExportedLocalLoadingRef.current) return
    const s = useCreationStore.getState()
    const projectId = Number(s.currentProjectId)
    const episodeId = s.currentProjectType === 'movie' ? 0 : Number(s.currentEpisodeId)
    const editorId = Number(exportedEpisodeEditorIdRef.current ?? s.currentEpisodeEditorId)
    const hasEditor = Number.isFinite(editorId) && editorId > 0
    const hasProject = Number.isFinite(projectId) && projectId > 0
    if (!hasEditor && !hasProject) {
      message.warning('暂无可保存的成片')
      return
    }
    saveExportedLocalLoadingRef.current = true
    setSaveExportedLocalLoading(true)
    const messageKey = 'export'
    try {
      // 下载接口会先完整读取附件流；在流真正返回前持续展示加载态，不能提前提示成功。
      message.loading({ content: '正在下载中...', key: messageKey, duration: 0 })
      await downloadExportedFinalVideo({
        episodeEditorId: hasEditor ? editorId : null,
        projectId: hasProject ? projectId : null,
        episodeId: Number.isFinite(episodeId) && episodeId >= 0 ? episodeId : 0
      })
      if (String(useCreationStore.getState().currentPendingVideoUrl || '').trim()) {
        message.warning({
          content: '下载成功；新片需重新提交审核（线上仍展示旧版）',
          key: messageKey,
          duration: 4
        })
      } else {
        message.success({ content: '下载成功', key: messageKey, duration: 2 })
      }
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error({
        content: err?.msg || err?.message || '成片下载失败',
        key: messageKey,
        duration: 4
      })
    } finally {
      saveExportedLocalLoadingRef.current = false
      setSaveExportedLocalLoading(false)
    }
  }, [])

  /** 导出完整视频：合成成功后不再弹窗，直接自动保存至本地 */
  const onExportFullVideo = useCallback(async () => {
    setExportMenuOpen(false)
    const bridge = previewExportBridgeRef.current
    if (!bridge) {
      message.warning('预览页尚未就绪，请稍后再试')
      return
    }
    const result = await bridge.exportFullVideo()
    if (!result?.videoUrl) return
    // 二次导出成功后恢复「发布至案例广场」
    setPublishBlockedUntilFreshExport(false)
    const editorId = Number(result.episodeEditorId)
    exportedEpisodeEditorIdRef.current =
      Number.isFinite(editorId) && editorId > 0
        ? editorId
        : useCreationStore.getState().currentEpisodeEditorId
    await saveExportedVideoToLocal()
  }, [saveExportedVideoToLocal])

  const onExportSegments = useCallback(async () => {
    setExportMenuOpen(false)
    const bridge = previewExportBridgeRef.current
    if (!bridge) {
      message.warning('预览页尚未就绪，请稍后再试')
      return
    }
    await bridge.exportSegments()
  }, [])

  /** 完整导出成功（含切步/刷新恢复场景）：自动保存至本地；发布链路合成不触发下载 */
  const handlePreviewExportSuccess = useCallback(
    (_videoUrl: string) => {
      if (publishFlowRunningRef.current) return
      setPublishBlockedUntilFreshExport(false)
      exportedEpisodeEditorIdRef.current = useCreationStore.getState().currentEpisodeEditorId
      void saveExportedVideoToLocal()
    },
    [saveExportedVideoToLocal]
  )

  /** 发布至案例广场入口：需已有当前可用成片；合成/下载仅「导出完整视频」可触发 */
  const onPublishToCasePlaza = useCallback(() => {
    setExportMenuOpen(false)
    if (publishFlowRunningRef.current || publishCasePlazaModalOpenRef.current) return
    const s = useCreationStore.getState()
    const projectId = Number(s.currentProjectId ?? optionsRef.current.getActiveProjectId())
    if (!Number.isFinite(projectId) || projectId <= 0) {
      message.warning('缺少项目信息，无法发布')
      return
    }
    const blockReason = resolvePublishToCasePlazaBlockReason(getFullVideoExportState())
    if (blockReason) {
      message.warning(blockReason)
      return
    }
    publishCasePlazaModalOpenRef.current = true
    setPublishCasePlazaModalOpen(true)
  }, [])

  const setPublishModalOpen = useCallback((open: boolean) => {
    publishCasePlazaModalOpenRef.current = open
    setPublishCasePlazaModalOpen(open)
  }, [])

  /** 发布弹窗回显：描述用项目配置；封面不走风格图，由弹窗按需拉 detail */
  const publishInitialProjectDesc = String(globalSettingDescription).trim()

  /**
   * 发布链路（严格顺序，任一步失败即终止）：
   * 1. 更新项目封面/描述（已在弹窗内完成，成功才会回调到这里）
   * 2. 提交审核 / 重新提交审核（按需）
   * 3. 发布
   * 不再调用合成完整视频 / 下载接口（仅「导出完整视频」可触发）
   */
  const onPublishCasePlazaMetaSuccess = useCallback(
    async (payload: { projectId: number; coverUrl: string; projectDesc: string }) => {
      if (publishFlowRunningRef.current) return
      const nextDesc = String(payload.projectDesc || '').trim()
      const nextCover = String(payload.coverUrl || '').trim()
      const s = useCreationStore.getState()
      if (nextDesc) {
        s.updateFormData({
          globalSetting: {
            ...s.formData.globalSetting,
            description: nextDesc
          }
        })
      }
      const blockReason = resolvePublishToCasePlazaBlockReason(getFullVideoExportState())
      if (blockReason) {
        message.warning(blockReason)
        return
      }
      publishFlowRunningRef.current = true
      setPublishFlowRunning(true)
      try {
        await optionsRef.current.handleSubmit({
          alsoPublish: true,
          coverUrl: nextCover,
          projectDesc: nextDesc
        })
      } finally {
        publishFlowRunningRef.current = false
        setPublishFlowRunning(false)
      }
    },
    []
  )

  // 未使用的选择器保持订阅（原 computed 依赖面一致，确保发布态渲染同步刷新）
  void currentProjectIsPublic
  void currentEpisodeStatus
  void currentProjectStatus
  void currentProjectType

  return {
    exportMenuOpen,
    setExportMenuOpen,
    onExportMenuOpenChange,
    previewExportBusy,
    registerPreviewExportBridge,
    onExportFullVideo,
    onExportSegments,
    handlePreviewExportSuccess,
    publishToCasePlazaTooltip,
    publishToCasePlazaDisabled,
    getPublishTooltipPopupContainer,
    onPublishToCasePlaza,
    publishCasePlazaModalOpen,
    setPublishModalOpen,
    publishInitialProjectDesc,
    onPublishCasePlazaMetaSuccess
  }
}
