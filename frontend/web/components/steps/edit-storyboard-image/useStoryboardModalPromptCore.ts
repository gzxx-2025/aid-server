'use client'

import { message } from 'antd'
import { useMemo,useRef } from 'react'
import {
matchesModalTaskOverlayKey
} from '~/composables/useModalTaskScope'
import { PROMPT_TYPE,usePromptDictionary } from '~/composables/usePromptDictionary'
import {
resolveStoryboardGenConfigLlmFields,
STORYBOARD_GEN_CONFIG_SCENE_CODES
} from '~/utils/projectGenConfig'
import { fetchUserStoryboardDetailOnce } from '~/utils/storyboardDetailOnce'
import {
collectStoryboardPromptAssets,
mergePromptAssets,
storyboardPromptHtmlToPlain,
storyboardPromptPlainToHtml
} from '~/utils/storyboardPromptAssetRef'
import {
fetchStoryboardPromptPlainWithRetry,
resolveStoryboardImageAssetsFromPlain,
resolveStoryboardPromptAgentCode,
resolveStoryboardPromptModelCode
} from '~/utils/storyboardPromptGenerateFlow'
import {
buildStoryboardPromptParamGroups,
extractImagePromptParamSelectionsFromPlain
} from '~/utils/storyboardPromptParamRef'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { EditStoryboardImageModalCtx } from './types'
import { nextTick } from './useMirrored'

