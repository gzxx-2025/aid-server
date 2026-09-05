'use client'

import { message } from 'antd'
import { useMemo,useRef } from 'react'
import {
createAsyncPromptApplyGuard,
type AsyncPromptApplyTicket
} from '~/utils/asyncPromptApplyGuard'
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
  const promptApplyGuardRef = useRef(createAsyncPromptApplyGuard())
  const promptScopeKey = (storyboardId = ctx.currentStoryboardId()) =>
    `storyboard-image:${String(storyboardId ?? '')}`
  const beginStoryboardPromptApply = (storyboardId: number) =>
    promptApplyGuardRef.current.begin(promptScopeKey(storyboardId))
  const canApplyStoryboardPrompt = (ticket: AsyncPromptApplyTicket) =>
    promptApplyGuardRef.current.isCurrent(ticket, promptScopeKey())
  const handleStoryboardPromptEditorChange = (value: string) => {
    promptApplyGuardRef.current.markEdited()
    ctx.storyboardPrompt.set(value)
  }
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
    const resolvedImageAssets = ctx.resolvedPromptAssets
      .get()
      .filter((asset) => asset.assetType !== 'audio')
    const startIndex =
      resolvedImageAssets.length > 0
        ? Math.max(...resolvedImageAssets.map((asset) => asset.imageIndex)) + 1
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

  async function applyStoryboardPromptFromApi(
    plain: string,
    ticket = beginStoryboardPromptApply(Number(ctx.currentStoryboardId()))
  ): Promise<boolean> {
    if (!canApplyStoryboardPrompt(ticket)) return false
    const text = String(plain || '').trim()
    if (!text) {
      if (!canApplyStoryboardPrompt(ticket)) return false
      ctx.resolvedPromptAssets.set([])
      ctx.storyboardPrompt.set('')
      return true
    }

    await ensurePromptDictLoaded()
    if (!canApplyStoryboardPrompt(ticket)) return false
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!canApplyStoryboardPrompt(ticket)) return false
    const imageResolve = await resolveStoryboardImageAssetsFromPlain(text, saveCtx)
    if (!canApplyStoryboardPrompt(ticket)) return false

    ctx.storyboardPromptProgrammaticSyncDepth.set(
      ctx.storyboardPromptProgrammaticSyncDepth.get() + 1
    )
    try {
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
      return true
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
      promptApplyGuardRef.current.invalidate()
      ctx.resolvedPromptAssets.set([])
      ctx.storyboardPrompt.set('')
      return
    }
    const ticket = beginStoryboardPromptApply(id)
    try {
      const plain = await fetchStoryboardImagePrompt(id)
      await applyStoryboardPromptFromApi(plain, ticket)
    } catch {
      // 加载失败保留当前输入；旧分镜或用户编辑后的请求结果不得清空编辑器。
    }
  }

  return {
    applyParamSelectionsFromPlain,
    applyStoryboardPromptFromApi,
    beginStoryboardPromptApply,
    handleStoryboardPromptEditorChange,
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
