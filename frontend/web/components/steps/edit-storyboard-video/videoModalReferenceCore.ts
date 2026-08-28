'use client'

import { message } from 'antd'
import { resolveStoryboardPanelCoverImage } from '~/utils/storyboardImageCover'
import {
isEmptyPromptAssetUrl,
mergeReferenceImageItems,
promptAssetToReferenceImageItem,
splitResolvedPromptAssetsToReferenceBuckets,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import type {
ReferenceImageItem,
VideoModalCtx
} from './types'

/** 图生视频接口：参考图最多 1 张 */
const MAX_IMAGE_TO_VIDEO_REFERENCE_COUNT = 1

export function createVideoModalReferenceCore(ctx: VideoModalCtx) {
  function referenceImageGet(): ReferenceImageItem | null {
    const first = ctx.referenceImages.get()[0]
    return first ?? null
  }

  function setReferenceImage(v: ReferenceImageItem | null) {
    if (!v) {
      ctx.referenceImages.set([])
      return
    }
    if (ctx.referenceImages.get().length === 0) {
      ctx.referenceImages.set([v])
    } else {
      ctx.referenceImages.set([v, ...ctx.referenceImages.get().slice(1)])
    }
  }

  function cleanStoryboardScriptTabLabel(raw: string, fallbackIndex: number): string {
    const t = String(raw || '').trim()
    const cleaned = t
      .replace(/^分镜视频\d+[：:]\s*/i, '')
      .replace(/[:：]\s*分镜生成中\s*$/u, '')
      .replace(/\s*分镜生成中\s*$/u, '')
      .trim()
    return cleaned || `分镜脚本${fallbackIndex + 1}`
  }

  /** 参考图弹窗第二 Tab：用分镜脚本标题，避免沿用视频面板的「分镜生成中」 */
  function referenceStepTabName(): string {
    const sp = ctx.resolveScriptPanelForSceneIndex(ctx.currentSceneIndex.get())
    return cleanStoryboardScriptTabLabel(
      sp?.title || ctx.scriptRowLabel(),
      ctx.currentSceneIndex.get()
    )
  }

  /** 当前分镜视频对应分镜脚本的分镜图（第二 Tab，按分镜一一对应） */
  function currentPanelStoryboardImages(): any[] {
    const idx = ctx.currentSceneIndex.get()
    const cached = ctx.stepPanelImagesCache.get()[idx]
    if (cached?.length) return cached
    return ctx.localStoryboardImagesForScene(idx)
  }

  /** 各分镜脚本的分镜图，供「选择分镜画面」本作品资产 Tab */
  function storyboardScriptAssetGroups(): { label: string; images: any[] }[] {
    return ctx
      .scriptPanels()
      .map((panel, idx) => {
        const images = (Array.isArray(panel.images) ? panel.images : [])
          .filter((img: any) => String(img?.url || img?.thumbnail || '').trim())
          .map((img: any, j: number) => ({
            ...img,
            id: img.id || `sb-ref-${idx}-${j}-${img.url || img.thumbnail || j}`
          }))
        const shortName = cleanStoryboardScriptTabLabel(panel.title, idx).replace(
          /^分镜脚本\d+[：:]\s*/i,
          ''
        )
        return {
          label: `分镜脚本${idx + 1}: ${shortName || '未命名'}`,
          images
        }
      })
      .filter((g) => g.images.length > 0)
  }

  function resolveSceneCoverImageUrl(sceneIdx: number): string {
    const sp = ctx.resolveScriptPanelForSceneIndex(sceneIdx)
    const scene = ctx.props().scenes[sceneIdx]
    const cover = resolveStoryboardPanelCoverImage({
      images: sp?.images ?? scene?.storyboardImages,
      finalImageUrl: sp?.finalImageUrl
    })
    return String(cover?.thumbnail || cover?.url || '').trim()
  }

  function mapStoryboardCoverToReferenceImage(cover: {
    id?: string | number
    url?: string
    thumbnail?: string
    title?: string
    name?: string
    isSelected?: boolean
    _fromServer?: boolean
    _serverRow?: { id?: number }
  }) {
    const url = String(cover?.url || cover?.thumbnail || '').trim()
    if (!url) return null
    const rawId = cover?.id ?? cover?._serverRow?.id
    return {
      id: rawId != null && String(rawId).trim() ? String(rawId) : `storyboard-cover-${Date.now()}`,
      url,
      thumbnail: String(cover?.thumbnail || cover?.url || url),
      title: cover?.title || cover?.name || '分镜图',
      name: cover?.name || cover?.title || '分镜图',
      ...(cover?._fromServer ? { _fromServer: true, _serverRow: cover._serverRow } : {}),
      ...(cover?.isSelected != null ? { isSelected: cover.isSelected } : {})
    }
  }

  /** 当前分镜脚本已确认主图（isSelected=1 / finalImageUrl），供图生视频与多参默认参考图 */
  function resolveDefaultStoryboardReferenceImage(sceneIdx: number) {
    const sp = ctx.resolveScriptPanelForSceneIndex(sceneIdx)
    const scene = ctx.props().scenes[sceneIdx]
    const cover = resolveStoryboardPanelCoverImage({
      images: sp?.images ?? scene?.storyboardImages,
      finalImageUrl: sp?.finalImageUrl
    })
    if (!cover) return null
    return mapStoryboardCoverToReferenceImage(cover)
  }

  function resetStoryboardReferenceState() {
    ctx.referenceImages.set([])
    ctx.sceneImages.set([])
    ctx.characterImages.set([])
    ctx.propImages.set([])
    ctx.otherImages.set([])
  }

  function collectReferenceImageUrls(): string[] {
    return ctx.referenceImages
      .get()
      .map((r) => String(r.url || r.thumbnail || '').trim())
      .filter(Boolean)
  }

  function collectMultiParamAssetImages(): Array<{ url?: string; thumbnail?: string }> {
    return [
      ...ctx.sceneImages.get(),
      ...ctx.characterImages.get(),
      ...ctx.propImages.get(),
      ...ctx.otherImages.get()
    ].filter((img) => img?.url || img?.thumbnail)
  }

  function validateImageToVideoReferenceImages(images: string[]): boolean {
    if (!images.length) {
      message.warning('请上传或选择至少一张参考图片')
      return false
    }
    if (images.length > MAX_IMAGE_TO_VIDEO_REFERENCE_COUNT) {
      message.warning('图生视频最多只能上传一张参考图片')
      return false
    }
    return true
  }

  function validateMultiParamAssetImages(): boolean {
    if (!collectMultiParamAssetImages().length) {
      message.warning('多参生视频至少需要上传一张图片素材')
      return false
    }
    return true
  }

  function normalizeImageToVideoReferenceItems<T extends { url?: string; thumbnail?: string }>(
    items: T[]
  ): T[] {
    if (items.length <= MAX_IMAGE_TO_VIDEO_REFERENCE_COUNT) return items
    message.warning('图生视频最多只能上传一张参考图片，已使用第一张')
    return items.slice(0, MAX_IMAGE_TO_VIDEO_REFERENCE_COUNT)
  }

  /** 无主图时保持为空；有主图时默认填入图生视频参考图与多参场景参考图 */
  function applyDefaultStoryboardReferenceImages(sceneIdx: number) {
    const main = resolveDefaultStoryboardReferenceImage(sceneIdx)
    if (!main) return
    ctx.referenceImages.set(normalizeImageToVideoReferenceItems([main]))
    ctx.sceneImages.set([main])
  }

  /** 将 resolve 解析出的参考图同步到「导入参考图」展示区，供校验与出片接口使用 */
  function syncResolvedPromptAssetsToImportReferences(
    assets: PromptAssetItem[],
    mode: 'imageToVideo' | 'multiParam'
  ) {
    const withUrl = assets.filter((a) => !isEmptyPromptAssetUrl(a.url))
    if (!withUrl.length) return

    if (mode === 'imageToVideo') {
      const refs = withUrl
        .slice()
        .sort((a, b) => a.imageIndex - b.imageIndex)
        .map(promptAssetToReferenceImageItem)
        .filter((item): item is NonNullable<typeof item> => item != null)
      if (!refs.length) return
      ctx.referenceImages.set(
        normalizeImageToVideoReferenceItems(
          mergeReferenceImageItems(ctx.referenceImages.get(), refs)
        )
      )
      return
    }

    const buckets = splitResolvedPromptAssetsToReferenceBuckets(withUrl, (item) =>
      inferMultiParamAssetType(item)
    )
    ctx.sceneImages.set(mergeReferenceImageItems(ctx.sceneImages.get(), buckets.scene))
    ctx.characterImages.set(mergeReferenceImageItems(ctx.characterImages.get(), buckets.character))
    ctx.propImages.set(mergeReferenceImageItems(ctx.propImages.get(), buckets.prop))
    ctx.otherImages.set(mergeReferenceImageItems(ctx.otherImages.get(), buckets.other))
  }

  function resolveBaseImageRecordId(): number | undefined {
    const refId = Number(referenceImageGet()?.id)
    if (Number.isFinite(refId) && refId > 0) return refId
    return undefined
  }

  function handleImportReference() {
    ctx.selectReferenceModalOpen.set(true)
  }

  function handleMultiParamImportReference() {
    ctx.selectMultiParamReferenceModalOpen.set(true)
  }

  function mapMultiParamReferenceImportItem(item: any, idx: number) {
    return {
      ...item,
      id: item.id || `multi-ref-${Date.now()}-${idx}-${Math.random().toString(36).slice(2, 6)}`,
      url: item.url || item.thumbnail,
      thumbnail: item.thumbnail || item.url,
      title: item.title || item.name
    }
  }

  function inferMultiParamAssetType(item: any): 'scene' | 'character' | 'prop' | 'other' {
    const id = String(item?.id ?? '')
    if (id.includes('proj-scene') || id.includes('scene')) return 'scene'
    if (id.includes('proj-char') || id.includes('character')) return 'character'
    if (id.includes('proj-prop') || id.includes('prop')) return 'prop'
    const title = String(item?.title || item?.name || '')
    if (/^场景\d*/.test(title)) return 'scene'
    if (/^角色\d*/.test(title)) return 'character'
    if (/^道具\d*/.test(title)) return 'prop'
    return 'other'
  }

  return {
    applyDefaultStoryboardReferenceImages,
    cleanStoryboardScriptTabLabel,
    collectMultiParamAssetImages,
    collectReferenceImageUrls,
    currentPanelStoryboardImages,
    handleImportReference,
    handleMultiParamImportReference,
    inferMultiParamAssetType,
    mapMultiParamReferenceImportItem,
    normalizeImageToVideoReferenceItems,
    referenceImageGet,
    referenceStepTabName,
    resetStoryboardReferenceState,
    resolveBaseImageRecordId,
    resolveDefaultStoryboardReferenceImage,
    resolveSceneCoverImageUrl,
    setReferenceImage,
    storyboardScriptAssetGroups,
    syncResolvedPromptAssetsToImportReferences,
    validateImageToVideoReferenceImages,
    validateMultiParamAssetImages,
  }
}
