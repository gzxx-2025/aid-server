'use client'

import { useEffect,useRef } from 'react'
import { useCreateFlowScopeChangedResume } from '~/composables/useCreateFlowLiveGenResume'
import {
activeStoryboardVideoModalOwnedFollowIds
} from '~/composables/useStoryboardVideoBatchGenerate'
import { useVideoPlaybackSpaceShortcut } from '~/composables/useVideoPlaybackSpaceShortcut'
import type { StoryboardPanel } from '~/types'
import { storyboardPromptHtmlToPlain } from '~/utils/storyboardPromptAssetRef'
import { plainHasVideoLabeledParamFields } from '~/utils/storyboardPromptParamRef'
import type { VideoModalCtx } from './types'
import {
removeVideoModalGlobalListeners,
syncVideoModalOpenLifecycle
} from './videoModalLifecycle'

interface EffectDeps {
  open: boolean
  sceneIndex: number
  scenesLength: number
  scriptPanelsValue: StoryboardPanel[]
  creationModeValue: string
  currentVideosValue: any[] | undefined
  currentStoryboardIdValue: number | null
  activeVideoModelValue: string
  showAudioValue: boolean
  i2vOptionsValue: unknown
  multiOptionsValue: unknown
  edgeOptionsValue: unknown
  gridOptionsValue: unknown
  multiParamGroupsValue: unknown
  videoGroupsValue: unknown
  syncLeftActiveTabForCreationMode: (preferPrimary?: boolean) => void
}

