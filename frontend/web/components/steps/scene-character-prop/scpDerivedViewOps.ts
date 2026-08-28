'use client'

import { htmlPlainTextLength,isHtmlContentEmpty } from '~/utils/htmlPlain'
import { isStep3ListEditImageDisabledForSlot } from '~/utils/step3EditImageGate'
import { formAllowsAutoGenerateImage } from './scpRowUtils'
import { formatStep3TabTaskProgressText } from './scpTaskUtils'
import type { ScpCtx,TabKey } from './types'

export function createScpDerivedViewOps(ctx: ScpCtx) {
  const isManualScene = (index: number) => ctx.manualScenes.get().has(index)
  const isManualCharacter = (index: number) => ctx.manualCharacters.get().has(index)
  const isManualProp = (index: number) => ctx.manualProps.get().has(index)

  function canAutoGenerateCharacterFormImage(characterIndex: number, formIndex: number): boolean {
    if (isManualCharacter(characterIndex)) return false
    return formAllowsAutoGenerateImage(ctx.characterForms.get()[characterIndex]?.[formIndex])
  }

  function canAutoGeneratePropFormImage(propIndex: number, formIndex: number): boolean {
    if (isManualProp(propIndex)) return false
    return formAllowsAutoGenerateImage(ctx.propForms.get()[propIndex]?.[formIndex])
  }

  /** 角色形态：列表 generating 或该形态仍有进行中生图任务 → 禁用编辑形态图 */
  function isCharacterFormEditImageDisabled(characterIndex: number, formIndex: number): boolean {
    const key = `${characterIndex}-${formIndex}`
    return isStep3ListEditImageDisabledForSlot({
      status: ctx.characterFormGenerationStatus.get()[key],
      formIdUnderActiveGen: ctx.characterSlotHasActiveFormImageGeneration(key)
    })
  }

  /** 道具形态：同上 */
  function isPropFormEditImageDisabled(propIndex: number, formIndex: number): boolean {
    const key = `${propIndex}-${formIndex}`
    return isStep3ListEditImageDisabledForSlot({
      status: ctx.propFormGenerationStatus.get()[key],
      formIdUnderActiveGen: ctx.propSlotHasActiveFormImageGeneration(key)
    })
  }

  /** 场景主卡是否允许「自动生成」（手动添加的场景禁用） */
  function canAutoGenerateSceneImage(sceneIndex: number): boolean {
    return !isManualScene(sceneIndex)
  }

  function resolveSingleExtractTabLock(
    stages?: { scene: boolean; character: boolean; prop: boolean } | null
  ): TabKey | null {
    if (!stages) return null
    const active = (['scene', 'character', 'prop'] as const).filter((k) => stages[k])
    return active.length === 1 ? active[0]! : null
  }

  function shouldFollowExtractStageForActiveTab(): boolean {
    return ctx.singleExtractTabLock == null
  }

  const hasStory = () => htmlPlainTextLength(ctx.props().storyScriptContent ?? '') > 0

  const extractingStageLabel = () => {
    if (ctx.props().extractingStage === 'scene') return '场景'
    if (ctx.props().extractingStage === 'character') return '角色'
    return '道具'
  }

  /** 提取进行中：主文案优先 SSE 的 message，其次 stepTitle，最后兜底句式 */
  const extractingLiveTitle = () => {
    const p = ctx.store().extractingTaskProgress
    const msg = String(p?.message || '').trim()
    const step = String(p?.stepTitle || '').trim()
    const live = msg || step
    return live || `正在为您提取${extractingStageLabel()}...`
  }

  const isExtracting = () => ctx.props().isExtracting
  const extractingProgressText = () => {
    const text = formatStep3TabTaskProgressText(ctx.step3TabTaskProgress.get()[ctx.activeTab.get()])
    if (text) return text
    const hasOngoingForTab = Object.values(ctx.step3TaskIdToTab.get()).includes(ctx.activeTab.get())
    return hasOngoingForTab ? '任务进行中…' : ''
  }
  const extractingStages = () =>
    ctx.props().extractingStages || {
      scene: false,
      character: false,
      prop: false
    }

  /** 共用空状态：标题与说明随当前 Tab 切换 */
  const emptyAssetTitle = () => {
    if (ctx.activeTab.get() === 'scene') return `场景（${ctx.localValue.get().scenes.length}）`
    if (ctx.activeTab.get() === 'character') return `角色（${ctx.localValue.get().characters.length}）`
    return `道具（${ctx.localValue.get().props.length}）`
  }

  const emptyAssetAddLabel = () => {
    if (ctx.activeTab.get() === 'scene') return '添加场景'
    if (ctx.activeTab.get() === 'character') return '添加角色'
    return '添加道具'
  }

  /** 空状态：提示文案随 Tab（场景/角色/道具）切换 */
  const emptyExtractTips = () => {
    if (ctx.activeTab.get() === 'scene') return '点击此按钮，为您智能提取场景'
    if (ctx.activeTab.get() === 'character') return '点击此按钮，为您智能提取角色'
    return '点击此按钮，为您智能提取道具'
  }

  /** 自动生成/同步进行中，或当前 Tab 尚无提取结果时不可手动添加 */
  const topbarAddDisabled = () => {
    if (isExtracting() || ctx.store().isExtractingAssets) return true
    if (!ctx.step3AssetBootstrapReady.get()) return true
    if (ctx.activeTab.get() === 'scene') return ctx.localValue.get().scenes.length === 0
    if (ctx.activeTab.get() === 'character') return ctx.localValue.get().characters.length === 0
    return ctx.localValue.get().props.length === 0
  }

  /** 当前 Tab 下「待生成形态」小卡片 */
  const activeTabPendingFormCards = () => {
    if (ctx.activeTab.get() === 'scene') return pendingSceneFormCards()
    if (ctx.activeTab.get() === 'character') return pendingCharacterFormCards()
    return pendingPropFormCards()
  }

  const showBatchGenerateTopbarBtn = () => {
    if (ctx.activeTab.get() === 'scene') return ctx.localValue.get().scenes.length > 0
    if (ctx.activeTab.get() === 'character') return ctx.localValue.get().characters.length > 0
    return ctx.localValue.get().props.length > 0
  }

  const batchFormGenerateMenuLabel = () => {
    if (ctx.activeTab.get() === 'scene') return '批量生成场景形态'
    if (ctx.activeTab.get() === 'character') return '批量生成角色形态'
    return '批量生成道具形态'
  }

  const batchImageGenerateMenuLabel = () => {
    if (ctx.activeTab.get() === 'scene') return '批量生成场景图'
    if (ctx.activeTab.get() === 'character') return '批量生成角色图'
    return '批量生成道具图'
  }

  /** 当前 Tab 下是否仍有待生成形态的资产（批量删除后重新提取会恢复为 true） */
  const batchFormGenerateAlreadyGenerated = () => activeTabPendingFormCards().length === 0

  const batchFormGenerateBusyDisabled = () => {
    if (batchFormGenerateAlreadyGenerated()) return false
    if (ctx.batchFormGenerateSubmitting.get()) return true
    const cards = activeTabPendingFormCards()
    return cards.length > 0 && cards.every((c) => !!ctx.pendingFormGenBusy.get()[c.assetId])
  }

  const batchFormGenerateMenuDisabled = () =>
    batchFormGenerateAlreadyGenerated() || batchFormGenerateBusyDisabled()

  const batchImageGenerateMenuDisabled = () => {
    if (ctx.activeTab.get() === 'scene') return ctx.localValue.get().scenes.length === 0
    if (ctx.activeTab.get() === 'character') return ctx.localValue.get().characters.length === 0
    return ctx.localValue.get().props.length === 0
  }

  const batchDeleteMenuDisabled = () => batchImageGenerateMenuDisabled()

  const batchFormGenerateDisabledTooltip = () => {
    if (batchFormGenerateAlreadyGenerated()) return '当前已经生成过形态无法再次生成'
    if (batchFormGenerateBusyDisabled()) return '资产形态正在生成中，请稍候'
    return ''
  }

  const batchGenerateTopbarLoading = () => {
    if (ctx.batchFormGenerateSubmitting.get()) return true
    if (ctx.batchCardGenerateSubmitting.get() || ctx.isSettingCardBatchBusy()) return true
    return activeTabPendingFormCards().some((c) => !!ctx.pendingFormGenBusy.get()[c.assetId])
  }

  const batchCardGenerateMenuDisabled = () =>
    ctx.activeTab.get() !== 'character' ||
    ctx.batchCardGenerateSubmitting.get() ||
    ctx.isSettingCardBatchBusy() ||
    ctx.localValue.get().characters.length === 0

  /** 空状态「自动提取」主按钮文案（随 Tab 切换） */
  const autoExtractEmptyButtonLabel = () => {
    if (ctx.activeTab.get() === 'scene') return '自动提取场景'
    if (ctx.activeTab.get() === 'character') return '自动提取角色'
    return '自动提取道具'
  }

  /** 场景列表（全部项，使用原手动区 UI） */
  const manualScenesList = () => {
    return ctx.localValue.get().scenes.map((name, index) => ({ name, index }))
  }

  /** 角色列表（全部项，使用原手动区 UI） */
  const manualCharactersList = () => {
    return ctx.localValue.get().characters.map((name, index) => ({ name, index }))
  }

  /** 道具列表（全部项，使用原手动区 UI） */
  const manualPropsList = () => {
    return ctx.localValue.get().props.map((name, index) => ({ name, index }))
  }

  const pendingSceneFormCards = () =>
    ctx.store().pendingExtractFormAssets.filter((x) => x.assetType === 'scene')
  const pendingCharacterFormCards = () =>
    ctx.store().pendingExtractFormAssets.filter((x) => x.assetType === 'character')
  const pendingPropFormCards = () =>
    ctx.store().pendingExtractFormAssets.filter((x) => x.assetType === 'prop')

  /** 已在「待生成形态」小卡片中的资产不再渲染下方完整行，避免重复 */
  const visibleManualScenesList = () => {
    const pending = new Set(pendingSceneFormCards().map((x) => x.assetId))
    return manualScenesList().filter((s) => {
      const formIds = ctx.sceneFormIdsByIndex.get()[s.index] ?? []
      if (formIds.some((id) => Number.isFinite(Number(id)) && Number(id) > 0)) return true
      const aid = ctx.sceneAssetIds.get()[s.index]
      return !(aid != null && Number.isFinite(Number(aid)) && pending.has(Number(aid)))
    })
  }

  const visibleManualCharactersList = () => {
    const pending = new Set(pendingCharacterFormCards().map((x) => x.assetId))
    return manualCharactersList().filter((c) => {
      const formIds = ctx.characterFormIdsByIndex.get()[c.index] ?? []
      if (formIds.some((id) => Number.isFinite(Number(id)) && Number(id) > 0)) return true
      const aid = ctx.characterAssetIds.get()[c.index]
      return !(aid != null && Number.isFinite(Number(aid)) && pending.has(Number(aid)))
    })
  }

  const visibleManualPropsList = () => {
    const pending = new Set(pendingPropFormCards().map((x) => x.assetId))
    return manualPropsList().filter((p) => {
      const formIds = ctx.propFormIdsByIndex.get()[p.index] ?? []
      if (formIds.some((id) => Number.isFinite(Number(id)) && Number(id) > 0)) return true
      const aid = ctx.propAssetIds.get()[p.index]
      return !(aid != null && Number.isFinite(Number(aid)) && pending.has(Number(aid)))
    })
  }

  // 是否有场景正在生成
  const isGeneratingScene = () => {
    return Object.values(ctx.sceneGenerationStatus.get()).some((status) => status === 'generating')
  }

  const isGeneratingCharacterForm = () =>
    Object.values(ctx.characterFormGenerationStatus.get()).some((s) => s === 'generating')

  const isGeneratingPropForm = () =>
    Object.values(ctx.propFormGenerationStatus.get()).some((s) => s === 'generating')

  const isAnyStep3VisualGenerating = () =>
    isGeneratingScene() || isGeneratingCharacterForm() || isGeneratingPropForm()

  /** 初始化/切作品/刷新同步未完成且无已验证的进行中任务时展示「正在同步…」遮罩 */
  const showAssetBootstrapMask = () =>
    !ctx.step3AssetBootstrapReady.get() && !ctx.hasOngoingStep3VisualWork()

  /** 切换 Tab 拉取列表时的 loading；仅在该 Tab 尚无数据时展示，避免遮住已有列表 */
  const showActiveTabAssetLoading = () => {
    if (!ctx.step3AssetBootstrapReady.get() || ctx.props().isExtracting) return false
    if (!ctx.tabAssetLoading.get()[ctx.activeTab.get()]) return false
    if (ctx.activeTab.get() === 'scene') return ctx.localValue.get().scenes.length === 0
    if (ctx.activeTab.get() === 'character') return ctx.localValue.get().characters.length === 0
    return ctx.localValue.get().props.length === 0
  }

  const activeTabAssetLoadingText = () => {
    if (ctx.activeTab.get() === 'scene') return '正在加载场景列表…'
    if (ctx.activeTab.get() === 'character') return '正在加载角色列表…'
    return '正在加载道具列表…'
  }

  /** 初始化同步与 Tab 懒加载共用同一套全屏遮罩文案 */
  const assetLoadingMaskText = () =>
    showAssetBootstrapMask() ? '正在同步场景、角色与道具…' : activeTabAssetLoadingText()

  const batchGenerateDefaultModelCode = () => {
    if (ctx.batchGenerateMode.get() === 'setting-card') return ''
    return ctx.store().extractImageModelCodes[ctx.batchGenerateType.get()]
  }

  const batchGenerateItems = () => {
    const mode = ctx.batchGenerateMode.get()
    const type = ctx.batchGenerateType.get()
    if (mode === 'form') {
      return ctx.activeTabPendingFormCards().map((card) => {
        const index =
          card.assetType === 'scene'
            ? ctx.findSceneIndexByAssetId(card.assetId)
            : card.assetType === 'character'
              ? ctx.findCharacterIndexByAssetId(card.assetId)
              : ctx.findPropIndexByAssetId(card.assetId)
        const images =
          index < 0
            ? []
            : card.assetType === 'scene'
              ? ctx.sceneImages.get()[index] || []
              : ctx.collectInUseFormImagesForAsset(card.assetType, index)
        const busy = Boolean(ctx.pendingFormGenBusy.get()[card.assetId])
        return {
          id: card.assetId,
          name: card.title,
          images,
          billingIds: [card.assetId],
          selectable: !busy,
          disabledTooltip: busy ? '该资产形态正在生成中，请稍候' : ''
        }
      })
    }
    if (mode === 'delete') {
      const names =
        type === 'scene'
          ? ctx.localValue.get().scenes
          : type === 'character'
            ? ctx.localValue.get().characters
            : ctx.localValue.get().props
      const assetIds =
        type === 'scene'
          ? ctx.sceneAssetIds.get()
          : type === 'character'
            ? ctx.characterAssetIds.get()
            : ctx.propAssetIds.get()
      return names.map((name, index) => {
        const assetId = Number(assetIds[index])
        const saved = Number.isFinite(assetId) && assetId > 0
        const images =
          type === 'scene'
            ? ctx.sceneImages.get()[index] || []
            : ctx.collectInUseFormImagesForAsset(type, index)
        return {
          id: saved ? assetId : `unsaved-${type}-${index}`,
          name,
          images,
          selectable: saved,
          disabledTooltip: saved ? '' : '该资产尚未保存到服务器，无法批量删除'
        }
      })
    }
    if (ctx.batchGenerateMode.get() === 'setting-card') {
      return ctx.localValue.get().characters.flatMap((name, index) => {
        if (isManualCharacter(index)) return []
        const whiteBase = ctx.findFirstWhiteBaseImageForCharacter(index)
        const { imageIds: billingIds } =
          ctx.collectCharacterWhiteBaseImageIdsForSelectedCharacters([index])
        return [
          {
            id: `character-${index}`,
            name,
            images: whiteBase ? [whiteBase] : [],
            billingIds,
            settingCardReady: ctx.characterHasWhiteBaseImageForSettingCard(index)
          }
        ]
      })
    }
    if (ctx.batchGenerateType.get() === 'scene') {
      return ctx.localValue.get().scenes.flatMap((name, index) => {
        if (isManualScene(index)) return []
        return [
          {
            id: `scene-${index}`,
            name,
            images: ctx.sceneImages.get()[index] || [],
            billingIds: (ctx.sceneFormIdsByIndex.get()[index] || [])
              .map(Number)
              .filter((id) => Number.isFinite(id) && id > 0),
            hasSetting: !isHtmlContentEmpty(ctx.sceneSettings.get()[name]?.content || '')
          }
        ]
      })
    }
    if (ctx.batchGenerateType.get() === 'character') {
      return ctx.localValue.get().characters.flatMap((name, index) => {
        if (isManualCharacter(index)) return []
        return [
          {
            id: `character-${index}`,
            name,
            images: ctx.collectInUseFormImagesForAsset('character', index),
            billingIds: (ctx.characterFormIdsByIndex.get()[index] || [])
              .map(Number)
              .filter((id) => Number.isFinite(id) && id > 0),
            hasSetting: !isHtmlContentEmpty(ctx.characterSettings.get()[name]?.content || '')
          }
        ]
      })
    }
    return ctx.localValue.get().props.flatMap((name, index) => {
      if (isManualProp(index)) return []
      return [
        {
          id: `prop-${index}`,
          name,
          images: ctx.collectInUseFormImagesForAsset('prop', index),
          billingIds: (ctx.propFormIdsByIndex.get()[index] || [])
            .map(Number)
            .filter((id) => Number.isFinite(id) && id > 0),
          hasSetting: !isHtmlContentEmpty(ctx.propSettings.get()[name]?.content || '')
        }
      ]
    })
  }

  return {
    activeTabAssetLoadingText,
    activeTabPendingFormCards,
    assetLoadingMaskText,
    autoExtractEmptyButtonLabel,
    batchCardGenerateMenuDisabled,
    batchDeleteMenuDisabled,
    batchFormGenerateAlreadyGenerated,
    batchFormGenerateBusyDisabled,
    batchFormGenerateDisabledTooltip,
    batchFormGenerateMenuDisabled,
    batchFormGenerateMenuLabel,
    batchGenerateDefaultModelCode,
    batchGenerateItems,
    batchGenerateTopbarLoading,
    batchImageGenerateMenuDisabled,
    batchImageGenerateMenuLabel,
    canAutoGenerateCharacterFormImage,
    canAutoGeneratePropFormImage,
    canAutoGenerateSceneImage,
    emptyAssetAddLabel,
    emptyAssetTitle,
    emptyExtractTips,
    extractingLiveTitle,
    extractingProgressText,
    extractingStageLabel,
    extractingStages,
    hasStory,
    isAnyStep3VisualGenerating,
    isCharacterFormEditImageDisabled,
    isExtracting,
    isGeneratingCharacterForm,
    isGeneratingPropForm,
    isGeneratingScene,
    isManualCharacter,
    isManualProp,
    isManualScene,
    isPropFormEditImageDisabled,
    manualCharactersList,
    manualPropsList,
    manualScenesList,
    pendingCharacterFormCards,
    pendingPropFormCards,
    pendingSceneFormCards,
    resolveSingleExtractTabLock,
    shouldFollowExtractStageForActiveTab,
    showActiveTabAssetLoading,
    showAssetBootstrapMask,
    showBatchGenerateTopbarBtn,
    topbarAddDisabled,
    visibleManualCharactersList,
    visibleManualPropsList,
    visibleManualScenesList,
  }
}
