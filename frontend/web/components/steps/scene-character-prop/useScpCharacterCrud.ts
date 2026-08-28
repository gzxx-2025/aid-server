'use client'

import { message, Modal } from 'antd'
import {
  userAssetRpsCreate,
  userAssetRpsFormCreate,
  userAssetRpsUpdateForm,
  userRoleVoiceBind,
  rpsRowToUserAssetRow
} from '~/utils/businessApi'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
  applyVoiceFieldsToCharacterForms,
  roleVoiceBindingToFormFields
} from '~/utils/characterVoiceBinding'
import {
  buildImagesFromAssetRow,
  getCharacterName,
  getCharacterPrefix,
  getFormName,
  getFormPrefix,
  reindexAssetIdMap,
  reindexFormGenerationStatusMap,
  reindexFormIdsByIndexMap
} from './scpRowUtils'
import { rpsDeleteOrphanFormsOnly, rpsDeleteSingleForm, rpsDeleteWholeAsset } from './useScpRpsOps'
import { createScpCharacterAssetCrudOps } from './scpCharacterAssetCrudOps'
import type { CharacterFormItem, ScpCtx } from './types'
import { saveRpsSettingPrompt } from './scpSettingPromptUtils'

export interface ScpCharacterCrudApi {
  addCharacter: () => Promise<void>
  removeCharacter: (idx: number) => void
  startEditCharacterName: (index: number, currentName: string) => void
  handleCharacterNameBlur: (index: number) => Promise<void>
  handleEditCharacterFormSetting: (characterIndex: number, formIndex: number) => void
  handleSaveCharacterFormSetting: (content: string) => Promise<void>
  startEditFormName: (characterIndex: number, formIndex: number, currentName: string) => void
  handleFormNameBlur: (characterIndex: number, formIndex: number) => Promise<void>
  handleAddCharacterForm: (characterIndex: number) => Promise<void>
  handleCopyCharacterForm: (characterIndex: number, formIndex: number) => void
  handleDeleteCharacterForm: (characterIndex: number, formIndex: number) => void
  openVoiceTimbrePicker: (characterIndex: number, formIndex: number) => void
  stopVoicePreview: () => void
  handleVoiceTimbreConfirm: (payload: {
    name: string
    avatarUrl: string
    id: string
    previewUrl: string
    voiceLibraryId?: number
  }) => Promise<void>
  toggleVoicePreview: (characterIndex: number, formIndex: number) => Promise<void>
  handleVoicePreviewEnded: () => void
  handleVoicePreviewPaused: () => void
}

