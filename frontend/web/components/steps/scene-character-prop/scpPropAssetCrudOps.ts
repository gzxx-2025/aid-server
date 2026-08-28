'use client'

import { message,Modal } from 'antd'
import {
rpsRowToUserAssetRow,
userAssetRpsCreate,
userAssetRpsFormCreate,
userAssetRpsUpdateMain
} from '~/utils/businessApi'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
buildImagesFromAssetRow,
getCharacterPrefix,
getPropName,
getPropPrefix,
getScenePrefix,
reindexAssetIdMap,
reindexFormGenerationStatusMap,
reindexFormIdsByIndexMap
} from './scpRowUtils'
import type { PendingFormCardItem,PropFormItem,ScpCtx } from './types'
import { rpsDeleteOrphanFormsOnly,rpsDeleteWholeAsset } from './useScpRpsOps'
import { settingEditorStateFromRpsRow } from './scpSettingPromptUtils'

export function createScpPropAssetCrudOps(ctx: ScpCtx) {
  const addProp = async () => {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) {
      message.warning('缺少项目或剧集信息，请从「我的作品」进入创作流程后再添加')
      return
    }
    try {
      const pendingName = `道具${ctx.localValue.get().props.length + 1}: 未命名`
      let row = await userAssetRpsCreate({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        name: pendingName,
        assetType: 'prop'
      })
      // 与场景/角色保持一致：若主资产返回没有形态，则补建默认形态。
      if ((row.forms ?? []).length === 0 && row.id != null && Number.isFinite(Number(row.id))) {
        try {
          row = await userAssetRpsFormCreate({
            projectId: saveCtx.projectId,
            episodeId: saveCtx.episodeId,
            assetId: Number(row.id),
            imageUrl: '',
            name: '形态1: 未命名',
            sourceType: 'official'
          })
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.warning(
            err?.msg || err?.message || '道具形态初始化失败，可稍后点击「新增形态」继续'
          )
        }
      }
      const newIndex = ctx.localValue.get().props.length
      const name = row.assetName || pendingName
      ctx.update({ ...ctx.localValue.get(), props: [...ctx.localValue.get().props, name] })
      const nextManualProps = new Set(ctx.manualProps.get())
      nextManualProps.add(newIndex)
      ctx.manualProps.set(nextManualProps)
      ctx.store().addManualProp(newIndex)
      ctx.propForms.set({
        ...ctx.propForms.get(),
        [newIndex]: [{ name: '形态1: 未命名', canAutoGenerateImage: false, createSource: 'manual' }]
      })
      ctx.propFormGenerationStatus.set({
        ...ctx.propFormGenerationStatus.get(),
        [`${newIndex}-0`]: 'idle'
      })
      ctx.store().setPropFormGenerationStatus(`${newIndex}-0`, 'idle')
      ctx.propSettings.set({
        ...ctx.propSettings.get(),
        [name]: {
          ...settingEditorStateFromRpsRow(row, 'prop'),
          isNew: true
        }
      })
      const imgs = buildImagesFromAssetRow(rpsRowToUserAssetRow(row))
      if (imgs.length) {
        ctx.propImages.set({ ...ctx.propImages.get(), [newIndex]: imgs })
      }
      if (row.id != null && Number.isFinite(Number(row.id))) {
        const assetId = Number(row.id)
        ctx.propAssetIds.set({ ...ctx.propAssetIds.get(), [newIndex]: assetId })
        await ctx.syncAssetFormIdsFromServer('prop', newIndex, assetId, row)
      }
      ctx.scrollToManualAddedAsset('prop', newIndex)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '创建道具失败')
    }
  }

  const removeProp = (idx: number) => {
    Modal.confirm({
      title: '确认删除道具及相关内容?',
      content: '将同时删除道具图与相关内容。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const aid = ctx.propAssetIds.get()[idx]
        const formIds = ctx.propFormIdsByIndex.get()[idx] ?? []
        try {
          if (aid != null && Number.isFinite(Number(aid))) {
            await rpsDeleteWholeAsset(aid)
          } else if (formIds.length > 0) {
            await rpsDeleteOrphanFormsOnly(formIds)
          }
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除道具失败')
          throw e
        }

        ctx.update({
          ...ctx.localValue.get(),
          props: ctx.localValue.get().props.filter((_, i) => i !== idx)
        })

        // 更新手动道具索引
        const newManualProps = new Set<number>()
        ctx.manualProps.get().forEach((index) => {
          if (index < idx) {
            newManualProps.add(index)
          } else if (index > idx) {
            newManualProps.add(index - 1)
          }
        })
        ctx.manualProps.set(newManualProps)
        ctx.patchStore({ manualProps: Array.from(newManualProps) })

        // 删除道具形态
        if (ctx.propForms.get()[idx]) {
          const cur = { ...ctx.propForms.get() }
          delete cur[idx]
          // 重新索引道具形态
          const newPropForms: Record<number, PropFormItem[]> = {}
          Object.keys(cur).forEach((key) => {
            const oldIndex = Number(key)
            if (oldIndex < idx) {
              newPropForms[oldIndex] = cur[oldIndex]
            } else if (oldIndex > idx) {
              newPropForms[oldIndex - 1] = cur[oldIndex]
            }
          })
          ctx.propForms.set(newPropForms)
        }

        // 删除道具图片
        if (ctx.propImages.get()[idx]) {
          const cur = { ...ctx.propImages.get() }
          delete cur[idx]
          // 重新索引道具图片
          const newPropImages: Record<number, any[]> = {}
          Object.keys(cur).forEach((key) => {
            const oldIndex = Number(key)
            if (oldIndex < idx) {
              newPropImages[oldIndex] = cur[oldIndex]
            } else if (oldIndex > idx) {
              newPropImages[oldIndex - 1] = cur[oldIndex]
            }
          })
          ctx.propImages.set(newPropImages)
        }

        // 删除道具形态图片（需要重新索引）
        const newPropFormImages: Record<string, any[]> = {}
        Object.keys(ctx.propFormImages.get()).forEach((key) => {
          const [propIdx] = key.split('-').map(Number)
          if (propIdx < idx) {
            newPropFormImages[key] = ctx.propFormImages.get()[key]
          } else if (propIdx > idx) {
            const newKey = `${propIdx - 1}-${key.split('-')[1]}`
            newPropFormImages[newKey] = ctx.propFormImages.get()[key]
          }
          // propIdx === idx 的情况，直接删除，不添加到新对象中
        })
        ctx.propFormImages.set(newPropFormImages)

        ctx.propAssetIds.set(reindexAssetIdMap(ctx.propAssetIds.get(), idx))
        ctx.propFormIdsByIndex.set(reindexFormIdsByIndexMap(ctx.propFormIdsByIndex.get(), idx))
        ctx.propFormGenerationStatus.set(
          reindexFormGenerationStatusMap(ctx.propFormGenerationStatus.get(), idx)
        )
        ctx.patchStore({ propFormGenerationStatus: { ...ctx.propFormGenerationStatus.get() } })
        ctx.store().syncStep3GenVisualToCurrentScope()

        message.success('道具及相关内容已删除')
      }
    })
  }

  const startEditPropName = (index: number, currentName: string) => {
    ctx.editingPropIndex.set(index)
    ctx.editingPropName.set(getPropName(currentName))
  }

  const handlePropNameBlur = async (index: number) => {
    if (ctx.editingPropIndex.get() !== index || !ctx.editingPropName.get().trim()) {
      ctx.editingPropIndex.set(null)
      ctx.editingPropName.set('')
      return
    }
    const prefix = getPropPrefix(ctx.localValue.get().props[index])
    const newName = prefix
      ? `${prefix} ${ctx.editingPropName.get().trim()}`
      : ctx.editingPropName.get().trim()
    const oldName = ctx.localValue.get().props[index]
    if (newName === oldName) {
      ctx.editingPropIndex.set(null)
      ctx.editingPropName.set('')
      return
    }

    const assetId = ctx.propAssetIds.get()[index]
    if (assetId != null) {
      try {
        await userAssetRpsUpdateMain({ id: assetId, name: newName })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '道具名称同步失败')
        ctx.editingPropIndex.set(null)
        ctx.editingPropName.set('')
        return
      }
    }

    const newProps = [...ctx.localValue.get().props]
    newProps[index] = newName
    ctx.update({ ...ctx.localValue.get(), props: newProps })
    if (ctx.propSettings.get()[oldName]) {
      const nextSettings = { ...ctx.propSettings.get() }
      nextSettings[newName] = nextSettings[oldName]
      delete nextSettings[oldName]
      ctx.propSettings.set(nextSettings)
    }
    message.success(assetId != null ? '道具名称已更新' : '道具名称已更新（仅本地）')
    ctx.editingPropIndex.set(null)
    ctx.editingPropName.set('')
  }

  function startEditPendingFormCardTitle(card: PendingFormCardItem) {
    ctx.editingPendingFormCardKey.set(ctx.pendingFormCardEditKey(card))
    ctx.editingPendingFormTitle.set(ctx.pendingFormCardEditableSuffix(card))
  }

  async function handlePendingFormCardTitleBlur(card: PendingFormCardItem) {
    const key = ctx.pendingFormCardEditKey(card)
    if (ctx.editingPendingFormCardKey.get() !== key || !ctx.editingPendingFormTitle.get().trim()) {
      ctx.editingPendingFormCardKey.set(null)
      ctx.editingPendingFormTitle.set('')
      return
    }

    const trimmed = ctx.editingPendingFormTitle.get().trim()
    let idx = -1
    let prevFull = card.title
    let newFullName = ''

    if (card.assetType === 'scene') {
      idx = ctx.findSceneIndexByAssetId(card.assetId)
      if (idx >= 0) {
        prevFull = ctx.localValue.get().scenes[idx]
        const prefix = getScenePrefix(prevFull)
        newFullName = prefix ? `${prefix} ${trimmed}` : trimmed
      } else {
        newFullName = trimmed
        prevFull = card.title
      }
    } else if (card.assetType === 'character') {
      idx = ctx.findCharacterIndexByAssetId(card.assetId)
      if (idx >= 0) {
        prevFull = ctx.localValue.get().characters[idx]
        const prefix = getCharacterPrefix(prevFull)
        newFullName = prefix ? `${prefix} ${trimmed}` : trimmed
      } else {
        newFullName = trimmed
        prevFull = card.title
      }
    } else {
      idx = ctx.findPropIndexByAssetId(card.assetId)
      if (idx >= 0) {
        prevFull = ctx.localValue.get().props[idx]
        const prefix = getPropPrefix(prevFull)
        newFullName = prefix ? `${prefix} ${trimmed}` : trimmed
      } else {
        newFullName = trimmed
        prevFull = card.title
      }
    }

    if (newFullName === prevFull) {
      ctx.editingPendingFormCardKey.set(null)
      ctx.editingPendingFormTitle.set('')
      return
    }

    try {
      await userAssetRpsUpdateMain({ id: card.assetId, name: newFullName })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '名称同步失败')
      ctx.editingPendingFormCardKey.set(null)
      ctx.editingPendingFormTitle.set('')
      return
    }

    ctx.store().patchPendingExtractFormAssetTitle(card.assetId, card.assetType, newFullName)

    if (idx >= 0) {
      if (card.assetType === 'scene') {
        const newScenes = [...ctx.localValue.get().scenes]
        newScenes[idx] = newFullName
        ctx.update({ ...ctx.localValue.get(), scenes: newScenes })
        if (ctx.sceneSettings.get()[prevFull]) {
          const nextSettings = { ...ctx.sceneSettings.get() }
          nextSettings[newFullName] = nextSettings[prevFull]
          delete nextSettings[prevFull]
          ctx.sceneSettings.set(nextSettings)
        }
      } else if (card.assetType === 'character') {
        const newCharacters = [...ctx.localValue.get().characters]
        newCharacters[idx] = newFullName
        ctx.update({ ...ctx.localValue.get(), characters: newCharacters })
        if (ctx.characterSettings.get()[prevFull]) {
          const nextSettings = { ...ctx.characterSettings.get() }
          nextSettings[newFullName] = nextSettings[prevFull]
          delete nextSettings[prevFull]
          ctx.characterSettings.set(nextSettings)
        }
      } else {
        const newProps = [...ctx.localValue.get().props]
        newProps[idx] = newFullName
        ctx.update({ ...ctx.localValue.get(), props: newProps })
        if (ctx.propSettings.get()[prevFull]) {
          const nextSettings = { ...ctx.propSettings.get() }
          nextSettings[newFullName] = nextSettings[prevFull]
          delete nextSettings[prevFull]
          ctx.propSettings.set(nextSettings)
        }
      }
    }

    message.success('名称已更新')
    ctx.editingPendingFormCardKey.set(null)
    ctx.editingPendingFormTitle.set('')
  }

  return {
    addProp,
    handlePendingFormCardTitleBlur,
    handlePropNameBlur,
    removeProp,
    startEditPendingFormCardTitle,
    startEditPropName,
  }
}
