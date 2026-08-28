'use client'

import { message } from 'antd'
import type { ModelOption } from '~/components/steps/ModelSelectDropdown'
import { useModelList } from '~/composables/useModelList'
import { useModelGenerateSettings } from '~/composables/useModelGenerateSettings'
import { mapUserModelListItemToModelOption } from '~/utils/userModelOption'
import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import {
  fetchAgentDefaultModelCodes,
  getAgentDefaultModelCacheKey,
  FORM_IMAGE_AGENT_BIZ_CATEGORY,
  resolvePreferredModelId,
  resolveSelectedModelOption,
  IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY,
  IMAGE_MULTI_VIEW_AGENT_CODE
} from '~/utils/extractAgentBiz'
import { modelsFromListByFuncGroups, uniqueTrimmedCodes } from '~/utils/modelListByFuncBatch'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import { userModelListByFuncCodes } from '~/utils/businessApi'
import type { AssetExtractType, UserModelListItem } from '~/types/business-api'
import type { SelectOption } from '~/utils/modelCapability'
import type { EditSceneImageModalCtx } from './types'
import { useMirrored, type Mirrored } from './useMirrored'

export interface SceneModalGenerationSettings {
  model: string
  aspectRatio: string
  count: number
  quality: string
}

export interface SceneModalModelsApi {
  generationSettings: Mirrored<SceneModalGenerationSettings>
  /** 右侧「对话作图」Tab 的模型与出图参数（须在 selectedDialogueModel / useModelGenerateSettings 之前声明） */
  dialogueSettings: Mirrored<SceneModalGenerationSettings>
  multiViewSettings: Mirrored<{ model: string }>
  modelDropdownExpanded: Mirrored<boolean>
  dialogueModelDropdownExpanded: Mirrored<boolean>
  multiViewModelDropdownExpanded: Mirrored<boolean>
  modelOptions: ModelOption[]
  dialogueModelOptions: ModelOption[]
  multiViewModelOptions: ModelOption[]
  /** 变清晰：listByFunc(image_upscale) 模型池，供 UpscaleModelPopover 复用 */
  upscaleModelPool: Mirrored<UserModelListItem[]>
  initImageModelOptions: () => Promise<void>
  selectedModel: () => ModelOption
  selectedDialogueModel: () => ModelOption
  multiViewSelectedModel: () => ModelOption
  editAspectRatioSelectOptions: SelectOption<string>[]
  editCountSelectOptions: SelectOption<number>[]
  editQualitySelectOptions: SelectOption<string>[]
  dialogueAspectRatioSelectOptions: SelectOption<string>[]
  dialogueCountSelectOptions: SelectOption<number>[]
  dialogueQualitySelectOptions: SelectOption<string>[]
  handleSelectModel: (model: ModelOption) => void
  handleSelectDialogueModel: (model: ModelOption) => void
  handleSelectMultiViewModel: (model: ModelOption) => void
  activeAspectRatio: () => string
  setActiveAspectRatio: (value: string) => void
  activeCount: () => number
  setActiveCount: (value: number) => void
  activeQuality: () => string
  setActiveQuality: (value: string) => void
  generationCost: () => number
}

// 模型选项列表
const fallbackModelOptions: ModelOption[] = [

]

const fallbackMultiViewModelOptions: ModelOption[] = [
  {
    id: 'wan2.7-image',
    name: '万相 2.7',
    iconBg: '#60A5FA',
    desc: '多机位形态生图',
    prices: []
  }
]

const mapSceneModalModelItem = (item: Parameters<typeof mapUserModelListItemToModelOption>[0]): ModelOption =>
  mapUserModelListItemToModelOption(item, { iconBg: '#10B981' })

