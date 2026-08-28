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
getCharacterName,
getCharacterPrefix,
reindexAssetIdMap,
reindexFormGenerationStatusMap,
reindexFormIdsByIndexMap
} from './scpRowUtils'
import type { CharacterFormItem,ScpCtx } from './types'
import { rpsDeleteOrphanFormsOnly,rpsDeleteWholeAsset } from './useScpRpsOps'
import { settingEditorStateFromRpsRow } from './scpSettingPromptUtils'

export function createScpCharacterAssetCrudOps(ctx: ScpCtx) {
  const addCharacter = async () => {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) {
      message.warning('缺少项目或剧集信息，请从「我的作品」进入创作流程后再添加')
      return
    }
    try {
      const pendingName = `角色${ctx.localValue.get().characters.length + 1}: 未命名`
      let row = await userAssetRpsCreate({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        name: pendingName,
        assetType: 'character'
      })
      // 与场景/道具保持一致：若主资产返回没有形态，则补建默认形态。
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
            err?.msg || err?.message || '角色形态初始化失败，可稍后点击「新增形态」继续'
          )
        }
      }
      const newIndex = ctx.localValue.get().characters.length
      const name = row.assetName || pendingName
      ctx.update({ ...ctx.localValue.get(), characters: [...ctx.localValue.get().characters, name] })
      const nextManualCharacters = new Set(ctx.manualCharacters.get())
      nextManualCharacters.add(newIndex)
      ctx.manualCharacters.set(nextManualCharacters)
      ctx.store().addManualCharacter(newIndex)
      ctx.characterForms.set({
        ...ctx.characterForms.get(),
        [newIndex]: [{ name: '形态1: 未命名', canAutoGenerateImage: false, createSource: 'manual' }]
      })
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [`${newIndex}-0`]: 'idle'
      })
      ctx.store().setCharacterFormGenerationStatus(`${newIndex}-0`, 'idle')
      ctx.characterSettings.set({
        ...ctx.characterSettings.get(),
        [name]: {
          ...settingEditorStateFromRpsRow(row, 'character'),
          isNew: true
        }
      })
      const imgs = buildImagesFromAssetRow(rpsRowToUserAssetRow(row))
      if (imgs.length) {
        ctx.characterImages.set({ ...ctx.characterImages.get(), [newIndex]: imgs })
      }
      if (row.id != null && Number.isFinite(Number(row.id))) {
        const assetId = Number(row.id)
        ctx.characterAssetIds.set({ ...ctx.characterAssetIds.get(), [newIndex]: assetId })
        await ctx.syncAssetFormIdsFromServer('character', newIndex, assetId, row)
      }
      ctx.scrollToManualAddedAsset('character', newIndex)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '创建角色失败')
    }
  }

  const removeCharacter = (idx: number) => {
    Modal.confirm({
      title: '确认删除角色及相关内容?',
      content: '将同时删除角色图与相关内容。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const aid = ctx.characterAssetIds.get()[idx]
        const formIds = ctx.characterFormIdsByIndex.get()[idx] ?? []
        try {
          if (aid != null && Number.isFinite(Number(aid))) {
            await rpsDeleteWholeAsset(aid)
          } else if (formIds.length > 0) {
            await rpsDeleteOrphanFormsOnly(formIds)
          }
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除角色失败')
          throw e
        }

        ctx.update({
          ...ctx.localValue.get(),
          characters: ctx.localValue.get().characters.filter((_, i) => i !== idx)
        })

        // 更新手动角色索引
        const newManualCharacters = new Set<number>()
        ctx.manualCharacters.get().forEach((index) => {
          if (index < idx) {
            newManualCharacters.add(index)
          } else if (index > idx) {
            newManualCharacters.add(index - 1)
          }
        })
        ctx.manualCharacters.set(newManualCharacters)
        ctx.patchStore({ manualCharacters: Array.from(newManualCharacters) })

        // 删除角色形态
        if (ctx.characterForms.get()[idx]) {
          const cur = { ...ctx.characterForms.get() }
          delete cur[idx]
          // 重新索引角色形态
          const newCharacterForms: Record<number, CharacterFormItem[]> = {}
          Object.keys(cur).forEach((key) => {
            const oldIndex = Number(key)
            if (oldIndex < idx) {
              newCharacterForms[oldIndex] = cur[oldIndex]
            } else if (oldIndex > idx) {
              newCharacterForms[oldIndex - 1] = cur[oldIndex]
            }
          })
          ctx.characterForms.set(newCharacterForms)
        }

        // 删除角色图片
        if (ctx.characterImages.get()[idx]) {
          const cur = { ...ctx.characterImages.get() }
          delete cur[idx]
          // 重新索引角色图片
          const newCharacterImages: Record<number, any[]> = {}
          Object.keys(cur).forEach((key) => {
            const oldIndex = Number(key)
            if (oldIndex < idx) {
              newCharacterImages[oldIndex] = cur[oldIndex]
            } else if (oldIndex > idx) {
              newCharacterImages[oldIndex - 1] = cur[oldIndex]
            }
          })
          ctx.characterImages.set(newCharacterImages)
        }

        // 删除角色形态图片（需要重新索引）
        const newCharacterFormImages: Record<string, any[]> = {}
        Object.keys(ctx.characterFormImages.get()).forEach((key) => {
          const [charIdx] = key.split('-').map(Number)
          if (charIdx < idx) {
            newCharacterFormImages[key] = ctx.characterFormImages.get()[key]
          } else if (charIdx > idx) {
            const newKey = `${charIdx - 1}-${key.split('-')[1]}`
            newCharacterFormImages[newKey] = ctx.characterFormImages.get()[key]
          }
          // charIdx === idx 的情况，直接删除，不添加到新对象中
        })
        ctx.characterFormImages.set(newCharacterFormImages)

        ctx.characterAssetIds.set(reindexAssetIdMap(ctx.characterAssetIds.get(), idx))
        ctx.characterFormIdsByIndex.set(
          reindexFormIdsByIndexMap(ctx.characterFormIdsByIndex.get(), idx)
        )
        ctx.characterFormGenerationStatus.set(
          reindexFormGenerationStatusMap(ctx.characterFormGenerationStatus.get(), idx)
        )
        ctx.patchStore({
          characterFormGenerationStatus: { ...ctx.characterFormGenerationStatus.get() }
        })
        ctx.store().syncStep3GenVisualToCurrentScope()

        message.success('角色及相关内容已删除')
      }
    })
  }

  const startEditCharacterName = (index: number, currentName: string) => {
    ctx.editingCharacterIndex.set(index)
    ctx.editingCharacterName.set(getCharacterName(currentName))
  }

  const handleCharacterNameBlur = async (index: number) => {
    if (ctx.editingCharacterIndex.get() !== index || !ctx.editingCharacterName.get().trim()) {
      ctx.editingCharacterIndex.set(null)
      ctx.editingCharacterName.set('')
      return
    }
    const prefix = getCharacterPrefix(ctx.localValue.get().characters[index])
    const newName = prefix
      ? `${prefix} ${ctx.editingCharacterName.get().trim()}`
      : ctx.editingCharacterName.get().trim()
    const oldName = ctx.localValue.get().characters[index]
    if (newName === oldName) {
      ctx.editingCharacterIndex.set(null)
      ctx.editingCharacterName.set('')
      return
    }

    const assetId = ctx.characterAssetIds.get()[index]
    if (assetId != null) {
      try {
        await userAssetRpsUpdateMain({ id: assetId, name: newName })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '角色名称同步失败')
        ctx.editingCharacterIndex.set(null)
        ctx.editingCharacterName.set('')
        return
      }
    }

    const newCharacters = [...ctx.localValue.get().characters]
    newCharacters[index] = newName
    ctx.update({ ...ctx.localValue.get(), characters: newCharacters })
    if (ctx.characterSettings.get()[oldName]) {
      const nextSettings = { ...ctx.characterSettings.get() }
      nextSettings[newName] = nextSettings[oldName]
      delete nextSettings[oldName]
      ctx.characterSettings.set(nextSettings)
    }
    message.success(assetId != null ? '角色名称已更新' : '角色名称已更新（仅本地）')
    ctx.editingCharacterIndex.set(null)
    ctx.editingCharacterName.set('')
  }

  return {
    addCharacter,
    handleCharacterNameBlur,
    removeCharacter,
    startEditCharacterName,
  }
}