export function useStoryboardModalPromptCore(ctx: EditStoryboardImageModalCtx) {
  const {
    ensureLoaded: ensurePromptDictLoaded,
    compositionOptions,
    shotSizeOptions,
    cameraAngleOptions,
    focalLengthOptions,
    colorToneOptions,
    lightingOptions,
    techniqueOptions
  } = usePromptDictionary()

  const storyboardPromptParamGroupsMemo = useMemo(
    () =>
      buildStoryboardPromptParamGroups({
        composition: compositionOptions,
        shotSize: shotSizeOptions,
        cameraAngle: cameraAngleOptions,
        focalLength: focalLengthOptions,
        colorTone: colorToneOptions,
        lighting: lightingOptions,
        technique: techniqueOptions
      }),
    [
      compositionOptions,
      shotSizeOptions,
      cameraAngleOptions,
      focalLengthOptions,
      colorToneOptions,
      lightingOptions,
      techniqueOptions
    ]
  )
  const storyboardPromptParamGroupsRef = useRef(storyboardPromptParamGroupsMemo)
  storyboardPromptParamGroupsRef.current = storyboardPromptParamGroupsMemo
  const storyboardPromptParamGroups = () => storyboardPromptParamGroupsRef.current

  const storyboardPromptAssets = () => {
    const startIndex =
      ctx.resolvedPromptAssets.get().length > 0
        ? Math.max(...ctx.resolvedPromptAssets.get().map((a) => a.imageIndex)) + 1
        : 1
    const local = collectStoryboardPromptAssets(
      ctx.sceneImages.get(),
      ctx.characterImages.get(),
      ctx.propImages.get(),
      ctx.otherImages.get(),
      startIndex
    )
    return ctx.resolvedPromptAssets.get().length
      ? mergePromptAssets(ctx.resolvedPromptAssets.get(), local)
      : local
  }

  const storyboardPromptPlainText = () => storyboardPromptHtmlToPlain(ctx.storyboardPrompt.get())

  const showGeneratingPromptForScene = () => {
    const sid = ctx.currentStoryboardId()
    if (sid != null && ctx.activePromptFollowStoryboardIds.has(sid)) return true
    return matchesModalTaskOverlayKey(
      ctx.promptGenerateTargetKey.get(),
      ctx.overlayKeyParts(ctx.currentSceneIndex.get(), -1, 'prompt-gen')
    )
  }

  function applyParamSelectionsFromPlain(plain: string) {
    const selections = extractImagePromptParamSelectionsFromPlain(
      plain,
      storyboardPromptParamGroups()
    )
    ctx.selectedComposition.set(selections[PROMPT_TYPE.composition] ?? null)
    ctx.selectedShotSize.set(selections[PROMPT_TYPE.shot_size] ?? null)
    ctx.selectedCameraAngle.set(selections[PROMPT_TYPE.camera_angle] ?? null)
    ctx.selectedFocalLength.set(selections[PROMPT_TYPE.focal_length] ?? null)
    ctx.selectedColorTone.set(selections[PROMPT_TYPE.color_tone] ?? null)
    ctx.selectedLighting.set(selections[PROMPT_TYPE.lighting] ?? null)
    ctx.selectedTechnique.set(selections[PROMPT_TYPE.exposure_blur] ?? null)
  }

  async function applyStoryboardPromptFromApi(plain: string) {
    const text = String(plain || '').trim()
    if (!text) {
      ctx.resolvedPromptAssets.set([])
      ctx.storyboardPrompt.set('')
      return
    }

    ctx.storyboardPromptProgrammaticSyncDepth.set(
      ctx.storyboardPromptProgrammaticSyncDepth.get() + 1
    )
    try {
      await ensurePromptDictLoaded()

      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      const imageResolve = await resolveStoryboardImageAssetsFromPlain(text, saveCtx)
      ctx.resolvedPromptAssets.set(imageResolve.resolvedAssets)
      if (imageResolve.unresolvedNames.length) {
        message.warning(`部分参考图未匹配：${imageResolve.unresolvedNames.join('、')}`)
      }

      // 构图 / 景别等：@标签 + 「景别：/构图：」等结构化字段前端词库解析
      applyParamSelectionsFromPlain(text)
      ctx.storyboardPrompt.set(
        storyboardPromptPlainToHtml(text, storyboardPromptAssets(), storyboardPromptParamGroups(), {
          enableImageLabeledParams: true
        })
      )
      await nextTick()
    } finally {
      ctx.storyboardPromptProgrammaticSyncDepth.set(
        ctx.storyboardPromptProgrammaticSyncDepth.get() - 1
      )
    }
  }

  function storyboardBizErr(e: unknown): string {
    const x = e as { msg?: string; message?: string }
    return x?.msg || x?.message || '操作失败'
  }

  async function fetchStoryboardImagePrompt(storyboardId: number): Promise<string> {
    const row = await fetchUserStoryboardDetailOnce(storyboardId)
    return String(row?.imagePrompt ?? '').trim()
  }

  async function fetchStoryboardImagePromptAfterGenerate(storyboardId: number): Promise<string> {
    return fetchStoryboardPromptPlainWithRetry(storyboardId, 'imagePrompt')
  }

  /** 分镜图提示词：手动「生成设置」优先，否则读项目生成配置 */
  async function resolveImagePromptSubmitFields() {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    const manualAgent = resolveStoryboardPromptAgentCode(
      ctx.store().storyboardStylistGenerateSettings
    )
    const manualModel = resolveStoryboardPromptModelCode(
      ctx.store().storyboardStylistGenerateSettings
    )
    const manualPick = Boolean(manualAgent || manualModel)
    return resolveStoryboardGenConfigLlmFields(
      saveCtx?.projectId ?? null,
      STORYBOARD_GEN_CONFIG_SCENE_CODES.stylist,
      manualPick,
      manualAgent,
      manualModel
    )
  }

  async function loadCurrentStoryboardPrompt() {
    const id = ctx.currentStoryboardId()
    if (!id) {
      ctx.resolvedPromptAssets.set([])
      ctx.storyboardPrompt.set('')
      return
    }
    try {
      const plain = await fetchStoryboardImagePrompt(id)
      await applyStoryboardPromptFromApi(plain)
    } catch {
      ctx.resolvedPromptAssets.set([])
      ctx.storyboardPrompt.set('')
    }
  }

  return {
    applyParamSelectionsFromPlain,
    applyStoryboardPromptFromApi,
    ensurePromptDictLoaded,
    fetchStoryboardImagePrompt,
    fetchStoryboardImagePromptAfterGenerate,
    loadCurrentStoryboardPrompt,
    resolveImagePromptSubmitFields,
    showGeneratingPromptForScene,
    storyboardBizErr,
    storyboardPromptAssets,
    storyboardPromptParamGroups,
    storyboardPromptParamGroupsMemo,
    storyboardPromptPlainText,
  }
}