export function useSceneModalModels(ctx: EditSceneImageModalCtx): SceneModalModelsApi {
  const generationSettings = useMirrored<SceneModalGenerationSettings>({
    model: '',
    aspectRatio: '16:9',
    count: 1,
    quality: '2k'
  })

  /** 右侧「对话作图」Tab 的模型与出图参数（须在 selectedDialogueModel / useModelGenerateSettings 之前声明） */
  const dialogueSettings = useMirrored<SceneModalGenerationSettings>({
    model: '',
    aspectRatio: '16:9',
    count: 1,
    quality: '2k'
  })

  // 模型选择相关
  const modelDropdownExpanded = useMirrored(false)
  const dialogueModelDropdownExpanded = useMirrored(false)

  const {
    modelList: modelOptions,
    setModelList: setModelOptions,
    getModelList: getModelOptions,
    setRawModelList,
    getRawModelList
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.SCENE_IMAGE,
    modelType: 'image',
    fallback: fallbackModelOptions,
    mapItem: mapSceneModalModelItem,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载生图模型失败，已使用默认模型')
    }
  })

  const {
    modelList: dialogueModelOptions,
    setModelList: setDialogueModelOptions,
    getModelList: getDialogueModelOptions,
    setRawModelList: setDialogueRawModelList,
    getRawModelList: getDialogueRawModelList
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_EDIT,
    modelType: 'image',
    fallback: fallbackModelOptions,
    mapItem: mapSceneModalModelItem,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载对话作图模型失败，已使用默认模型')
    }
  })

  const {
    modelList: multiViewModelOptions,
    setModelList: setMultiViewModelOptions,
    getModelList: getMultiViewModelOptions
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW,
    modelType: 'image',
    fallback: fallbackMultiViewModelOptions,
    mapItem: (item) => mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' }),
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载多机位模型失败，已使用默认模型')
    }
  })

  const multiViewSettings = useMirrored<{ model: string }>({ model: '' })
  const multiViewModelDropdownExpanded = useMirrored(false)

  /** 变清晰：listByFunc(image_upscale) 模型池，供 UpscaleModelPopover 复用 */
  const upscaleModelPool = useMirrored<UserModelListItem[]>([])

  /** 主场景/角色/道具生图：main_scene_image / main_character_image / main_prop_image */
  function applySceneModalImageModelPool(
    groups: Awaited<ReturnType<typeof userModelListByFuncCodes>>,
    assetType: AssetExtractType
  ) {
    const imageFuncCode = FORM_IMAGE_AGENT_BIZ_CATEGORY[assetType]
    const list = modelsFromListByFuncGroups(groups, imageFuncCode)
    if (list.length > 0) {
      setRawModelList(list)
      setModelOptions(list.map(mapSceneModalModelItem))
      return true
    }
    return false
  }

  /** 对话作图：image_edit */
  function applySceneModalDialogueModelPool(
    groups: Awaited<ReturnType<typeof userModelListByFuncCodes>>
  ) {
    const list = modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_EDIT)
    if (list.length > 0) {
      setDialogueRawModelList(list)
      setDialogueModelOptions(list.map(mapSceneModalModelItem))
      return true
    }
    return false
  }

  function applySceneModalMultiViewModelPool(
    groups: Awaited<ReturnType<typeof userModelListByFuncCodes>>
  ) {
    const list = modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW)
    if (list.length > 0) {
      setMultiViewModelOptions(
        list.map((item) => mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' }))
      )
      return true
    }
    return false
  }

  async function initImageModelOptions() {
    const assetType = ctx.resolveSceneModalAssetType()
    const agentCode = String(ctx.store().extractAgents[assetType]?.id || '').trim()
    const formImageBiz = FORM_IMAGE_AGENT_BIZ_CATEGORY[assetType]
    const funcCodes = uniqueTrimmedCodes([
      formImageBiz,
      AI_MODEL_FUNC_CODE.IMAGE_EDIT,
      AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW,
      AI_MODEL_FUNC_CODE.IMAGE_UPSCALE
    ])
    const listScope = buildAidAgentListScopeParams(ctx.store())
    const agentPayloads = [
      { bizCategoryCode: formImageBiz, agentCode, ...listScope },
      {
        bizCategoryCode: IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY,
        agentCode: IMAGE_MULTI_VIEW_AGENT_CODE,
        ...listScope
      }
    ]

    const [agentCodes, modelGroups] = await Promise.all([
      fetchAgentDefaultModelCodes(agentPayloads),
      userModelListByFuncCodes(funcCodes, listScope)
    ])

    // 批量 listByFunc 已请求过各池；空结果不再用不同入参单码重打
    if (!applySceneModalImageModelPool(modelGroups, assetType)) {
      setRawModelList([])
      setModelOptions([])
    }
    if (!applySceneModalDialogueModelPool(modelGroups)) {
      setDialogueRawModelList([])
      setDialogueModelOptions([])
    }
    if (!applySceneModalMultiViewModelPool(modelGroups)) {
      setMultiViewModelOptions([])
    }

    upscaleModelPool.set(modelsFromListByFuncGroups(modelGroups, AI_MODEL_FUNC_CODE.IMAGE_UPSCALE))

    const agentDefaultModelCode =
      agentCodes[getAgentDefaultModelCacheKey(formImageBiz, agentCode, listScope)] || ''
    const multiViewAgentDefault =
      agentCodes[
        getAgentDefaultModelCacheKey(
          IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY,
          IMAGE_MULTI_VIEW_AGENT_CODE,
          listScope
        )
      ] || ''

    generationSettings.set({
      ...generationSettings.get(),
      model: resolvePreferredModelId(getModelOptions(), {
        agentDefaultCode: agentDefaultModelCode
      })
    })
    dialogueSettings.set({
      ...dialogueSettings.get(),
      model: resolvePreferredModelId(getDialogueModelOptions(), {
        agentDefaultCode: agentDefaultModelCode
      })
    })
    syncEditSettingsToModel()
    syncDialogueSettingsToModel()
    multiViewSettings.set({
      model: resolvePreferredModelId(getMultiViewModelOptions(), {
        agentDefaultCode: multiViewAgentDefault
      })
    })
  }

  const selectedModel = (): ModelOption =>
    resolveSelectedModelOption(getModelOptions(), generationSettings.get().model)

  const selectedDialogueModel = (): ModelOption =>
    resolveSelectedModelOption(getDialogueModelOptions(), dialogueSettings.get().model)

  const {
    aspectRatioSelectOptions: editAspectRatioSelectOptions,
    countSelectOptions: editCountSelectOptionsRaw,
    qualitySelectOptions: editQualitySelectOptions,
    syncSettingsToModel: syncEditSettingsToModel
  } = useModelGenerateSettings({
    getSelectedModel: selectedModel,
    getRawModelList,
    getGenerationSettings: () => {
      const s = generationSettings.get()
      return { aspectRatio: s.aspectRatio, count: s.count, quality: s.quality }
    },
    setGenerationSettings: (v) => {
      generationSettings.set({
        ...generationSettings.get(),
        aspectRatio: v.aspectRatio,
        count: v.count,
        quality: v.quality
      })
    },
    include3k: true
  })

  /** 接口限制 imageCount 为 1~4 */
  const editCountSelectOptions = (() => {
    const capped = editCountSelectOptionsRaw.filter((o) => o.value >= 1 && o.value <= 4)
    if (capped.length) return capped
    return [
      { value: 1, label: '1张' },
      { value: 2, label: '2张' },
      { value: 3, label: '3张' },
      { value: 4, label: '4张' }
    ]
  })()

  const {
    aspectRatioSelectOptions: dialogueAspectRatioSelectOptions,
    countSelectOptions: dialogueCountSelectOptionsRaw,
    qualitySelectOptions: dialogueQualitySelectOptions,
    syncSettingsToModel: syncDialogueSettingsToModel
  } = useModelGenerateSettings({
    getSelectedModel: selectedDialogueModel,
    getRawModelList: getDialogueRawModelList,
    getGenerationSettings: () => {
      const s = dialogueSettings.get()
      return { aspectRatio: s.aspectRatio, count: s.count, quality: s.quality }
    },
    setGenerationSettings: (v) => {
      dialogueSettings.set({
        ...dialogueSettings.get(),
        aspectRatio: v.aspectRatio,
        count: v.count,
        quality: v.quality
      })
    },
    include3k: true
  })

  const dialogueCountSelectOptions = (() => {
    const capped = dialogueCountSelectOptionsRaw.filter((o) => o.value >= 1 && o.value <= 4)
    if (capped.length) return capped
    return [
      { value: 1, label: '1张' },
      { value: 2, label: '2张' },
      { value: 3, label: '3张' },
      { value: 4, label: '4张' }
    ]
  })()

  const handleSelectModel = (model: ModelOption) => {
    generationSettings.set({ ...generationSettings.get(), model: model.id })
    modelDropdownExpanded.set(false)
    syncEditSettingsToModel()
  }

  const handleSelectDialogueModel = (model: ModelOption) => {
    dialogueSettings.set({ ...dialogueSettings.get(), model: model.id })
    dialogueModelDropdownExpanded.set(false)
    syncDialogueSettingsToModel()
  }

  const handleSelectMultiViewModel = (model: ModelOption) => {
    multiViewSettings.set({ model: model.id })
    multiViewModelDropdownExpanded.set(false)
  }

  const multiViewSelectedModel = (): ModelOption =>
    resolveSelectedModelOption(getMultiViewModelOptions(), multiViewSettings.get().model)

  const generationCost = () => {
    const costMap: Record<string, number> = {
      '1k': 1,
      '2k': 2,
      '4k': 4
    }
    return (costMap[generationSettings.get().quality] || 2) * generationSettings.get().count
  }

  const activeAspectRatio = () =>
    ctx.leftActiveTab.get() === 'generate'
      ? generationSettings.get().aspectRatio
      : dialogueSettings.get().aspectRatio
  const setActiveAspectRatio = (value: string) => {
    if (ctx.leftActiveTab.get() === 'generate') {
      generationSettings.set({ ...generationSettings.get(), aspectRatio: value })
    } else {
      dialogueSettings.set({ ...dialogueSettings.get(), aspectRatio: value })
    }
  }

  const activeCount = () =>
    ctx.leftActiveTab.get() === 'generate' ? generationSettings.get().count : dialogueSettings.get().count
  const setActiveCount = (value: number) => {
    if (ctx.leftActiveTab.get() === 'generate') {
      generationSettings.set({ ...generationSettings.get(), count: value })
    } else {
      dialogueSettings.set({ ...dialogueSettings.get(), count: value })
    }
  }

  const activeQuality = () =>
    ctx.leftActiveTab.get() === 'generate' ? generationSettings.get().quality : dialogueSettings.get().quality
  const setActiveQuality = (value: string) => {
    if (ctx.leftActiveTab.get() === 'generate') {
      generationSettings.set({ ...generationSettings.get(), quality: value })
    } else {
      dialogueSettings.set({ ...dialogueSettings.get(), quality: value })
    }
  }

  return {
    generationSettings,
    dialogueSettings,
    multiViewSettings,
    modelDropdownExpanded,
    dialogueModelDropdownExpanded,
    multiViewModelDropdownExpanded,
    modelOptions,
    dialogueModelOptions,
    multiViewModelOptions,
    upscaleModelPool,
    initImageModelOptions,
    selectedModel,
    selectedDialogueModel,
    multiViewSelectedModel,
    editAspectRatioSelectOptions,
    editCountSelectOptions,
    editQualitySelectOptions,
    dialogueAspectRatioSelectOptions,
    dialogueCountSelectOptions,
    dialogueQualitySelectOptions,
    handleSelectModel,
    handleSelectDialogueModel,
    handleSelectMultiViewModel,
    activeAspectRatio,
    setActiveAspectRatio,
    activeCount,
    setActiveCount,
    activeQuality,
    setActiveQuality,
    generationCost
  }
}
