'use client'

import { message } from 'antd'
import { useRef } from 'react'
import { matchesModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import {
filterAspectRatiosForVideoModal,
PROMPT_TYPE,
usePromptDictionary
} from '~/composables/usePromptDictionary'
import { looksLikeMarkdown,scriptApiTextToEditorHtml } from '~/utils/htmlPlain'
import { collectReferenceAudioIds } from '~/utils/referenceMediaItem'
import {
collectStoryboardPromptAssets,
patchEmptyResolvedPromptAssets,
storyboardPromptHtmlToPlain,
storyboardPromptMarkdownPlainToHtml,
storyboardPromptPlainToHtml,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import {
prependDefaultReferenceImageToPlainPrompt,
promptPlainHasAssetPlaceholders
} from '~/utils/storyboardPromptDefaultRefInject'
import {
resolveStoryboardImageAssetsFromPlain
} from '~/utils/storyboardPromptGenerateFlow'
import {
buildMultiParamVideoPromptParamGroups,
buildStoryboardVideoPromptParamGroups,
extractVideoPromptParamSelectionsFromPlain,
plainHasVideoLabeledParamFields
} from '~/utils/storyboardPromptParamRef'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { VideoModalCtx } from './types'

/** 对齐 Vue nextTick（await nextTick() 语义） */
function nextTickAsync(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}


export function useVideoModalPromptEditorOps(ctx: VideoModalCtx) {
  const dict = usePromptDictionary()
  /** 事件回调 / 异步流程内读最新词库（避免闭包旧值） */
  const dictRef = useRef(dict)
  dictRef.current = dict

  function ensureDictLoaded() {
    return dictRef.current.ensureLoaded()
  }

  function cameraMovementOptions() {
    return dictRef.current.cameraMovementOptions
  }

  function shootingTechniqueOptions() {
    return dictRef.current.shootingTechniqueOptions
  }

  function aspectRatioEnumOptions() {
    return filterAspectRatiosForVideoModal(dictRef.current.aspectRatioEnumOptions)
  }

  function imageToVideoPromptPlain() {
    return storyboardPromptHtmlToPlain(ctx.imageToVideoPrompt.get())
  }

  function multiParamPromptPlain() {
    return storyboardPromptHtmlToPlain(ctx.multiParamPrompt.get())
  }

  function edgeVideoPromptPlain() {
    return storyboardPromptHtmlToPlain(ctx.edgeVideoPrompt.get())
  }

  function videoPromptParamGroups() {
    return buildStoryboardVideoPromptParamGroups({
      cameraMovement: cameraMovementOptions(),
      shootingTechnique: shootingTechniqueOptions()
    })
  }

  function multiParamPromptParamGroups() {
    return buildMultiParamVideoPromptParamGroups({
      cameraMovement: cameraMovementOptions(),
      shootingTechnique: shootingTechniqueOptions()
    })
  }

  function showGeneratingVideoPromptForScene(): boolean {
    const promptTaskKind =
      ctx.leftActiveTab.get() === 'gridVideo' ? 'grid-video-prompt-gen' : 'video-prompt-gen'
    return matchesModalTaskOverlayKey(
      ctx.videoPromptGenerateTargetKey.get(),
      ctx.overlayKeyParts(ctx.currentSceneIndex.get(), promptTaskKind)
    )
  }

  function showGeneratingMultiParamPromptForScene(): boolean {
    return matchesModalTaskOverlayKey(
      ctx.multiParamPromptGenerateTargetKey.get(),
      ctx.overlayKeyParts(ctx.currentSceneIndex.get(), 'multi-video-prompt-gen')
    )
  }

  function isStoryboardVideoPromptGeneratingForScene(sceneIdx = ctx.currentSceneIndex.get()): boolean {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (Number.isFinite(storyboardId) && storyboardId > 0) {
      if (ctx.activeStoryboardPromptFollowStoryboardIds.has(storyboardId)) return true
    }
    return (
      matchesModalTaskOverlayKey(
        ctx.videoPromptGenerateTargetKey.get(),
        ctx.overlayKeyParts(sceneIdx, 'video-prompt-gen')
      ) ||
      matchesModalTaskOverlayKey(
        ctx.videoPromptGenerateTargetKey.get(),
        ctx.overlayKeyParts(sceneIdx, 'grid-video-prompt-gen')
      ) ||
      matchesModalTaskOverlayKey(
        ctx.multiParamPromptGenerateTargetKey.get(),
        ctx.overlayKeyParts(sceneIdx, 'multi-video-prompt-gen')
      )
    )
  }

  function applyVideoParamSelectionsFromPlain(plain: string) {
    const selections = extractVideoPromptParamSelectionsFromPlain(plain, videoPromptParamGroups())
    ctx.selectedCameraMovement.set(selections[PROMPT_TYPE.camera_movement] ?? null)
    ctx.selectedImageToVideoShootingTechnique.set(
      selections[PROMPT_TYPE.shooting_technique] ?? null
    )
  }

  function renderStoryboardVideoPromptApiTextToEditor(
    plain: string,
    options?: {
      assets?: PromptAssetItem[]
      paramGroups?: ReturnType<typeof buildStoryboardVideoPromptParamGroups>
      enableAssetRefs?: boolean
      /** 多参生视频：解析 # 标题 / 列表等 Markdown */
      enableMarkdown?: boolean
    }
  ): string {
    const text = String(plain || '').trim()
    if (!text) return ''
    if (options?.enableMarkdown || options?.enableAssetRefs) {
      if (
        options.enableAssetRefs &&
        (text.includes('@') || looksLikeMarkdown(text) || plainHasVideoLabeledParamFields(text))
      ) {
        return storyboardPromptMarkdownPlainToHtml(
          text,
          options.assets ?? [],
          options.paramGroups ?? [],
          {
            enableVideoLabeledParams: true,
            enableAssetRefs: true
          }
        )
      }
      if (options.enableMarkdown && looksLikeMarkdown(text)) {
        return scriptApiTextToEditorHtml(text)
      }
    }
    if (options?.enableAssetRefs && text.includes('@')) {
      return storyboardPromptPlainToHtml(text, options.assets ?? [], options.paramGroups ?? [], {
        enableVideoLabeledParams: true,
        enableAssetRefs: true
      })
    }
    return scriptApiTextToEditorHtml(text)
  }

  async function applyVideoPromptFromApi(plain: string) {
    const raw = String(plain || '').trim()
    if (!raw) {
      ctx.resolvedVideoPromptAssets.set([])
      ctx.imageToVideoPrompt.set('')
      return
    }

    ctx.videoPromptProgrammaticSyncDepth.set(ctx.videoPromptProgrammaticSyncDepth.get() + 1)
    try {
      await ensureDictLoaded()

      const audioIds = collectReferenceAudioIds(ctx.referenceAudios.get())
      const originalHasPlaceholders = promptPlainHasAssetPlaceholders(raw)
      const inject = prependDefaultReferenceImageToPlainPrompt(
        raw,
        ctx.referenceImages.get()[0] ?? null
      )
      const text = inject.plain
      const injectedAsset = inject.injected ? (inject.asset as PromptAssetItem) : null

      applyVideoParamSelectionsFromPlain(text)

      const localAssets = collectStoryboardPromptAssets(
        ctx.sceneImages.get(),
        ctx.characterImages.get(),
        ctx.propImages.get(),
        ctx.otherImages.get()
      )
      const localWithInjected = injectedAsset ? [injectedAsset, ...localAssets] : localAssets

      let resolvedAssets: PromptAssetItem[] = []
      if (originalHasPlaceholders || audioIds.length > 0) {
        const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
        const imageResolve = await resolveStoryboardImageAssetsFromPlain(text, saveCtx, {
          referenceAudioIds: audioIds
        })
        if (imageResolve.unresolvedReferenceAudioIds?.length) {
          message.warning('参考音频不可用，请重新选择')
        }
        resolvedAssets = patchEmptyResolvedPromptAssets(
          imageResolve.resolvedAssets,
          localWithInjected
        )
        if (imageResolve.unresolvedNames.length) {
          message.warning(`部分参考图未匹配：${imageResolve.unresolvedNames.join('、')}`)
        }
      } else if (injectedAsset) {
        resolvedAssets = [injectedAsset]
      }

      ctx.resolvedVideoPromptAssets.set(resolvedAssets)
      ctx.imageToVideoPrompt.set(
        renderStoryboardVideoPromptApiTextToEditor(text, {
          assets: resolvedAssets,
          paramGroups: videoPromptParamGroups(),
          enableAssetRefs: true,
          enableMarkdown: false
        })
      )
      ctx.syncResolvedPromptAssetsToImportReferences(resolvedAssets, 'imageToVideo')
      await nextTickAsync()
    } finally {
      ctx.videoPromptProgrammaticSyncDepth.set(ctx.videoPromptProgrammaticSyncDepth.get() - 1)
    }
  }

  async function applyMultiParamPromptFromApi(plain: string) {
    const raw = String(plain || '').trim()
    if (!raw) {
      ctx.resolvedMultiParamPromptAssets.set([])
      ctx.multiParamPrompt.set('')
      return
    }

    ctx.videoPromptProgrammaticSyncDepth.set(ctx.videoPromptProgrammaticSyncDepth.get() + 1)
    try {
      await ensureDictLoaded()

      const audioIds = collectReferenceAudioIds(ctx.referenceAudios.get())
      const originalHasPlaceholders = promptPlainHasAssetPlaceholders(raw)
      const inject = prependDefaultReferenceImageToPlainPrompt(raw, ctx.sceneImages.get()[0] ?? null)
      const text = inject.plain
      const injectedAsset = inject.injected ? (inject.asset as PromptAssetItem) : null

      const localAssets = collectStoryboardPromptAssets(
        ctx.sceneImages.get(),
        ctx.characterImages.get(),
        ctx.propImages.get(),
        ctx.otherImages.get()
      )
      const localWithInjected = injectedAsset ? [injectedAsset, ...localAssets] : localAssets

      let resolvedAssets: PromptAssetItem[] = []
      if (originalHasPlaceholders || audioIds.length > 0) {
        const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
        const imageResolve = await resolveStoryboardImageAssetsFromPlain(text, saveCtx, {
          referenceAudioIds: audioIds
        })
        if (imageResolve.unresolvedReferenceAudioIds?.length) {
          message.warning('参考音频不可用，请重新选择')
        }
        resolvedAssets = patchEmptyResolvedPromptAssets(
          imageResolve.resolvedAssets,
          localWithInjected
        )
        if (imageResolve.unresolvedNames.length) {
          message.warning(`部分参考图未匹配：${imageResolve.unresolvedNames.join('、')}`)
        }
      } else if (injectedAsset) {
        resolvedAssets = [injectedAsset]
      }

      ctx.resolvedMultiParamPromptAssets.set(resolvedAssets)

      const selections = extractVideoPromptParamSelectionsFromPlain(
        text,
        multiParamPromptParamGroups()
      )
      ctx.selectedCameraMovement.set(selections[PROMPT_TYPE.camera_movement] ?? null)
      ctx.multiParamShootingTechnique.set(selections[PROMPT_TYPE.shooting_technique] ?? null)

      ctx.multiParamPrompt.set(
        renderStoryboardVideoPromptApiTextToEditor(text, {
          assets: resolvedAssets,
          paramGroups: multiParamPromptParamGroups(),
          enableAssetRefs: true,
          enableMarkdown: true
        })
      )
      ctx.syncResolvedPromptAssetsToImportReferences(resolvedAssets, 'multiParam')
      await nextTickAsync()
    } finally {
      ctx.videoPromptProgrammaticSyncDepth.set(ctx.videoPromptProgrammaticSyncDepth.get() - 1)
    }
  }
  return {
    applyMultiParamPromptFromApi,
    applyVideoParamSelectionsFromPlain,
    applyVideoPromptFromApi,
    aspectRatioEnumOptions,
    cameraMovementOptions,
    edgeVideoPromptPlain,
    ensureDictLoaded,
    imageToVideoPromptPlain,
    isStoryboardVideoPromptGeneratingForScene,
    multiParamPromptParamGroups,
    multiParamPromptPlain,
    renderStoryboardVideoPromptApiTextToEditor,
    shootingTechniqueOptions,
    showGeneratingMultiParamPromptForScene,
    showGeneratingVideoPromptForScene,
    videoPromptParamGroups,
  }
}
