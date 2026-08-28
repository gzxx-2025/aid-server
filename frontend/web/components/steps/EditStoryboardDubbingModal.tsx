'use client'

import { useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import { Button, Modal, message } from 'antd'
import {
  ArrowLeftOutlined,
  CheckCircleFilled,
  FullscreenOutlined,
  DownloadOutlined,
  CheckOutlined,
  PlusOutlined,
  LoadingOutlined
} from '@ant-design/icons'
import HorizontalScrollTabBar, {
  type HorizontalScrollTabBarHandle
} from '~/components/common/HorizontalScrollTabBar'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import { resolveStoryboardModalTabMedia } from '~/components/common/StoryboardModalTabThumbnail'
import { cancelPendingVideoPosters } from '~/utils/ensureVideoPoster'
import { useVideoPlaybackSpaceShortcut } from '~/composables/useVideoPlaybackSpaceShortcut'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useStoryboardModalHeaderTabs } from '~/composables/useStoryboardModalHeaderTabs'
import { useRouteLike, getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import {
  clearStoryboardDubbingModalUserDismissed,
  markStoryboardDubbingModalUserDismissed
} from '~/utils/storyboardDubbingModalGenSession'
import { isComposeStoryboardVideoRecord } from '~/utils/storyboardRecordRow'
import { DubbingEditLeftPanel } from './DubbingEditLeftPanel'
import { VoiceTimbrePickerModal } from './VoiceTimbrePickerModal'
import deleteIconRaw from '@/assets/img/icon/del-black.svg'
import { assetUrl } from '~/utils/assetUrl'
import { htmlToPlainText } from '~/utils/htmlPlain'
import type { DubbingPanel, StoryboardVideoPanel, StoryboardPanel } from '~/types'
import { useDubbingModalState } from './edit-storyboard-dubbing/useDubbingModalState'
import { useDubbingTtsPreview } from './edit-storyboard-dubbing/useDubbingTtsPreview'
import { useDubbingHeroVideo } from './edit-storyboard-dubbing/useDubbingHeroVideo'
import {
  navKeyLoading,
  TAB_SWITCH_SKELETON_MS,
  type DubbingGenItem,
  type DubbingModalCtx
} from './edit-storyboard-dubbing/types'
import {
  formatDubbingSceneTabPrimaryLabel,
  isPanelDubbingConfigured
} from './edit-storyboard-dubbing/helpers'
import {
  canDeleteHistoryDubbing,
  canSetMainFromHistory,
  getDubbingCanvasMode,
  getDubbingPreviewTitle,
  getDubbingPreviewUrl,
  getLipSyncProgressHint,
  getRightNavEntries,
  getSelectedNavKey,
  getShowLoadingCardForScene,
  getShowSetLipSyncActions,
  getUploadPendingActive,
  getVideoUrl,
  isHistoryDubbingMain,
  isSceneGenerating,
  isSelectedNavLipSyncMain,
  onRightNavClick,
  resolveDubbingCoverImageUrl,
  resolveDubbingPanelKey,
  resolveStoryboardIdForIndex
} from './edit-storyboard-dubbing/derived'
import {
  applyComposeRowsFromProject,
  reconcileSelectedNavKeyForScene,
  refreshServerVideoRecords
} from './edit-storyboard-dubbing/recordsOps'
import {
  buildDubbingGenerateParams,
  handleStoryboardDubbingGenSettledEvent,
  primeDubbingLoadingFromStore,
  restoreStoryboardDubbingGenerateIfNeeded,
  storyboardDubbingModalSessionScope,
  suspendOtherStoryboardDubbingModalFollows,
  syncDubbingLoadingUiFromStore
} from './edit-storyboard-dubbing/generateOps'
import { buildLipSyncRequest } from '~/composables/useStoryboardDubbingGenerate'
import {
  loadDraftForIndex,
  persistCurrentDraft,
  refreshEmotionTagCodeMap
} from './edit-storyboard-dubbing/draftOps'
import {
  applyLipSyncFromPreview,
  confirmSetLipSync,
  handleDeleteHistoryDubbing,
  handleSetMainFromHistory,
  onCancelDubbingSetting,
  onStartDubbingPrepare
} from './edit-storyboard-dubbing/lipSyncOps'
import { DubbingModalSkeleton } from './edit-storyboard-dubbing/DubbingModalSkeleton'
import { DubbingHistoryPanel } from './edit-storyboard-dubbing/DubbingHistoryPanel'
import { DubbingSceneTabs } from './edit-storyboard-dubbing/DubbingSceneTabs'
import './edit-storyboard-dubbing/edit-storyboard-dubbing-base.css'
import './edit-storyboard-dubbing/edit-storyboard-dubbing-stage.css'
import './edit-storyboard-video/edit-storyboard-video-canvas.css'

const deleteIcon = assetUrl(deleteIconRaw)

export interface EditStoryboardDubbingModalProps {
  open: boolean
  sceneIndex: number
  dubbingPanels: DubbingPanel[]
  storyboardVideoPanels?: StoryboardVideoPanel[]
  storyboardScriptPanels?: StoryboardPanel[]
  /** 父组件批量生成中的分镜下标，用于弹窗内头部 tab 与右侧列表的 loading */
  batchGeneratingIndices?: number[]
  /** 弹窗实例作用域，配合分镜 id 隔离配音生成 loading */
  editorScopeKey?: string
  /** 原 emit('update:open') */
  onOpenChange: (open: boolean) => void
  /** 原 emit('update:panels') */
  onPanelsChange: (panels: DubbingPanel[]) => void
  /** 原 emit('update:storyboardVideoPanels')（源组件未触发，保留契约） */
  onStoryboardVideoPanelsChange?: (panels: StoryboardVideoPanel[]) => void
}

export function EditStoryboardDubbingModal({
  open,
  sceneIndex,
  dubbingPanels,
  storyboardVideoPanels,
  storyboardScriptPanels,
  batchGeneratingIndices,
  editorScopeKey = 'storyboard-dubbing',
  onOpenChange,
  onPanelsChange,
  onStoryboardVideoPanelsChange
}: EditStoryboardDubbingModalProps) {
  void onStoryboardVideoPanelsChange

  const route = useRouteLike()
  const S = useDubbingModalState()

  // 最新 props 快照（事件回调 / 异步流程内读取）
  const propsRef = useRef({
    open,
    sceneIndex,
    dubbingPanels,
    storyboardVideoPanels: storyboardVideoPanels || [],
    storyboardScriptPanels: storyboardScriptPanels || [],
    batchGeneratingIndices: batchGeneratingIndices || [],
    editorScopeKey
  })
  propsRef.current = {
    open,
    sceneIndex,
    dubbingPanels,
    storyboardVideoPanels: storyboardVideoPanels || [],
    storyboardScriptPanels: storyboardScriptPanels || [],
    batchGeneratingIndices: batchGeneratingIndices || [],
    editorScopeKey
  }
  const onOpenChangeRef = useRef(onOpenChange)
  onOpenChangeRef.current = onOpenChange
  const onPanelsChangeRef = useRef(onPanelsChange)
  onPanelsChangeRef.current = onPanelsChange
  const refreshHeaderTabsRef = useRef<(force?: boolean) => Promise<void>>(async () => {})
  const heroResetRef = useRef<() => void>(() => {})

  const ctxRef = useRef<DubbingModalCtx | null>(null)
  if (!ctxRef.current) {
    ctxRef.current = {
      props: () => propsRef.current,
      emitPanelsUpdate: (panels) => onPanelsChangeRef.current(panels),
      emitOpenChange: (v) => onOpenChangeRef.current(v),
      state: S,
      refreshHeaderTabs: (force?: boolean) => refreshHeaderTabsRef.current(force),
      route: () => getRouteLikeSnapshot(),
      resetHeroVideoPreviewState: () => heroResetRef.current(),
      resumeDubbingFollowGen: { current: 0 },
      serverVideoRecordsInflightByIndex: new Map(),
      prefetchComposeGenHistoryInflight: { current: null }
    }
  }
  const ctx = ctxRef.current

  const { headerTabs, projectRecordRows, refreshHeaderTabs } = useStoryboardModalHeaderTabs({
    open,
    recordType: 'compose',
    scenes: () =>
      propsRef.current.dubbingPanels.map((panel, index) => ({
        name:
          panel.title ||
          propsRef.current.storyboardScriptPanels[index]?.title ||
          `分镜${index + 1}`,
        storyboardId: resolveStoryboardIdForIndex(ctx, index) ?? undefined
      })),
    route,
    headerOptions: () => ({
      // 顶部 Tab 用分镜图封面，避免打开弹窗并发拉齐 mp4
      resolveFallbackThumbnailUrl: (idx) => resolveDubbingCoverImageUrl(ctx, idx),
      resolveDubbingConfigured: (idx, composeRows) => {
        const panel = propsRef.current.dubbingPanels[idx]
        if (isPanelDubbingConfigured(panel)) return true
        return composeRows.some(
          (r) =>
            isComposeStoryboardVideoRecord(r) &&
            r.isSelected === 1 &&
            !!String(r.fileUrl ?? '').trim()
        )
      }
    })
  })
  refreshHeaderTabsRef.current = refreshHeaderTabs

  const { voicePreviewEstimatedMaxChars, loadPublicConfig } = useAuthPublicConfig()
  const tts = useDubbingTtsPreview(ctx, { voicePreviewEstimatedMaxChars })

  const hero = useDubbingHeroVideo({
    getPreviewUrl: () => getDubbingPreviewUrl(ctx),
    getDownloadName: () =>
      getDubbingPreviewTitle(ctx) ||
      propsRef.current.dubbingPanels[S.currentSceneIndex.get()]?.title ||
      '分镜视频'
  })
  heroResetRef.current = hero.resetHeroVideoPreviewState

  const sceneTabBarRef = useRef<HorizontalScrollTabBarHandle | null>(null)

  function scrollActiveSceneTabIntoView() {
    sceneTabBarRef.current?.scrollItemIntoView('.scene-image-tab.active')
    sceneTabBarRef.current?.refresh()
  }

  function switchScene(index: number) {
    if (
      index === S.currentSceneIndex.get() ||
      index < 0 ||
      index >= propsRef.current.dubbingPanels.length
    ) {
      return
    }
    const keepSid = resolveStoryboardIdForIndex(ctx, index)
    suspendOtherStoryboardDubbingModalFollows(ctx, keepSid)
    tts.stopTtsPreviewPlayback()
    hero.resetHeroVideoPreviewState()
    persistCurrentDraft(ctx)
    S.leftPanelLoading.set(true)
    S.rightPanelLoading.set(true)
    S.currentSceneIndex.set(index)
    loadDraftForIndex(ctx, index)
    void refreshServerVideoRecords(ctx, index).then(() => {
      reconcileSelectedNavKeyForScene(ctx, index)
    })
    setTimeout(() => {
      scrollActiveSceneTabIntoView()
      setTimeout(() => {
        S.leftPanelLoading.set(false)
        S.rightPanelLoading.set(false)
      }, TAB_SWITCH_SKELETON_MS)
      void restoreStoryboardDubbingGenerateIfNeeded(ctx, index)
    }, 0)
  }

  // 原 watch(() => props.open, ..., { immediate: true })：打开初始化 / 关闭停止播放
  // useLayoutEffect：初始化需在首帧绘制前完成（对齐 Vue immediate watch 在 setup 期执行）
  useLayoutEffect(() => {
    if (!open) {
      tts.stopTtsPreviewPlayback()
      hero.pauseHeroVideoPlayback()
      return
    }
    tts.stopTtsPreviewPlayback()
    hero.pauseHeroVideoPlayback()
    hero.resetHeroVideoPreviewState()
    tts.resetTtsPreviewState()
    void refreshEmotionTagCodeMap(ctx)
    void loadPublicConfig()
    S.leftPanelLoading.set(true)
    S.rightPanelLoading.set(true)
    S.draftByIndex.set({})
    S.pendingDubbingByIndex.set({})
    S.pendingPayloadByIndex.set({})
    S.preConfirmPanelByIndex.set({})
    S.confirmedDubbingThisSession.set(new Set())
    S.serverVideoRecordsByIndex.set({})
    S.selectedNavKeyByIndex.set({})
    // 从 panel 恢复生成历史，使批量生成与弹窗内生成的视频均以「新增一条」形式展示
    const nextGen: Record<number, DubbingGenItem[]> = {}
    propsRef.current.dubbingPanels.forEach((p, i) => {
      nextGen[i] = [...(p.dubbingGenHistory || [])]
    })
    S.genHistoryByIndex.set(nextGen)
    S.currentSceneIndex.set(
      Math.min(
        Math.max(0, propsRef.current.sceneIndex),
        Math.max(0, propsRef.current.dubbingPanels.length - 1)
      )
    )
    const openSid = resolveStoryboardIdForIndex(ctx, S.currentSceneIndex.get())
    if (openSid) clearStoryboardDubbingModalUserDismissed(storyboardDubbingModalSessionScope())
    primeDubbingLoadingFromStore(ctx)
    loadDraftForIndex(ctx, S.currentSceneIndex.get())
    setTimeout(() => {
      scrollActiveSceneTabIntoView()
      sceneTabBarRef.current?.refresh()
      setTimeout(() => {
        S.leftPanelLoading.set(false)
        S.rightPanelLoading.set(false)
      }, TAB_SWITCH_SKELETON_MS)
      void restoreStoryboardDubbingGenerateIfNeeded(ctx, S.currentSceneIndex.get())
      syncDubbingLoadingUiFromStore(ctx)
    }, 0)
    const listener = (e: Event) => handleStoryboardDubbingGenSettledEvent(ctx, e)
    window.addEventListener('storyboard-dubbing-gen-settled', listener)
    return () => {
      window.removeEventListener('storyboard-dubbing-gen-settled', listener)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 原 watch(projectRecordRows)
  useEffect(() => {
    if (!propsRef.current.open || !projectRecordRows.length) return
    applyComposeRowsFromProject(ctx, projectRecordRows)
    reconcileSelectedNavKeyForScene(ctx, S.currentSceneIndex.get())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectRecordRows])

  // 原 watch(() => props.sceneIndex)
  useEffect(() => {
    if (propsRef.current.open && sceneIndex >= 0 && sceneIndex < dubbingPanels.length) {
      switchScene(sceneIndex)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sceneIndex])

  // 原 watch(() => props.dubbingPanels, { deep: true })：父组件回写后同步生成历史与 loading
  useEffect(() => {
    if (!propsRef.current.open || !dubbingPanels?.length) return
    const nextGen = { ...S.genHistoryByIndex.get() }
    let changed = false
    dubbingPanels.forEach((p, i) => {
      const hist = p.dubbingGenHistory || []
      if (hist.length > 0) {
        nextGen[i] = [...hist]
        changed = true
      }
    })
    if (changed) S.genHistoryByIndex.set(nextGen)
    syncDubbingLoadingUiFromStore(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dubbingPanels])

  // 原 watch(step4PlusLiveGenByScope[...].storyboardDubbingGenTasksByStoryboardId, { deep: true })
  const dubbingGenTasksByStoryboardId = useCreationStore(
    (s) =>
      s.step4PlusLiveGenByScope[s.step3GenVisualScopeKey()]
        ?.storyboardDubbingGenTasksByStoryboardId
  )
  useEffect(() => {
    syncDubbingLoadingUiFromStore(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dubbingGenTasksByStoryboardId])

  // ---- 渲染期派生（原 computed） ----
  const showLoadingCardForScene = getShowLoadingCardForScene(ctx)
  const dubbingPreviewUrl = getDubbingPreviewUrl(ctx)
  const dubbingCanvasMode = getDubbingCanvasMode(ctx)
  const lipSyncProgressHint = getLipSyncProgressHint(ctx)
  const selectedNavKey = getSelectedNavKey(ctx)
  const rightNavEntries = getRightNavEntries(ctx)
  const uploadPendingActive = getUploadPendingActive(ctx)
  const showSetLipSyncActions = getShowSetLipSyncActions(ctx)
  const selectedNavIsLipSyncMain = isSelectedNavLipSyncMain(ctx)
  const currentSceneIndexVal = S.currentSceneIndex.value
  const isSettingFinalDubbing = S.isSettingFinalDubbing.value
  const leftPanelLoading = S.leftPanelLoading.value
  const rightPanelLoading = S.rightPanelLoading.value
  const dubbingQuoteRequest = useMemo(() => {
    if (!open) return null
    if (S.draftLipSync.value) {
      const params = buildDubbingGenerateParams(ctx, currentSceneIndexVal)
      if (!params) return null
      try {
        return {
          quoteType: 'STORYBOARD_LIP_SYNC',
          payload: { ...buildLipSyncRequest(params) }
        }
      } catch {
        return null
      }
    }
    const storyboardId = resolveStoryboardIdForIndex(ctx, currentSceneIndexVal)
    const ttsText = htmlToPlainText(S.draftDialogue.value || '').trim()
    const voiceLibraryId = Number(S.draftVoiceLibraryId.value)
    const voiceModelId = Number(S.draftVoiceModelId.value)
    const timbreCode = String(S.draftTimbreCode.value || '').trim()
    if (
      !storyboardId ||
      !ttsText ||
      (!(Number.isFinite(voiceLibraryId) && voiceLibraryId > 0) &&
        !(Number.isFinite(voiceModelId) && voiceModelId > 0 && timbreCode))
    ) {
      return null
    }
    const emotion = String(S.emotionNameToCode.value.get(S.draftEmotion.value) || '').trim()
    const voicePayload = {
      ...(voiceLibraryId > 0
        ? { voiceLibraryId }
        : { voiceModelId, timbreCode }),
      ...(emotion && emotion !== 'neutral' ? { emotion } : {})
    }
    return {
      quoteType: 'STORYBOARD_AUDIO',
      payload: { storyboardId, ttsText, ...voicePayload }
    }
  }, [
    S.draftDialogue.value,
    S.draftEmotion.value,
    S.draftLipSync.value,
    S.draftTimbreCode.value,
    S.draftVoiceLibraryId.value,
    S.draftVoiceModelId.value,
    S.emotionNameToCode.value,
    currentSceneIndexVal,
    open
  ])

  // 原 watch(showLoadingCardForScene)：生成中自动选中「正在生成中」项
  useEffect(() => {
    if (showLoadingCardForScene) {
      const i = S.currentSceneIndex.get()
      if (S.selectedNavKeyByIndex.get()[i] !== navKeyLoading) {
        S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [i]: navKeyLoading })
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showLoadingCardForScene])

  // 原 watch(dubbingPreviewUrl)：切换预览源后复位播放状态
  useEffect(() => {
    hero.resetHeroVideoPreviewState()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dubbingPreviewUrl])

  const canToggleHeroVideoWithSpace =
    open && Boolean(dubbingPreviewUrl) && hero.heroVideoMediaReady.value
  useVideoPlaybackSpaceShortcut(canToggleHeroVideoWithSpace, hero.toggleHeroVideoPlayback)

  const headerTabsForDisplay = headerTabs.length
    ? headerTabs
    : dubbingPanels.map((panel, idx) => ({
        sceneIndex: idx,
        storyboardId: resolveStoryboardIdForIndex(ctx, idx) ?? undefined,
        name: panel.title || `分镜${idx + 1}`,
        thumbnailUrl: resolveDubbingCoverImageUrl(ctx, idx),
        hasFinalAsset: false,
        dubbingConfigured: isPanelDubbingConfigured(panel)
      }))

  const sceneItems = dubbingPanels.map((p, i) => {
    const tab = headerTabsForDisplay[i]
    const sourceVideoUrl = getVideoUrl(ctx, i)
    const coverFromScene = resolveDubbingCoverImageUrl(ctx, i)
    const { coverImageUrl, videoUrl } = resolveStoryboardModalTabMedia({
      tabThumbnailUrl: tab?.thumbnailUrl,
      coverImageUrl: coverFromScene,
      videoUrl: sourceVideoUrl
    })
    const hasVideo = !!videoUrl || !!coverImageUrl
    return {
      id: p.id,
      name: formatDubbingSceneTabPrimaryLabel(p.title || '', hasVideo, i),
      coverImageUrl,
      videoUrl
    }
  })

  function handleCancel() {
    persistCurrentDraft(ctx)
    const posterUrls = [
      ...sceneItems.map((t) => String(t.videoUrl || '').trim()),
      ...rightNavEntries.map((n) => String(n.url || '').trim())
    ].filter(Boolean)
    if (posterUrls.length) cancelPendingVideoPosters(posterUrls)
    const sid = resolveStoryboardIdForIndex(ctx, S.currentSceneIndex.get())
    if (
      sid &&
      (useCreationStore.getState().getStoryboardDubbingGenTask(sid) ||
        S.genLoadingByPanelKey.get()[resolveDubbingPanelKey(ctx, S.currentSceneIndex.get())])
    ) {
      markStoryboardDubbingModalUserDismissed(sid, storyboardDubbingModalSessionScope())
    }
    onOpenChangeRef.current(false)
  }

  function onVoiceTimbreConfirm(payload: {
    name: string
    avatarUrl: string
    id: string
    previewUrl: string
    voiceLibraryId?: number
    voiceModelId?: number
    timbreCode?: string
    providerName?: string
    modelCode?: string
  }) {
    S.draftVoiceName.set(payload.name)
    S.draftVoiceAvatarUrl.set(payload.avatarUrl)
    S.draftVoiceLibraryId.set(
      payload.voiceLibraryId != null && payload.voiceLibraryId > 0 ? payload.voiceLibraryId : 0
    )
    S.draftVoiceModelId.set(
      payload.voiceModelId != null && payload.voiceModelId > 0 ? payload.voiceModelId : 0
    )
    S.draftTimbreCode.set(payload.timbreCode || '')
    S.draftVoiceProviderHint.set(
      [payload.providerName, payload.modelCode].filter(Boolean).join('|')
    )
    message.success(`已选择音色：${payload.name}`)
  }

  return (
    <Modal
      open={open}
      width="100vw"
      style={{ top: 0, paddingBottom: 0, maxWidth: '100vw' }}
      footer={null}
      closable={false}
      mask={{ closable: false }}
      wrapClassName="create-flow-modal edit-scene-image-modal edit-storyboard-dubbing-modal"
      className="edit-scene-image-modal"
      onCancel={handleCancel}
    >
      <div className="edit-scene-image-container">
        <div className="modal-header">
          <Button type="text" className="back-btn" icon={<ArrowLeftOutlined />} onClick={handleCancel}>
            <span>返回</span>
          </Button>
          <HorizontalScrollTabBar
            ref={sceneTabBarRef}
            rootClass="scene-switcher scene-switcher--dubbing"
            trackClass="scene-switcher-inner"
          >
            <DubbingSceneTabs
              sceneItems={sceneItems}
              currentSceneIndex={currentSceneIndexVal}
              isSceneGenerating={(index) => isSceneGenerating(ctx, index)}
              onSwitchScene={switchScene}
            />
          </HorizontalScrollTabBar>
        </div>

        <div className="main-content-wrapper">
          {leftPanelLoading || rightPanelLoading ? (
            <DubbingModalSkeleton />
          ) : (
            <div className="figma-stage-layout dubbing-stage-layout">
              {/* 左：生成记录（与分镜视频弹窗一致：主视频角标 + hover 设主） */}
              <DubbingHistoryPanel
                navEntries={rightNavEntries}
                selectedNavKey={selectedNavKey}
                isSettingFinalDubbing={isSettingFinalDubbing}
                deleteIcon={deleteIcon}
                isHistoryDubbingMain={(nav) => isHistoryDubbingMain(ctx, nav)}
                canSetMainFromHistory={(nav) => canSetMainFromHistory(ctx, nav)}
                canDeleteHistoryDubbing={(nav) => canDeleteHistoryDubbing(ctx, nav)}
                onNavClick={(key) => onRightNavClick(ctx, key)}
                onSetMainFromHistory={(nav) => void handleSetMainFromHistory(ctx, nav)}
                onUnsetMainFromHistory={(nav) => {
                  onRightNavClick(ctx, nav.key)
                  void onCancelDubbingSetting(ctx)
                }}
                onDeleteHistoryDubbing={(nav) => handleDeleteHistoryDubbing(ctx, nav)}
              />

              {/* 中：当前选中项预览（DOM / 样式与 EditStoryboardVideoModal 中间栏一致） */}
              <section className="stage-canvas-panel video-stage-canvas dubbing-stage-canvas">
                {dubbingCanvasMode === 'empty' ? (
                  <div className="canvas-empty video-canvas-empty">
                    暂无分镜视频，请先在「视频生成」步骤生成或上传
                  </div>
                ) : (
                  <div className="video-canvas-body video-canvas-body--enhance-wrap">
                    <div
                      className={[
                        'video-card',
                        'video-card--active',
                        dubbingCanvasMode === 'loading' ? 'video-card--generating' : ''
                      ]
                        .filter(Boolean)
                        .join(' ')}
                    >
                      <div className="video-preview-wrap">
                        {dubbingCanvasMode === 'loading' ? (
                          <>
                            <div className="video-placeholder video-placeholder--blank" />
                            <div className="video-card-generating-mask" role="status" aria-live="polite">
                              <LoadingOutlined spin className="video-card-generating-mask__icon" />
                              <span className="video-card-generating-mask__text">
                                {lipSyncProgressHint || '正在生成中...'}
                              </span>
                            </div>
                          </>
                        ) : dubbingPreviewUrl ? (
                          <>
                            <ShimmerVideo
                              key={dubbingPreviewUrl}
                              ref={hero.heroVideoComponentRef}
                              src={dubbingPreviewUrl}
                              videoClass="video-preview"
                              objectFit="contain"
                              revealDirection="fade"
                              preload="metadata"
                              onLoad={hero.markHeroVideoMediaReady}
                              onEnded={hero.onHeroVideoEnded}
                              onPause={hero.onHeroVideoPause}
                              onClick={(e) => {
                                e.stopPropagation()
                                void hero.toggleHeroVideoPlayback()
                              }}
                            />
                            {!hero.heroVideoPlaying.value && hero.heroVideoMediaReady.value ? (
                              <button
                                type="button"
                                className="dubbing-video-play-btn"
                                title="播放视频"
                                aria-label="播放视频"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  void hero.toggleHeroVideoPlayback()
                                }}
                              />
                            ) : null}
                            <div className="video-top-actions">
                              <Button
                                type="text"
                                size="small"
                                className="video-action-btn"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  void hero.handleFullscreenHeroVideo()
                                }}
                              >
                                <FullscreenOutlined />
                              </Button>
                              <Button
                                type="text"
                                size="small"
                                className="video-action-btn"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  hero.downloadPreviewVideo()
                                }}
                              >
                                <DownloadOutlined />
                              </Button>
                            </div>
                          </>
                        ) : null}
                      </div>
                      {dubbingCanvasMode !== 'loading' &&
                      (showSetLipSyncActions || uploadPendingActive) ? (
                        <div className="video-card-actions">
                          {showSetLipSyncActions ? (
                            selectedNavIsLipSyncMain ? (
                              <Button
                                size="small"
                                className="btn-set-storyboard-done"
                                loading={isSettingFinalDubbing}
                                disabled={isSettingFinalDubbing}
                                onClick={() => void onCancelDubbingSetting(ctx)}
                              >
                                <CheckCircleFilled className="mr-1" />
                                取消设置
                              </Button>
                            ) : (
                              <Button
                                type="primary"
                                size="small"
                                className="btn-set-storyboard"
                                loading={isSettingFinalDubbing}
                                disabled={
                                  isSettingFinalDubbing || selectedNavKey === navKeyLoading
                                }
                                onClick={() => void applyLipSyncFromPreview(ctx)}
                              >
                                <CheckOutlined className="mr-1" />
                                设置为音画同步结果
                              </Button>
                            )
                          ) : null}
                          {uploadPendingActive ? (
                            <Button
                              type="primary"
                              size="small"
                              className="btn-set-storyboard"
                              icon={<PlusOutlined />}
                              onClick={() => void confirmSetLipSync(ctx)}
                            >
                              设置分镜音画同步结果
                            </Button>
                          ) : null}
                        </div>
                      ) : null}
                    </div>
                  </div>
                )}
              </section>

              {/* 右：配音配置（中间内容可滚，「对口型 / 开始配音」固定底部） */}
              <aside className="stage-config-panel dubbing-stage-config">
                <div className="dubbing-config-below-tabs">
                  <DubbingEditLeftPanel
                    dialogue={S.draftDialogue.value}
                    emotion={S.draftEmotion.value}
                    emotionOptions={S.emotionLabelOptions.value}
                    lipSync={S.draftLipSync.value}
                    voiceName={S.draftVoiceName.value}
                    voiceAvatarUrl={S.draftVoiceAvatarUrl.value}
                    ttsPreviewLoading={tts.ttsPreviewLoading}
                    ttsPreviewPlaying={tts.ttsPreviewPlaying}
                    ttsPreviewDurationSec={tts.ttsPreviewDurationSec}
                    billingRequest={dubbingQuoteRequest}
                    onDialogueChange={(v) => S.draftDialogue.set(v)}
                    onEmotionChange={(v) => S.draftEmotion.set(v)}
                    onLipSyncChange={(v) => S.draftLipSync.set(v)}
                    onVoiceNameChange={(v) => S.draftVoiceName.set(v)}
                    onPreviewListen={() => void tts.onPreviewListen()}
                    onPickVoice={() => S.voicePickerOpen.set(true)}
                    onStartDubbing={(payload) => void onStartDubbingPrepare(ctx, payload)}
                  />
                </div>
              </aside>
            </div>
          )}
        </div>
      </div>

      <VoiceTimbrePickerModal
        open={S.voicePickerOpen.value}
        initialVoiceName={S.draftVoiceName.value}
        onOpenChange={(v) => S.voicePickerOpen.set(v)}
        onConfirm={onVoiceTimbreConfirm}
      />
    </Modal>
  )
}

export default EditStoryboardDubbingModal