export function useScpCharacterCrud(ctx: ScpCtx): ScpCharacterCrudApi {
  const {
    addCharacter,
    removeCharacter,
    startEditCharacterName,
    handleCharacterNameBlur
  } = createScpCharacterAssetCrudOps(ctx)

  const handleEditCharacterFormSetting = (characterIndex: number, formIndex: number) => {
    const form = ctx.characterForms.get()[characterIndex]?.[formIndex]
    if (!form) {
      message.error('形态信息不存在，请刷新后重试')
      return
    }
    const formId = ctx.characterFormIdsByIndex.get()[characterIndex]?.[formIndex]
    if (!form.setting) {
      const nextForms = { ...ctx.characterForms.get() }
      nextForms[characterIndex] = nextForms[characterIndex].map((item, index) =>
        index === formIndex
          ? {
              ...item,
              setting: {
                content: '',
                isNew: true,
                ...(formId ? { formId } : {}),
                createSource: item.createSource ?? null
              }
            }
          : item
      )
      ctx.characterForms.set(nextForms)
    }
    ctx.currentCharacterIndex.set(characterIndex)
    ctx.currentCharacterSettingFormIndex.set(formIndex)
    ctx.showCharacterSettingModal.set(true)
  }

  const handleSaveCharacterFormSetting = async (content: string) => {
    const characterIndex = ctx.currentCharacterIndex.get()
    const formIndex = ctx.currentCharacterSettingFormIndex.get()
    const form = ctx.characterForms.get()[characterIndex]?.[formIndex]
    if (!form) return
    try {
      const updatedSetting = await saveRpsSettingPrompt(
        'character',
        form.setting,
        content
      )
      const nextForms = { ...ctx.characterForms.get() }
      nextForms[characterIndex] = nextForms[characterIndex].map((item, index) =>
        index === formIndex ? { ...item, setting: updatedSetting } : item
      )
      ctx.characterForms.set(nextForms)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '角色形态提示词同步失败')
      return
    }
    ctx.showCharacterSettingModal.set(false)
    message.success('角色形态图设定已保存并同步')
  }

  const startEditFormName = (characterIndex: number, formIndex: number, currentName: string) => {
    ctx.editingFormIndex.set(`${characterIndex}-${formIndex}`)
    ctx.editingFormName.set(getFormName(currentName))
  }

  const handleFormNameBlur = async (characterIndex: number, formIndex: number) => {
    if (
      ctx.editingFormIndex.get() !== `${characterIndex}-${formIndex}` ||
      !ctx.editingFormName.get().trim()
    ) {
      ctx.editingFormIndex.set(null)
      ctx.editingFormName.set('')
      return
    }
    const prev = ctx.characterForms.get()[characterIndex][formIndex].name
    const prefix = getFormPrefix(prev)
    const newName = prefix
      ? `${prefix} ${ctx.editingFormName.get().trim()}`
      : ctx.editingFormName.get().trim()
    if (newName === prev) {
      ctx.editingFormIndex.set(null)
      ctx.editingFormName.set('')
      return
    }

    const assetId = ctx.characterAssetIds.get()[characterIndex]
    if (assetId != null) {
      const formId = ctx.ensureFormIdForRpsUpdate('character', characterIndex, formIndex)
      if (formId == null) {
        message.error(
          '无法同步形态名称：请先在「编辑形态图」弹窗中上传、从资产库导入或通过 AI 生成图片以创建形态'
        )
        ctx.editingFormIndex.set(null)
        ctx.editingFormName.set('')
        return
      }
      try {
        await userAssetRpsUpdateForm({ id: formId, name: newName })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '形态名称同步失败')
        ctx.editingFormIndex.set(null)
        ctx.editingFormName.set('')
        return
      }
    }

    const nextForms = { ...ctx.characterForms.get() }
    nextForms[characterIndex] = nextForms[characterIndex].map((f, i) =>
      i === formIndex ? { ...f, name: newName } : f
    )
    ctx.characterForms.set(nextForms)
    message.success(assetId != null ? '形态名称已更新' : '形态名称已更新（仅本地）')
    ctx.editingFormIndex.set(null)
    ctx.editingFormName.set('')
  }

  const handleAddCharacterForm = async (characterIndex: number) => {
    const curForms = ctx.characterForms.get()[characterIndex] ?? []
    const formCount = curForms.length + 1
    const formName = `形态${formCount}: 未命名`
    const aid = ctx.characterAssetIds.get()[characterIndex]
    if (aid != null && Number.isFinite(Number(aid))) {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (saveCtx) {
        try {
          const row = await userAssetRpsFormCreate({
            projectId: saveCtx.projectId,
            episodeId: saveCtx.episodeId,
            assetId: Number(aid),
            imageUrl: '',
            name: formName,
            sourceType: 'official'
          })
          ctx.applyRpsRowFormIds('character', characterIndex, row)
          if (aid != null)
            await ctx.syncAssetFormIdsFromServer('character', characterIndex, Number(aid), row)
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '新增形态失败')
          return
        }
      }
    }
    const fi = (ctx.characterForms.get()[characterIndex] ?? []).length
    ctx.characterForms.set({
      ...ctx.characterForms.get(),
      [characterIndex]: [
        ...(ctx.characterForms.get()[characterIndex] ?? []),
        {
          name: formName,
          voiceover: undefined,
          voiceoverId: undefined,
          voiceoverAvatarUrl: undefined,
          voiceoverPreviewUrl: undefined,
          canAutoGenerateImage: false,
          createSource: 'manual',
          setting: {
            content: '',
            isNew: true,
            ...(ctx.characterFormIdsByIndex.get()[characterIndex]?.[fi]
              ? { formId: ctx.characterFormIdsByIndex.get()[characterIndex]![fi] }
              : {}),
            createSource: 'manual'
          }
        }
      ]
    })
    const formKey = `${characterIndex}-${fi}`
    ctx.characterFormGenerationStatus.set({
      ...ctx.characterFormGenerationStatus.get(),
      [formKey]: 'idle'
    })
    ctx.store().setCharacterFormGenerationStatus(formKey, 'idle')
    message.success('形态已添加')
  }

  const handleCopyCharacterForm = (characterIndex: number, formIndex: number) => {
    const form = ctx.characterForms.get()[characterIndex][formIndex]
    const newForm = {
      name: form.name.replace(/形态\d+/, () => {
        return `形态${ctx.characterForms.get()[characterIndex].length + 1}`
      }),
      voiceover: form.voiceover,
      voiceoverId: form.voiceoverId,
      voiceoverAvatarUrl: form.voiceoverAvatarUrl,
      voiceoverPreviewUrl: form.voiceoverPreviewUrl,
      canAutoGenerateImage: false,
      createSource: 'manual',
      setting: { content: form.setting?.content || '', isNew: true, createSource: 'manual' }
    }
    ctx.characterForms.set({
      ...ctx.characterForms.get(),
      [characterIndex]: [...ctx.characterForms.get()[characterIndex], newForm]
    })
    message.success('形态已复制')
  }

  const handleDeleteCharacterForm = (characterIndex: number, formIndex: number) => {
    Modal.confirm({
      title: '确认删除形态?',
      content: '删除后将无法恢复。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const aid = ctx.characterAssetIds.get()[characterIndex]
        const formIds = ctx.characterFormIdsByIndex.get()[characterIndex] ?? []
        const fid = formIds[formIndex]
        try {
          if (aid != null && fid != null) {
            await rpsDeleteSingleForm(aid, fid)
          } else if (aid != null) {
            message.warning('未找到服务端形态 ID，已仅从界面移除')
          }
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除形态失败')
          throw e
        }

        const nextList = [...ctx.characterForms.get()[characterIndex]]
        nextList.splice(formIndex, 1)
        const renamed = nextList.map((form, index) => {
          const match = form.name.match(/^(形态\d+):\s*(.+)$/)
          if (match) {
            return { ...form, name: `形态${index + 1}: ${match[2]}` }
          }
          return form
        })
        ctx.characterForms.set({ ...ctx.characterForms.get(), [characterIndex]: renamed })

        const newIds = [...formIds]
        newIds.splice(formIndex, 1)
        ctx.characterFormIdsByIndex.set({
          ...ctx.characterFormIdsByIndex.get(),
          [characterIndex]: newIds
        })

        const next: Record<string, any[]> = {}
        for (const k of Object.keys(ctx.characterFormImages.get())) {
          const [c, f] = k.split('-').map(Number)
          if (c !== characterIndex) {
            next[k] = ctx.characterFormImages.get()[k]
          } else if (f === formIndex) {
            continue
          } else if (f > formIndex) {
            next[`${c}-${f - 1}`] = ctx.characterFormImages.get()[k]
          } else {
            next[k] = ctx.characterFormImages.get()[k]
          }
        }
        ctx.characterFormImages.set(next)

        message.success('形态已删除')
      }
    })
  }

  const openVoiceTimbrePicker = (characterIndex: number, formIndex: number) => {
    const form = ctx.characterForms.get()[characterIndex]?.[formIndex]
    if (!form) return
    ctx.currentVoiceCharacterIndex.set(characterIndex)
    ctx.currentVoiceFormIndex.set(formIndex)
    ctx.voicePickerInitialName.set(form.voiceover || '')
    ctx.showVoiceTimbrePickerModal.set(true)
  }

  const stopVoicePreview = () => {
    const audio = ctx.voicePreviewAudioRef.current
    if (audio) {
      audio.pause()
      audio.src = ''
    }
    ctx.playingVoicePreviewKey.set(null)
  }

  const handleVoiceTimbreConfirm = async (payload: {
    name: string
    avatarUrl: string
    id: string
    previewUrl: string
    voiceLibraryId?: number
  }) => {
    const characterIndex = ctx.currentVoiceCharacterIndex.get()
    const formIndex = ctx.currentVoiceFormIndex.get()
    const forms = ctx.characterForms.get()[characterIndex]
    const targetForm = forms?.[formIndex]
    if (!targetForm || !forms?.length) return

    const localVoiceFields = roleVoiceBindingToFormFields({
      voiceLibraryId: payload.voiceLibraryId ?? Number(payload.id),
      voiceName: payload.name,
      avatarUrl: payload.avatarUrl,
      sampleUrl: payload.previewUrl
    })
    stopVoicePreview()

    const assetId = ctx.characterAssetIds.get()[characterIndex]
    const voiceLibraryId = payload.voiceLibraryId ?? Number(payload.id)
    if (assetId == null || !Number.isFinite(Number(assetId))) {
      applyVoiceFieldsToCharacterForms(forms, localVoiceFields)
      // 原地写入 forms 后强制触发渲染（Vue 深响应式的 React 等价）
      ctx.characterForms.set({ ...ctx.characterForms.get() })
      message.success(`已选择配音：${payload.name}`)
      message.warning('角色尚未同步到服务端，刷新后配音可能无法保留')
      return
    }
    if (!Number.isFinite(voiceLibraryId) || voiceLibraryId <= 0) {
      message.error('音色 ID 无效')
      return
    }

    try {
      const binding = await userRoleVoiceBind({
        assetId: Number(assetId),
        voiceLibraryId: Number(voiceLibraryId)
      })
      const voiceFields = roleVoiceBindingToFormFields(binding)
      applyVoiceFieldsToCharacterForms(forms, voiceFields)
      // 原地写入 forms 后强制触发渲染（Vue 深响应式的 React 等价）
      ctx.characterForms.set({ ...ctx.characterForms.get() })
      message.success(`已选择配音：${voiceFields.voiceover || payload.name}`)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '配音绑定失败')
    }
  }

  const toggleVoicePreview = async (characterIndex: number, formIndex: number) => {
    const form = ctx.characterForms.get()[characterIndex]?.[formIndex]
    if (!form?.voiceover) return
    const previewUrl = String(
      form.voiceoverPreviewUrl ||
        'https://interactive-examples.mdn.mozilla.net/media/cc0-audio/t-rex-roar.mp3'
    ).trim()
    if (!previewUrl) {
      message.warning('该配音暂无试听音频')
      return
    }
    const key = `${characterIndex}-${formIndex}`
    const audio = ctx.voicePreviewAudioRef.current
    if (!audio) return

    if (ctx.playingVoicePreviewKey.get() === key) {
      stopVoicePreview()
      return
    }

    stopVoicePreview()
    ctx.playingVoicePreviewKey.set(key)
    audio.src = previewUrl
    try {
      await audio.play()
    } catch {
      ctx.playingVoicePreviewKey.set(null)
      message.warning('试听失败，请稍后重试')
    }
  }

  const handleVoicePreviewEnded = () => {
    ctx.playingVoicePreviewKey.set(null)
  }

  const handleVoicePreviewPaused = () => {
    const audio = ctx.voicePreviewAudioRef.current
    if (!audio || audio.ended) return
    if (!audio.src) ctx.playingVoicePreviewKey.set(null)
  }

  return {
    addCharacter,
    removeCharacter,
    startEditCharacterName,
    handleCharacterNameBlur,
    handleEditCharacterFormSetting,
    handleSaveCharacterFormSetting,
    startEditFormName,
    handleFormNameBlur,
    handleAddCharacterForm,
    handleCopyCharacterForm,
    handleDeleteCharacterForm,
    openVoiceTimbrePicker,
    stopVoicePreview,
    handleVoiceTimbreConfirm,
    toggleVoicePreview,
    handleVoicePreviewEnded,
    handleVoicePreviewPaused
  }
}
