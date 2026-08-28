'use client'

import { message } from 'antd'
import type { UserAssetRpsFormRow } from '~/types/business-api'
import { rpsRowToUserAssetRow,sortUserAssetRpsRows,userAssetRpsList } from '~/utils/businessApi'
import { roleVoiceBindingToFormFields } from '~/utils/characterVoiceBinding'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { mapRpsFormsToAlignedFormIds } from '~/utils/rpsFormIdsAlign'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
buildManualIndexListFromRps,
collectRpsImageIdsFromImageRows,
getFormsForRpsRow,
inUseImagesFromRpsForm,
mapRpsFormToLocalFormFields,
resolveLegacyFormImageUrlFallback,
sanitizeSceneImageListForForms
} from './scpRowUtils'
import type { CharacterFormItem,FormGenStatus,PropFormItem,ScpCtx,TabKey } from './types'

import type { ScpAssetLoadApi } from './useScpAssetLoad'

export function createScpAssetTabLoader(
  ctx: ScpCtx,
  helpers: Pick<ScpAssetLoadApi, 'applyRpsSettingsToLocalMaps' | 'collectPersistedManualIndices' | 'reconcilePendingExtractForTabFromRps' | 'setTabAssetLoading'>
) {
  const { applyRpsSettingsToLocalMaps, collectPersistedManualIndices, reconcilePendingExtractForTabFromRps, setTabAssetLoading } = helpers
  async function loadPersonalAssetsForTab(
    tab: TabKey,
    options?: { allowWhenExtracting?: boolean; background?: boolean }
  ): Promise<boolean> {
    if (ctx.props().isExtracting && !options?.allowWhenExtracting) return false
    const step = routePathToCreationStep(ctx.route().path)
    if (step !== 'scene-character') return false

    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) return false

    const trackGlobalGen = !options?.background
    const gen = trackGlobalGen ? ++ctx.loadAssetTabGeneration : ctx.loadAssetTabGeneration
    const tabGen = ++ctx.tabAssetLoadGen[tab]
    if (!options?.background) {
      setTabAssetLoading(tab, true)
    }
    const assetType = tab === 'scene' ? 'scene' : tab === 'character' ? 'character' : 'prop'

    try {
      const { rows: rpsRows } = await userAssetRpsList({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        assetType
      })
      if (trackGlobalGen && gen !== ctx.loadAssetTabGeneration) return false

      const sortedRps = sortUserAssetRpsRows(rpsRows)
      const rows = sortedRps.map(rpsRowToUserAssetRow)

      if (tab === 'scene') {
        const prevManualSceneAssetIds = new Set(
          Array.from(ctx.manualScenes.get())
            .map((i) => ctx.sceneAssetIds.get()[i])
            .filter((id): id is number => id != null && Number.isFinite(Number(id)))
            .map((id) => Number(id))
        )
        const sceneNames = rows.map((r, i) => r.assetName || `场景${i + 1}`)
        const sceneImagesNext: Record<number, any[]> = {}
        sortedRps.forEach((raw, i) => {
          const date = raw.updateTime || raw.createTime || ''
          let imgs: any[] = []
          const forms = getFormsForRpsRow(raw)

          const pushInUseFormImages = (f: UserAssetRpsFormRow) => {
            const formId = f.id
            if (formId == null || !Number.isFinite(Number(formId))) return
            const list = inUseImagesFromRpsForm(f)
            for (const img of list) {
              const url = String(img?.imageUrl || '').trim()
              if (!url) continue
              const imageId = Number(img?.id)
              if (
                imgs.some(
                  (x) =>
                    (Number.isFinite(imageId) && Number(x?.rpsImageId) === imageId) ||
                    String(x?.url ?? '').trim() === url
                )
              ) {
                continue
              }
              const sourceType = String(img?.sourceType || '').trim()
              imgs.push({
                id: Number.isFinite(imageId) ? `img-${imageId}` : `form-${formId}`,
                rpsFormId: Number(formId),
                ...(Number.isFinite(imageId) ? { rpsImageId: imageId } : {}),
                title: String(img?.name || f.name || '主图'),
                url,
                thumbnail: url,
                source: 'server',
                importDate: date,
                ...(sourceType ? { sourceType, _serverSourceType: sourceType } : {})
              })
            }
          }

          // 优先 images[]；同一场景任一形态已有图时，不再用其它形态的 form.imageUrl 旧字段（刷新恢复常见双形态占位）
          for (const f of forms) {
            pushInUseFormImages(f)
          }
          if (imgs.length === 0) {
            for (const f of forms) {
              const formId = f.id
              if (formId == null || !Number.isFinite(Number(formId))) continue
              const fallbackUrl = resolveLegacyFormImageUrlFallback(f)
              if (!fallbackUrl) continue
              if (imgs.some((x) => String(x?.url ?? '').trim() === fallbackUrl)) continue
              imgs.push({
                id: `form-${formId}`,
                rpsFormId: Number(formId),
                title: f.name?.trim() || '主图',
                url: fallbackUrl,
                thumbnail: fallbackUrl,
                source: 'server',
                importDate: date
              })
            }
          }
          if (imgs.length > 1) {
            const nonGenericTitle = imgs.filter((x) => String(x?.title ?? '').trim() !== '主图')
            if (nonGenericTitle.length > 0) imgs = nonGenericTitle
          }
          if (imgs.length) sceneImagesNext[i] = sanitizeSceneImageListForForms(imgs, forms)
        })
        ctx.sceneImages.set(sceneImagesNext)
        const nextSceneGenStatus: Record<number, 'idle' | 'generating' | 'success' | 'failed'> = {
          ...ctx.store().sceneGenerationStatus
        }
        sceneNames.forEach((_, i) => {
          const formIds = mapRpsFormsToAlignedFormIds(getFormsForRpsRow(sortedRps[i])).filter(
            (n) => n > 0
          )
          const underFormImageGen = formIds.some((fid) =>
            ctx.isFormIdUnderActiveFormImageGeneration(fid)
          )
          nextSceneGenStatus[i] = ctx.resolveAssetListReconcileGenStatus({
            hasImage: !!(sceneImagesNext[i] && sceneImagesNext[i].length > 0),
            underFormImageGen,
            previousStatus: nextSceneGenStatus[i]
          })
        })
        ctx.sceneGenerationStatus.set(nextSceneGenStatus)
        Object.entries(nextSceneGenStatus).forEach(([k, status]) => {
          const idx = Number(k)
          if (Number.isFinite(idx)) ctx.store().setSceneGenerationStatus(idx, status)
        })
        const sceneAssetIdsNext: Record<number, number> = {}
        const sceneFormIdsNext: Record<number, number[]> = {}
        sortedRps.forEach((raw, i) => {
          if (raw.id != null && Number.isFinite(Number(raw.id))) sceneAssetIdsNext[i] = Number(raw.id)
          sceneFormIdsNext[i] = mapRpsFormsToAlignedFormIds(getFormsForRpsRow(raw))
        })
        const persistedManualSceneIndices = collectPersistedManualIndices('scene', sortedRps.length)
        const persistedManualSceneAssetIds = new Set(
          (ctx.store().manualSceneAssetIds || [])
            .filter((id) => Number.isFinite(Number(id)))
            .map((id) => Number(id))
        )
        const manualSceneIdx = buildManualIndexListFromRps(sortedRps, {
          persistedIndices: persistedManualSceneIndices,
          prevManualAssetIds: prevManualSceneAssetIds,
          persistedManualAssetIds: persistedManualSceneAssetIds
        })
        ctx.manualScenes.set(new Set(manualSceneIdx))
        ctx.patchStore({ manualScenes: manualSceneIdx })
        manualSceneIdx.forEach((i) => {
          const aid = sceneAssetIdsNext[i]
          if (aid != null && Number.isFinite(Number(aid))) {
            ctx.store().addManualSceneAssetId(Number(aid))
          }
        })
        ctx.sceneAssetIds.set(sceneAssetIdsNext)
        ctx.sceneFormIdsByIndex.set(sceneFormIdsNext)
        ctx.update({
          scenes: sceneNames,
          characters: [...ctx.localValue.get().characters],
          props: [...ctx.localValue.get().props]
        })
        applyRpsSettingsToLocalMaps('scene', sceneNames, sortedRps, ctx.sceneSettings)
        ctx.syncStep3AfterApiLoad()
        reconcilePendingExtractForTabFromRps('scene', sortedRps)
        ctx.reapplyFormImageGeneratingSlotsFromActiveIds('scene')
        return true
      }

      if (tab === 'character') {
        const prevManualCharacterAssetIds = new Set(
          Array.from(ctx.manualCharacters.get())
            .map((i) => ctx.characterAssetIds.get()[i])
            .filter((id): id is number => id != null && Number.isFinite(Number(id)))
            .map((id) => Number(id))
        )
        const characterNames = rows.map((r, i) => r.assetName || `角色${i + 1}`)
        const characterImagesNext: Record<number, any[]> = {}
        const characterFormImagesNext: Record<string, any[]> = {}
        sortedRps.forEach((raw, i) => {
          const rawForms = getFormsForRpsRow(raw)
          const date = raw.updateTime || raw.createTime || ''
          const assetImages: any[] = []
          for (let fi = 0; fi < rawForms.length; fi++) {
            const f = rawForms[fi]
            if (f.id == null || !Number.isFinite(Number(f.id))) continue
            const formId = Number(f.id)
            const inUse = inUseImagesFromRpsForm(f)
            const mapped = inUse
              .map((img) => {
                const url = String(img?.imageUrl || '').trim()
                if (!url) return null
                const imageId = Number(img?.id)
                const sourceType = String(img?.sourceType || '').trim()
                return {
                  id: Number.isFinite(imageId) ? `img-${imageId}` : `form-${formId}`,
                  rpsFormId: formId,
                  ...(Number.isFinite(imageId) ? { rpsImageId: imageId } : {}),
                  title: String(img?.name || f.name || `形态图${fi + 1}`),
                  url,
                  thumbnail: url,
                  source: 'server',
                  importDate: date,
                  ...(sourceType ? { sourceType, _serverSourceType: sourceType } : {})
                }
              })
              .filter(Boolean) as any[]
            if (mapped.length > 0) {
              characterFormImagesNext[`${i}-${fi}`] = mapped
              assetImages.push(...mapped)
              continue
            }
            const fallbackUrl = resolveLegacyFormImageUrlFallback(f)
            if (!fallbackUrl) continue
            const fallback = {
              id: `form-${formId}`,
              rpsFormId: formId,
              title: f.name?.trim() || `形态图${fi + 1}`,
              url: fallbackUrl,
              thumbnail: fallbackUrl,
              source: 'server',
              importDate: date
            }
            characterFormImagesNext[`${i}-${fi}`] = [fallback]
            assetImages.push(fallback)
          }
          if (assetImages.length) characterImagesNext[i] = assetImages
        })
        /**
         * 主表已存在但从表 forms 仍为空时（新建角色尚未上传形态图），与 addCharacter 本地态一致，
         * 保留一个「形态1：未命名」占位，避免刷新后整块形态区消失。
         */
        const charFormsNext: Record<number, CharacterFormItem[]> = {}
        for (let i = 0; i < sortedRps.length; i++) {
          const rawForms = getFormsForRpsRow(sortedRps[i])
          const voiceFields = roleVoiceBindingToFormFields(sortedRps[i].voiceBinding)
          if (rawForms.length > 0) {
            charFormsNext[i] = rawForms.map((f, idx) => ({
              ...mapRpsFormToLocalFormFields(f, idx, 'character'),
              ...voiceFields
            }))
          } else {
            charFormsNext[i] = [
              {
                name: '形态1: 未命名',
                canAutoGenerateImage: false,
                createSource: 'manual',
                ...voiceFields
              }
            ]
          }
        }
        ctx.characterForms.set(charFormsNext)
        ctx.characterImages.set(characterImagesNext)
        ctx.characterFormImages.set(characterFormImagesNext)
        const nextCharacterFormGenStatus: Record<string, FormGenStatus> = {
          ...ctx.store().characterFormGenerationStatus
        }
        Object.keys(charFormsNext).forEach((ck) => {
          const ci = Number(ck)
          const forms = charFormsNext[ci] ?? []
          const rawForms = getFormsForRpsRow(sortedRps[ci])
          forms.forEach((_, fi) => {
            const key = `${ci}-${fi}`
            const hasImage =
              Array.isArray(characterFormImagesNext[key]) && characterFormImagesNext[key]!.length > 0
            const formId = rawForms[fi]?.id
            const underFormImageGen =
              formId != null && ctx.isFormIdUnderActiveFormImageGeneration(Number(formId))
            const slotImageIds = hasImage
              ? collectRpsImageIdsFromImageRows(characterFormImagesNext[key]!)
              : []
            const settingCardBusy = slotImageIds.some(
              (id) => ctx.settingCardGenBusyByImageId.get()[id]
            )
            nextCharacterFormGenStatus[key] = ctx.resolveAssetListReconcileGenStatus({
              hasImage,
              underFormImageGen: settingCardBusy || underFormImageGen,
              previousStatus: nextCharacterFormGenStatus[key]
            })
          })
        })
        ctx.characterFormGenerationStatus.set(nextCharacterFormGenStatus)
        Object.entries(nextCharacterFormGenStatus).forEach(([key, status]) => {
          ctx.store().setCharacterFormGenerationStatus(key, status)
        })
        const characterAssetIdsNext: Record<number, number> = {}
        const characterFormIdsNext: Record<number, number[]> = {}
        sortedRps.forEach((raw, i) => {
          if (raw.id != null && Number.isFinite(Number(raw.id)))
            characterAssetIdsNext[i] = Number(raw.id)
          characterFormIdsNext[i] = mapRpsFormsToAlignedFormIds(getFormsForRpsRow(raw))
        })
        const persistedManualCharacterIndices = collectPersistedManualIndices(
          'character',
          sortedRps.length
        )
        const manualCharIdx = buildManualIndexListFromRps(sortedRps, {
          persistedIndices: persistedManualCharacterIndices,
          prevManualAssetIds: prevManualCharacterAssetIds
        })
        ctx.manualCharacters.set(new Set(manualCharIdx))
        ctx.patchStore({ manualCharacters: manualCharIdx })
        ctx.characterAssetIds.set(characterAssetIdsNext)
        ctx.characterFormIdsByIndex.set(characterFormIdsNext)
        ctx.update({
          scenes: [...ctx.localValue.get().scenes],
          characters: characterNames,
          props: [...ctx.localValue.get().props]
        })
        applyRpsSettingsToLocalMaps('character', characterNames, sortedRps, ctx.characterSettings)
        ctx.syncStep3AfterApiLoad()
        reconcilePendingExtractForTabFromRps('character', sortedRps)
        ctx.reapplyFormImageGeneratingSlotsFromActiveIds('character')
        return true
      }

      const prevManualPropAssetIds = new Set(
        Array.from(ctx.manualProps.get())
          .map((i) => ctx.propAssetIds.get()[i])
          .filter((id): id is number => id != null && Number.isFinite(Number(id)))
          .map((id) => Number(id))
      )
      const propNames = rows.map((r, i) => r.assetName || `道具${i + 1}`)
      const propImagesNext: Record<number, any[]> = {}
      const propFormImagesNext: Record<string, any[]> = {}
      sortedRps.forEach((raw, i) => {
        const rawForms = getFormsForRpsRow(raw)
        const date = raw.updateTime || raw.createTime || ''
        const assetImages: any[] = []
        for (let fi = 0; fi < rawForms.length; fi++) {
          const f = rawForms[fi]
          if (f.id == null || !Number.isFinite(Number(f.id))) continue
          const formId = Number(f.id)
          const inUse = inUseImagesFromRpsForm(f)
          const mapped = inUse
            .map((img) => {
              const url = String(img?.imageUrl || '').trim()
              if (!url) return null
              const imageId = Number(img?.id)
              const sourceType = String(img?.sourceType || '').trim()
              return {
                id: Number.isFinite(imageId) ? `img-${imageId}` : `form-${formId}`,
                rpsFormId: formId,
                ...(Number.isFinite(imageId) ? { rpsImageId: imageId } : {}),
                title: String(img?.name || f.name || `形态图${fi + 1}`),
                url,
                thumbnail: url,
                source: 'server',
                importDate: date,
                ...(sourceType ? { sourceType, _serverSourceType: sourceType } : {})
              }
            })
            .filter(Boolean) as any[]
          if (mapped.length > 0) {
            propFormImagesNext[`${i}-${fi}`] = mapped
            assetImages.push(...mapped)
            continue
          }
          const fallbackUrl = resolveLegacyFormImageUrlFallback(f)
          if (!fallbackUrl) continue
          const fallback = {
            id: `form-${formId}`,
            rpsFormId: formId,
            title: f.name?.trim() || `形态图${fi + 1}`,
            url: fallbackUrl,
            thumbnail: fallbackUrl,
            source: 'server',
            importDate: date
          }
          propFormImagesNext[`${i}-${fi}`] = [fallback]
          assetImages.push(fallback)
        }
        if (assetImages.length) propImagesNext[i] = assetImages
      })
      /** 同角色：主表存在、forms 为空时保留默认空形态槽，与 addProp 刷新前表现一致 */
      const propFormsNext: Record<number, PropFormItem[]> = {}
      for (let i = 0; i < sortedRps.length; i++) {
        const rawForms = getFormsForRpsRow(sortedRps[i])
        if (rawForms.length > 0) {
          propFormsNext[i] = rawForms.map((f, idx) =>
            mapRpsFormToLocalFormFields(f, idx, 'prop')
          )
        } else {
          propFormsNext[i] = [
            { name: '形态1: 未命名', canAutoGenerateImage: false, createSource: 'manual' }
          ]
        }
      }
      ctx.propForms.set(propFormsNext)
      ctx.propImages.set(propImagesNext)
      ctx.propFormImages.set(propFormImagesNext)
      const nextPropFormGenStatus: Record<string, FormGenStatus> = {
        ...ctx.store().propFormGenerationStatus
      }
      Object.keys(propFormsNext).forEach((pk) => {
        const pi = Number(pk)
        const forms = propFormsNext[pi] ?? []
        const rawForms = getFormsForRpsRow(sortedRps[pi])
        forms.forEach((_, fi) => {
          const key = `${pi}-${fi}`
          const hasImage =
            Array.isArray(propFormImagesNext[key]) && propFormImagesNext[key]!.length > 0
          const formId = rawForms[fi]?.id
          const underFormImageGen =
            formId != null && ctx.isFormIdUnderActiveFormImageGeneration(Number(formId))
          nextPropFormGenStatus[key] = ctx.resolveAssetListReconcileGenStatus({
            hasImage,
            underFormImageGen,
            previousStatus: nextPropFormGenStatus[key]
          })
        })
      })
      ctx.propFormGenerationStatus.set(nextPropFormGenStatus)
      Object.entries(nextPropFormGenStatus).forEach(([key, status]) => {
        ctx.store().setPropFormGenerationStatus(key, status)
      })
      const propAssetIdsNext: Record<number, number> = {}
      const propFormIdsNext: Record<number, number[]> = {}
      sortedRps.forEach((raw, i) => {
        if (raw.id != null && Number.isFinite(Number(raw.id))) propAssetIdsNext[i] = Number(raw.id)
        propFormIdsNext[i] = mapRpsFormsToAlignedFormIds(getFormsForRpsRow(raw))
      })
      const persistedManualPropIndices = collectPersistedManualIndices('prop', sortedRps.length)
      const manualPropIdx = buildManualIndexListFromRps(sortedRps, {
        persistedIndices: persistedManualPropIndices,
        prevManualAssetIds: prevManualPropAssetIds
      })
      ctx.manualProps.set(new Set(manualPropIdx))
      ctx.patchStore({ manualProps: manualPropIdx })
      ctx.propAssetIds.set(propAssetIdsNext)
      ctx.propFormIdsByIndex.set(propFormIdsNext)
      ctx.update({
        scenes: [...ctx.localValue.get().scenes],
        characters: [...ctx.localValue.get().characters],
        props: propNames
      })
      applyRpsSettingsToLocalMaps('prop', propNames, sortedRps, ctx.propSettings)
      ctx.syncStep3AfterApiLoad()
      reconcilePendingExtractForTabFromRps('prop', sortedRps)
      ctx.reapplyFormImageGeneratingSlotsFromActiveIds('prop')
      return true
    } catch (e: unknown) {
      if (trackGlobalGen && gen !== ctx.loadAssetTabGeneration) return false
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载资产列表失败')
      return false
    } finally {
      if (!options?.background && tabGen === ctx.tabAssetLoadGen[tab]) {
        setTabAssetLoading(tab, false)
      }
    }
  }
  return { loadPersonalAssetsForTab }
}