/** 原 setup 内全部 watch / 生命周期的 React effect 平移（依赖值由主组件按渲染期取好传入） */
export function useVideoModalEffects(ctx: VideoModalCtx, deps: EffectDeps): void {
  const firstRunGate = useRef<Record<string, boolean>>({})
  function skipFirst(key: string): boolean {
    if (!firstRunGate.current[key]) {
      firstRunGate.current[key] = true
      return true
    }
    return false
  }

  // watch(selectReferenceModalOpen)
  useEffect(() => {
    if (skipFirst('selectReferenceModalOpen')) return
    if (ctx.selectReferenceModalOpen.get()) void ctx.refreshStepPanelImagesForReference()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx.selectReferenceModalOpen.value])

  // watch(currentSceneIndex)
  useEffect(() => {
    if (skipFirst('currentSceneIndex')) return
    ctx.pauseAllVideoPreviews()
    ctx.playingVideoIdx.set(-1)
    ctx.clearVideoPreviewRefs()
    ctx.videoPreviewMediaReady.set({})
    if (ctx.selectReferenceModalOpen.get()) void ctx.refreshStepPanelImagesForReference()
    if (ctx.selectEdgeFrameModalOpen.get()) void ctx.refreshStepPanelImagesForReference()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx.currentSceneIndex.value])

  // watch(scriptPanels 的 images, deep)：清缓存
  useEffect(() => {
    if (skipFirst('scriptPanelsImages')) return
    ctx.stepPanelImagesCache.set({})
    if (ctx.selectReferenceModalOpen.get()) {
      void ctx.refreshStepPanelImagesForReference()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.scriptPanelsValue])

  // watch(selectEdgeFrameModalOpen)
  useEffect(() => {
    if (skipFirst('selectEdgeFrameModalOpen')) return
    if (ctx.selectEdgeFrameModalOpen.get()) void ctx.refreshStepPanelImagesForReference()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx.selectEdgeFrameModalOpen.value])

  // watch(edgeVideoPrompt)：按分镜本地缓存
  useEffect(() => {
    if (skipFirst('edgeVideoPrompt')) return
    ctx.saveEdgeVideoPromptToCache(ctx.currentStoryboardId())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx.edgeVideoPrompt.value])

  // watch(activeVideoModel)
  useEffect(() => {
    if (skipFirst('activeVideoModel')) return
    ctx.syncVideoSettingsToModel()
    ctx.applyRecommendedVideoDuration()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.activeVideoModelValue])

  // watch(videoConfigShowAudio)：切到支持音画同出的模型时按本地偏好恢复；不支持时强制无声（不污染 persist）
  useEffect(() => {
    if (skipFirst('videoConfigShowAudio')) return
    if (!deps.showAudioValue) {
      ctx.videoAudio.set('silent')
      return
    }
    const saved = ctx.store().storyboardVideoGenerateSettings.soundEffects
    if (saved === 'with-sound') ctx.videoAudio.set('with_audio')
    else if (saved === 'none') ctx.videoAudio.set('silent')
    else ctx.videoAudio.set('with_audio')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.showAudioValue])

  // watch(model options x4, flush post)
  useEffect(() => {
    if (skipFirst('i2vOptions')) return
    ctx.reapplyVideoModelDefaultIfEmpty()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.i2vOptionsValue])
  useEffect(() => {
    if (skipFirst('multiOptions')) return
    ctx.reapplyVideoModelDefaultIfEmpty()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.multiOptionsValue])
  useEffect(() => {
    if (skipFirst('edgeOptions')) return
    ctx.reapplyVideoModelDefaultIfEmpty()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.edgeOptionsValue])
  useEffect(() => {
    if (skipFirst('gridOptions')) return
    ctx.reapplyVideoModelDefaultIfEmpty()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.gridOptionsValue])

  // watch(leftActiveTab)
  useEffect(() => {
    if (skipFirst('leftActiveTab')) return
    ctx.syncVideoSettingsToModel()
    ctx.applyRecommendedVideoDuration()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx.leftActiveTab.value])

  // watch(projectCreationMode)
  useEffect(() => {
    if (skipFirst('projectCreationMode')) return
    deps.syncLeftActiveTabForCreationMode()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.creationModeValue])

  // watch(props.open, immediate)（小）：开窗回落主 Tab；关窗释放当前分镜 follow
  const prevOpenSmallRef = useRef<boolean | undefined>(undefined)
  useEffect(() => {
    const wasOpen = prevOpenSmallRef.current
    prevOpenSmallRef.current = deps.open
    if (deps.open) {
      deps.syncLeftActiveTabForCreationMode(true)
      return
    }
    if (!wasOpen) return
    const sid = Number(ctx.props().scenes[ctx.currentSceneIndex.get()]?.storyboardId)
    if (Number.isFinite(sid) && sid > 0) {
      activeStoryboardVideoModalOwnedFollowIds.delete(sid)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.open])

  // watch(props.sceneIndex)
  useEffect(() => {
    if (skipFirst('propsSceneIndex')) return
    ctx.currentSceneIndex.set(deps.sceneIndex)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.sceneIndex])

  /** 稳定包装：open 期挂载的全局事件读最新 ctx 实现 */
  const onGlobalTasksUpdatedRef = useRef(() => ctx.handleGlobalTasksUpdatedForVideoModal())
  const onVideoGenSettledRef = useRef((e: Event) => ctx.handleStoryboardVideoGenSettledEvent(e))

  // watch(props.open, immediate)（大）：开窗初始化 / 关窗清理
  useEffect(() => {
    const onTasks = onGlobalTasksUpdatedRef.current
    const onSettled = onVideoGenSettledRef.current
    const refreshTimer = syncVideoModalOpenLifecycle(ctx, {
      open: deps.open,
      sceneIndex: deps.sceneIndex,
      onGlobalTasksUpdated: onTasks,
      onVideoGenSettled: onSettled
    })
    return () => {
      if (refreshTimer != null) window.clearTimeout(refreshTimer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.open])

  // 卸载兜底：移除全局监听
  useEffect(() => {
    const onTasks = onGlobalTasksUpdatedRef.current
    const onSettled = onVideoGenSettledRef.current
    return () => {
      if (typeof window === 'undefined') return
      removeVideoModalGlobalListeners(onTasks, onSettled)
    }
  }, [])

  // watch(props.scenes.length)
  useEffect(() => {
    if (skipFirst('scenesLength')) return
    if (!ctx.props().open) return
    const timer = setTimeout(() => ctx.sceneTabBarRef.current?.refresh(), 0)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.scenesLength])

  // watch([currentStoryboardId, currentSceneIndex])：切分镜时缓存/恢复首尾帧与提示词、重置参考图
  const prevStoryboardTupleRef = useRef<[number | null, number] | undefined>(undefined)
  useEffect(() => {
    const prev = prevStoryboardTupleRef.current
    prevStoryboardTupleRef.current = [deps.currentStoryboardIdValue, ctx.currentSceneIndex.get()]
    if (prev === undefined) return
    if (!ctx.props().open) return
    const prevStoryboardId = prev[0]
    if (deps.currentStoryboardIdValue === prevStoryboardId) return
    if (prevStoryboardId) {
      ctx.saveEdgeVideoPromptToCache(prevStoryboardId)
      ctx.edgeFrameImagesByStoryboardId.set({
        ...ctx.edgeFrameImagesByStoryboardId.get(),
        [String(prevStoryboardId)]: {
          first: ctx.firstFrameImage.get(),
          last: ctx.lastFrameImage.get()
        }
      })
    }
    if (deps.currentStoryboardIdValue !== prevStoryboardId && prevStoryboardId !== undefined) {
      ctx.resetStoryboardReferenceState()
      if (deps.currentStoryboardIdValue) {
        const cached = ctx.edgeFrameImagesByStoryboardId.get()[String(deps.currentStoryboardIdValue)]
        ctx.firstFrameImage.set(cached?.first ?? null)
        ctx.lastFrameImage.set(cached?.last ?? null)
      } else {
        ctx.firstFrameImage.set(null)
        ctx.lastFrameImage.set(null)
      }
      ctx.applyDefaultStoryboardReferenceImages(ctx.currentSceneIndex.get())
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.currentStoryboardIdValue, ctx.currentSceneIndex.value])

  // watch(videoPromptParamGroups, deep)：词库就绪后同步文本域结构化字段到右侧下拉
  useEffect(() => {
    if (skipFirst('videoPromptParamGroups')) return
    const timer = setTimeout(() => {
      if (ctx.videoPromptProgrammaticSyncDepth.get() > 0) return
      if (!ctx.imageToVideoPrompt.get()) return
      const plain = storyboardPromptHtmlToPlain(ctx.imageToVideoPrompt.get())
      if (!plainHasVideoLabeledParamFields(plain)) return
      ctx.applyVideoParamSelectionsFromPlain(plain)
    }, 0)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.videoGroupsValue])

  // watch(currentSceneVideos, deep)：选中项越界回卷
  useEffect(() => {
    const list = deps.currentVideosValue || []
    if (!list.length) {
      ctx.selectedVideoIdx.set(0)
      return
    }
    if (ctx.selectedVideoIdx.get() >= list.length) {
      ctx.selectedVideoIdx.set(list.length - 1)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps.currentVideosValue])

  // 空格播放快捷键
  const canToggleVideoPreviewWithSpace =
    deps.open && ctx.currentSceneVideos().some((video: any) => Boolean(video?.url))
  useVideoPlaybackSpaceShortcut(canToggleVideoPreviewWithSpace, () =>
    ctx.toggleSelectedVideoPreviewPlayback()
  )

  // 切作品/集恢复
  useCreateFlowScopeChangedResume(() => {
    if (!ctx.props().open) return
    const si = ctx.currentSceneIndex.get()
    void (async () => {
      await ctx.ensureModalVideoLoadingRestored(si)
      void ctx.restoreStoryboardVideoPromptGenerateIfNeeded(si)
      void ctx.restoreStoryboardVideoGenerateIfNeeded(si)
    })()
  })
}
