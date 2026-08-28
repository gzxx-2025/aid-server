'use client'

import { message } from 'antd'
import { useRef } from 'react'
import type { ModelOption } from '~/components/steps/ModelSelectDropdown'
import { useModelList } from '~/composables/useModelList'
import { useVideoModelGenerateSettings } from '~/composables/useVideoModelGenerateSettings'
import type { UserModelListItem } from '~/types/business-api'
import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import {
resolvePreferredModelIdFromAgentCodes,
resolveSelectedModelOption,
resolveStoryboardVideoPromptSubmitAgentCode
} from '~/utils/extractAgentBiz'
import { resolveGenerateAudioFlag } from '~/utils/modelCapability'
import {
canSwitchModelWithReferenceAudio,
MODEL_NO_REF_AUDIO_TIP,
parseReferenceAudioCapability
} from '~/utils/referenceAudioCapability'
import {
buildRecommendedDurationTipText,
readRecommendedDurationSeconds,
resolveVideoDurationOption
} from '~/utils/resolveVideoDurationOption'
import { fetchUserStoryboardDetailOnce } from '~/utils/storyboardDetailOnce'
import { mapUserModelListItemToModelOption } from '~/utils/userModelOption'
import type { VideoModalCtx } from './types'

export function useVideoModalModelCore(ctx: VideoModalCtx) {
  const mapVideoModelOption = (
    item: Parameters<typeof mapUserModelListItemToModelOption>[0]
  ): ModelOption => mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' })

  const imageToVideoList = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_IMAGE,
    modelType: 'video',
    projectId: () => ctx.store().currentProjectId,
    episodeId: () => ctx.store().currentEpisodeId,
    fallback: [],
    keepFallbackOnEmpty: false,
    mapItem: mapVideoModelOption,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载图生视频模型失败')
    }
  })

  const multiParamList = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO,
    funcCodeFallbacks: [AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_MULTI_PRO],
    modelType: 'video',
    projectId: () => ctx.store().currentProjectId,
    episodeId: () => ctx.store().currentEpisodeId,
    fallback: [],
    keepFallbackOnEmpty: false,
    mapItem: mapVideoModelOption,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载多参生视频模型失败')
    }
  })

  const edgeList = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_EDGE,
    modelType: 'video',
    projectId: () => ctx.store().currentProjectId,
    episodeId: () => ctx.store().currentEpisodeId,
    fallback: [],
    keepFallbackOnEmpty: false,
    mapItem: mapVideoModelOption,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载首尾帧视频模型失败')
    }
  })

  const gridList = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_GRID,
    modelType: 'video',
    projectId: () => ctx.store().currentProjectId,
    episodeId: () => ctx.store().currentEpisodeId,
    fallback: [],
    keepFallbackOnEmpty: false,
    mapItem: mapVideoModelOption,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载宫格视频模型失败')
    }
  })

  function activeVideoModelGet(): string {
    const tab = ctx.leftActiveTab.get()
    if (tab === 'multiParam') return ctx.multiParamVideoModel.get()
    if (tab === 'startEndFrame') return ctx.edgeVideoModel.get()
    if (tab === 'gridVideo') return ctx.gridVideoModel.get()
    return ctx.imageToVideoModel.get()
  }

  function videoRawModelList(): UserModelListItem[] {
    const tab = ctx.leftActiveTab.get()
    if (tab === 'multiParam') return multiParamList.getRawModelList()
    if (tab === 'startEndFrame') return edgeList.getRawModelList()
    if (tab === 'gridVideo') return gridList.getRawModelList()
    return imageToVideoList.getRawModelList()
  }

  /** 当前 Tab 选中的原始模型项（供参考音频 capability / 导入弹窗） */
  function activeVideoRawModel(): UserModelListItem | null {
    const code = String(activeVideoModelGet() || '').trim()
    const list = videoRawModelList() || []
    if (!code) return list[0] || null
    return (
      list.find((m) => String(m.modelCode || '').trim() === code || String(m.id) === code) ||
      list[0] ||
      null
    )
  }

  function selectedImageToVideoModel(): ModelOption {
    return resolveSelectedModelOption(imageToVideoList.getModelList(), ctx.imageToVideoModel.get())
  }

  function selectedMultiParamVideoModel(): ModelOption {
    return resolveSelectedModelOption(multiParamList.getModelList(), ctx.multiParamVideoModel.get())
  }

  function selectedEdgeVideoModel(): ModelOption {
    return resolveSelectedModelOption(edgeList.getModelList(), ctx.edgeVideoModel.get())
  }

  function selectedGridVideoModel(): ModelOption {
    return resolveSelectedModelOption(gridList.getModelList(), ctx.gridVideoModel.get())
  }

  function guardSelectVideoModel(
    model: ModelOption,
    rawList: { modelCode?: string; id?: number; capability?: unknown }[]
  ): boolean {
    const raw =
      rawList.find(
        (m) =>
          String(m.modelCode || '').trim() === String(model.id) || String(m.id) === String(model.id)
      ) || null
    const check = canSwitchModelWithReferenceAudio(
      parseReferenceAudioCapability(raw),
      ctx.referenceAudios.get().length > 0
    )
    if (!check.ok) {
      message.warning('message' in check ? check.message : MODEL_NO_REF_AUDIO_TIP)
      return false
    }
    return true
  }

  function handleSelectImageToVideoModel(model: ModelOption) {
    ctx.imageToVideoModelDropdownExpanded.set(false)
    if (!guardSelectVideoModel(model, imageToVideoList.getRawModelList())) return
    ctx.imageToVideoModel.set(model.id)
    syncVideoSettingsToModel()
  }

  function handleSelectMultiParamVideoModel(model: ModelOption) {
    ctx.multiParamVideoModelDropdownExpanded.set(false)
    if (!guardSelectVideoModel(model, multiParamList.getRawModelList())) return
    ctx.multiParamVideoModel.set(model.id)
    syncVideoSettingsToModel()
  }

  function handleSelectEdgeVideoModel(model: ModelOption) {
    ctx.edgeVideoModelDropdownExpanded.set(false)
    if (!guardSelectVideoModel(model, edgeList.getRawModelList())) return
    ctx.edgeVideoModel.set(model.id)
    syncVideoSettingsToModel()
  }

  function handleSelectGridVideoModel(model: ModelOption) {
    ctx.gridVideoModelDropdownExpanded.set(false)
    if (!guardSelectVideoModel(model, gridList.getRawModelList())) return
    ctx.gridVideoModel.set(model.id)
    syncVideoSettingsToModel()
  }

  // capability 驱动的视频参数（原 useVideoModelGenerateSettings 接线）
  const videoSettings = useVideoModelGenerateSettings({
    selectedModelCode: (() => {
      const tab = ctx.leftActiveTab.value
      if (tab === 'multiParam') return ctx.multiParamVideoModel.value
      if (tab === 'startEndFrame') return ctx.edgeVideoModel.value
      if (tab === 'gridVideo') return ctx.gridVideoModel.value
      return ctx.imageToVideoModel.value
    })(),
    rawModelList: (() => {
      const tab = ctx.leftActiveTab.value
      if (tab === 'multiParam') return multiParamList.rawModelList
      if (tab === 'startEndFrame') return edgeList.rawModelList
      if (tab === 'gridVideo') return gridList.rawModelList
      return imageToVideoList.rawModelList
    })(),
    generationSettings: {
      aspectRatio: ctx.videoAspectRatio.value,
      count: ctx.videoCount.value,
      quality: ctx.videoQuality.value,
      duration: ctx.videoDuration.value,
      audio: ctx.videoAudio.value
    },
    onGenerationSettingsChange: (v) => {
      ctx.videoAspectRatio.set(v.aspectRatio)
      ctx.videoCount.set(v.count)
      ctx.videoQuality.set(v.quality)
      ctx.videoDuration.set(v.duration)
      ctx.videoAudio.set(v.audio)
    },
    aspectRatioEnumLabels: ctx.aspectRatioEnumOptions()
  })
  /** 事件回调内读最新 capability 输出（渲染后镜像） */
  const videoSettingsRef = useRef(videoSettings)
  videoSettingsRef.current = videoSettings

  /** 原 syncVideoSettingsToModel：显式带上 Mirrored 最新值，保证与 Vue 同步 ref 语义一致 */
  function syncVideoSettingsToModel() {
    videoSettingsRef.current.syncSettingsToModel({
      selectedModelCode: activeVideoModelGet(),
      settings: {
        aspectRatio: ctx.videoAspectRatio.get(),
        count: ctx.videoCount.get(),
        quality: ctx.videoQuality.get(),
        duration: ctx.videoDuration.get(),
        audio: ctx.videoAudio.get()
      },
      rawModelList: videoRawModelList()
    })
  }

  function videoConfigShowDuration(): boolean {
    return videoSettingsRef.current.supportsDuration
  }

  function videoConfigShowAudio(): boolean {
    return videoSettingsRef.current.supportsAudio
  }

  function resolvedRecommendedDurationSeconds(): number {
    return resolveVideoDurationOption({
      recommendedDurationSeconds: ctx.recommendedDurationSecondsRaw.get(),
      durationOptions: videoSettingsRef.current.capabilitySnapshot.durationOptions,
      defaultDurationSeconds: videoSettingsRef.current.capabilitySnapshot.defaultDurationSeconds
    })
  }

  function videoDurationTip(): string {
    if (!videoConfigShowDuration()) return ''
    // 无 detail 推荐时长时不展示提示，避免把模型默认秒数误当成「推荐最优」
    if (ctx.recommendedDurationSecondsRaw.get() == null) return ''
    return buildRecommendedDurationTipText(ctx.videoDuration.get(), resolvedRecommendedDurationSeconds())
  }

  function applyRecommendedVideoDuration() {
    if (!videoConfigShowDuration()) return
    ctx.videoDuration.set(String(resolvedRecommendedDurationSeconds()))
  }

  function applyRecommendedDurationFromDetailRow(
    row: { recommendedDurationSeconds?: number | null } | null | undefined
  ) {
    ctx.recommendedDurationSecondsRaw.set(readRecommendedDurationSeconds(row))
    applyRecommendedVideoDuration()
  }

  async function loadRecommendedDurationForScene(options?: { force?: boolean }) {
    const id = ctx.currentStoryboardId()
    // 先清空，避免切分镜/重开弹窗短暂套用上一镜推荐秒数
    ctx.recommendedDurationSecondsRaw.set(null)
    if (!id) {
      applyRecommendedVideoDuration()
      return
    }
    try {
      const row = await fetchUserStoryboardDetailOnce(id, { force: options?.force === true })
      if (ctx.currentStoryboardId() !== id) return
      applyRecommendedDurationFromDetailRow(row)
    } catch {
      if (ctx.currentStoryboardId() !== id) return
      ctx.recommendedDurationSecondsRaw.set(null)
      applyRecommendedVideoDuration()
    }
  }

  /** 生成提示词完成后 detail 可能更新推荐时长；复用刚写入的短缓存并写回下拉默认值 */
  async function refreshRecommendedDurationAfterPromptGenerate(storyboardId: number) {
    if (ctx.currentStoryboardId() !== storyboardId) return
    try {
      const row = await fetchUserStoryboardDetailOnce(storyboardId)
      if (ctx.currentStoryboardId() !== storyboardId) return
      applyRecommendedDurationFromDetailRow(row)
    } catch {
      /* 保留生成前的时长默认值 */
    }
  }

  function applySavedVideoGenerateSettings() {
    const saved = ctx.store().storyboardVideoGenerateSettings
    if (saved.aspectRatio) ctx.videoAspectRatio.set(saved.aspectRatio)
    if (saved.resolution) ctx.videoQuality.set(String(saved.resolution).toLowerCase())
    // 时长默认始终优先 detail.recommendedDurationSeconds，不再用本地保存秒数覆盖
    // 音频先按本地偏好恢复；不支持音画同出的模型由后续 syncVideoSettingsToModel 强制 silent
    if (saved.soundEffects === 'with-sound') ctx.videoAudio.set('with_audio')
    else if (saved.soundEffects === 'none') ctx.videoAudio.set('silent')
    else ctx.videoAudio.set('with_audio')
  }

  function resolveCurrentGenerateAudio(): boolean {
    return resolveGenerateAudioFlag(ctx.videoAudio.get() === 'with_audio', videoConfigShowAudio())
  }

  function resolveImageVideoPromptAgentCode(): string {
    return resolveStoryboardVideoPromptSubmitAgentCode(
      'video_prompt_image',
      ctx.store().storyboardVideoGenerateSettings.agentId
    )
  }

  function resolveGridVideoPromptAgentCode(): string {
    return resolveStoryboardVideoPromptSubmitAgentCode(
      'video_prompt_grid',
      ctx.store().storyboardVideoGenerateSettings.agentId
    )
  }

  function resolveMultiVideoPromptAgentCode(): string {
    return resolveStoryboardVideoPromptSubmitAgentCode(
      'video_prompt',
      ctx.store().storyboardVideoGenerateSettings.agentId
    )
  }

  function applyVideoModelDefaultFromAgent(
    target: 'imageToVideo' | 'multiParam' | 'startEndFrame' | 'gridVideo',
    options: ModelOption[],
    agentDefaultCodes: string[]
  ) {
    if (!options.length) return
    const selected =
      resolvePreferredModelIdFromAgentCodes(options, { agentDefaultCodes }) || options[0]?.id || ''
    if (!selected) return
    if (target === 'imageToVideo') ctx.imageToVideoModel.set(selected)
    else if (target === 'multiParam') ctx.multiParamVideoModel.set(selected)
    else if (target === 'gridVideo') ctx.gridVideoModel.set(selected)
    else ctx.edgeVideoModel.set(selected)
  }

  function reapplyVideoModelDefaultIfEmpty() {
    if (!ctx.props().open) return
    if (!String(ctx.imageToVideoModel.get() || '').trim() && imageToVideoList.getModelList().length) {
      applyVideoModelDefaultFromAgent(
        'imageToVideo',
        imageToVideoList.getModelList(),
        ctx.cachedImageToVideoAgentModelCodes.current
      )
    }
    if (
      !String(ctx.multiParamVideoModel.get() || '').trim() &&
      multiParamList.getModelList().length
    ) {
      applyVideoModelDefaultFromAgent(
        'multiParam',
        multiParamList.getModelList(),
        ctx.cachedMultiParamAgentModelCodes.current
      )
    }
    if (!String(ctx.edgeVideoModel.get() || '').trim() && edgeList.getModelList().length) {
      ctx.edgeVideoModel.set(edgeList.getModelList()[0]!.id)
    }
    if (!String(ctx.gridVideoModel.get() || '').trim() && gridList.getModelList().length) {
      applyVideoModelDefaultFromAgent(
        'gridVideo',
        gridList.getModelList(),
        ctx.cachedGridVideoAgentModelCodes.current
      )
    }
    syncVideoSettingsToModel()
    applyRecommendedVideoDuration()
  }

  return {
    activeVideoModelGet,
    activeVideoRawModel,
    applyRecommendedVideoDuration,
    applySavedVideoGenerateSettings,
    applyVideoModelDefaultFromAgent,
    edgeList,
    gridList,
    handleSelectEdgeVideoModel,
    handleSelectGridVideoModel,
    handleSelectImageToVideoModel,
    handleSelectMultiParamVideoModel,
    imageToVideoList,
    loadRecommendedDurationForScene,
    mapVideoModelOption,
    multiParamList,
    reapplyVideoModelDefaultIfEmpty,
    refreshRecommendedDurationAfterPromptGenerate,
    resolveCurrentGenerateAudio,
    resolveGridVideoPromptAgentCode,
    resolveImageVideoPromptAgentCode,
    resolveMultiVideoPromptAgentCode,
    selectedEdgeVideoModel,
    selectedGridVideoModel,
    selectedImageToVideoModel,
    selectedMultiParamVideoModel,
    syncVideoSettingsToModel,
    videoConfigShowAudio,
    videoConfigShowDuration,
    videoDurationTip,
    videoRawModelList,
    videoSettingsRef,
  }
}
