import { message } from 'antd'
import { useRouter } from 'next/navigation'
import { create } from 'zustand'
import { useCreationStore } from '~/stores/creation'
import type { GlobalSettingData } from '~/types'
import type { UserProjectCreateRequest } from '~/types/business-api'
import { buildProjectVideoStyleFields } from '~/utils/buildProjectVideoStyleFields'
import { userProjectCreate } from '~/utils/businessApi'
import { CREATE_FIRST_STEP_DEFAULTS } from '~/utils/createFirstStepDefaults'
import { CREATE_SERIES_SCRIPT_UPLOAD_PATH } from '~/utils/createFlowRoutes'

interface HomeShellCreateModalState {
  showCreateFirstStepModal: boolean
  createConfirmLoading: boolean
  /** 为 true 时弹窗左侧作品类型跟随父级（我的作品页按 tab）；首页我要创作为 false，固定默认剧集 */
  syncProjectTypeFromParent: boolean
  creationTitleDraft: string
  creationProjectTypeDraft: 'movie' | 'series'
  creationGlobalSettingDraft: GlobalSettingData
}

function cloneStoreGlobalSetting(): GlobalSettingData {
  return JSON.parse(
    JSON.stringify(useCreationStore.getState().formData.globalSetting)
  ) as GlobalSettingData
}

/** 首页壳层（案例广场 / 我的作品 / 资产库）共用的「创建作品第一步」弹窗状态（原 Vue 模块级单例） */
const useHomeShellCreateModalStore = create<HomeShellCreateModalState>(() => ({
  showCreateFirstStepModal: false,
  createConfirmLoading: false,
  syncProjectTypeFromParent: false,
  creationTitleDraft: '未命名作品',
  creationProjectTypeDraft: 'series',
  creationGlobalSettingDraft: cloneStoreGlobalSetting()
}))

function setShowCreateFirstStepModal(open: boolean) {
  useHomeShellCreateModalStore.setState({ showCreateFirstStepModal: open })
}

function setCreationTitleDraft(title: string) {
  useHomeShellCreateModalStore.setState({ creationTitleDraft: title })
}

function setCreationProjectTypeDraft(type: 'movie' | 'series') {
  useHomeShellCreateModalStore.setState({ creationProjectTypeDraft: type })
}

function syncCreateDraftFromStore() {
  useHomeShellCreateModalStore.setState({
    creationTitleDraft: useCreationStore.getState().workTitle || '未命名作品',
    creationGlobalSettingDraft: cloneStoreGlobalSetting(),
    creationProjectTypeDraft: CREATE_FIRST_STEP_DEFAULTS.projectType
  })
}

function updateGlobalSettingDraft(value: GlobalSettingData) {
  useHomeShellCreateModalStore.setState({ creationGlobalSettingDraft: value })
}

/** 仅更新风格库字段，避免冲掉左侧基本信息草稿 */
function patchGlobalSettingDraftStyle(
  patch: Pick<
    GlobalSettingData,
    'selectedStyle' | 'myStyles' | 'style' | 'styleSelectionTouched' | 'styleLocked'
  >
) {
  const draft = useHomeShellCreateModalStore.getState().creationGlobalSettingDraft
  useHomeShellCreateModalStore.setState({
    creationGlobalSettingDraft: {
      ...draft,
      selectedStyle: patch.selectedStyle,
      myStyles: patch.myStyles,
      style: patch.style,
      styleSelectionTouched: patch.styleSelectionTouched,
      styleLocked: patch.styleLocked
    }
  })
}

function updateGlobalSettingDraftField<K extends keyof GlobalSettingData>(
  key: K,
  value: GlobalSettingData[K]
) {
  const draft = useHomeShellCreateModalStore.getState().creationGlobalSettingDraft
  useHomeShellCreateModalStore.setState({
    creationGlobalSettingDraft: {
      ...draft,
      [key]: value
    }
  })
}

function openCreateModal(options?: { worksTab?: 'film' | 'series' }) {
  const base = cloneStoreGlobalSetting()
  const d = CREATE_FIRST_STEP_DEFAULTS
  // 新建作品不带入上次/当前作品的风格；selectedStyle 置空后由风格库默认选中精选第一项
  const next: Partial<HomeShellCreateModalState> = {
    creationTitleDraft: '未命名作品',
    creationGlobalSettingDraft: {
      ...base,
      scriptType: d.scriptType,
      modelStrategy: d.modelStrategy,
      creationMode: d.creationMode,
      selectedStyle: null,
      style: '',
      styleSelectionTouched: false,
      styleLocked: false
    }
  }
  if (options?.worksTab) {
    next.syncProjectTypeFromParent = true
    next.creationProjectTypeDraft = options.worksTab === 'series' ? 'series' : 'movie'
  } else {
    next.syncProjectTypeFromParent = false
    next.creationProjectTypeDraft = d.projectType
  }
  next.showCreateFirstStepModal = true
  useHomeShellCreateModalStore.setState(next)
}

async function runCreateConfirm(router: { push: (href: string) => void }) {
  const state = useHomeShellCreateModalStore.getState()
  if (state.createConfirmLoading) return
  const draftStylePayload = buildProjectVideoStyleFields(
    state.creationGlobalSettingDraft.selectedStyle,
    state.creationGlobalSettingDraft.style
  )
  if (!draftStylePayload) {
    message.warning('请选择主题风格')
    return
  }
  if (!draftStylePayload.styleSource || !draftStylePayload.styleAssetId) {
    message.warning('请选择有效风格')
    return
  }
  useHomeShellCreateModalStore.setState({ createConfirmLoading: true })
  try {
    const finalTitle = (state.creationTitleDraft || '').trim() || '未命名作品'
    const finalGlobalSetting: GlobalSettingData = {
      ...state.creationGlobalSettingDraft,
      title: finalTitle,
      style:
        state.creationGlobalSettingDraft.selectedStyle?.name ||
        state.creationGlobalSettingDraft.style ||
        ''
    }

    const createPayload: UserProjectCreateRequest = {
      projectName: finalTitle,
      projectDesc: finalGlobalSetting.description || '',
      projectType: state.creationProjectTypeDraft,
      aspectRatio: finalGlobalSetting.aspectRatio,
      scriptType: finalGlobalSetting.scriptType,
      ...draftStylePayload,
      styleSource: draftStylePayload.styleSource,
      styleAssetId: draftStylePayload.styleAssetId,
      defaultGenMode: finalGlobalSetting.modelStrategy,
      defaultCreationMode: finalGlobalSetting.creationMode
    }

    const createRes = await userProjectCreate(createPayload)

    const savedGlobalSetting: GlobalSettingData = {
      ...finalGlobalSetting,
      styleSelectionTouched: false,
      styleLocked: createRes.data.styleLocked === true
    }

    const creation = useCreationStore.getState()
    creation.setWorkTitle(finalTitle)
    creation.updateFormData({
      globalSetting: savedGlobalSetting
    })
    creation.setCurrentProjectType(state.creationProjectTypeDraft)
    creation.setCurrentProjectContext({
      projectId: createRes.data.id,
      episodeId: state.creationProjectTypeDraft === 'movie' ? 0 : null
    })
    creation.setCurrentStepIndex(1)
    message.success(createRes.msg || '项目创建成功')
    const pid = String(createRes.data.id)
    const createQuery = `projectId=${pid}&id=${pid}`
    setTimeout(() => {
      useHomeShellCreateModalStore.setState({
        createConfirmLoading: false,
        showCreateFirstStepModal: false
      })
      if (state.creationProjectTypeDraft === 'series') {
        router.push(`${CREATE_SERIES_SCRIPT_UPLOAD_PATH}?${createQuery}`)
      } else {
        router.push(`/create?${createQuery}&stepInitAdvance=1`)
      }
    }, 2000)
  } catch (error: unknown) {
    useHomeShellCreateModalStore.setState({ createConfirmLoading: false })
    const backendMsg =
      error && typeof error === 'object' && 'msg' in error
        ? String((error as { msg?: string }).msg)
        : error instanceof Error
          ? error.message
          : ''
    message.error(backendMsg || '创建项目失败，请稍后重试')
  }
}

export type HomeShellCreateModalApi = ReturnType<typeof useHomeShellCreateModal>

/**
 * 首页壳层共用的「创建作品第一步」弹窗状态（单例）：
 * 组件内调用响应式读取；原 Vue 直接赋值的字段改为对应 setXxx 动作。
 */
export function useHomeShellCreateModal() {
  const router = useRouter()
  const state = useHomeShellCreateModalStore()

  return {
    ...state,
    setShowCreateFirstStepModal,
    setCreationTitleDraft,
    setCreationProjectTypeDraft,
    syncCreateDraftFromStore,
    updateGlobalSettingDraft,
    patchGlobalSettingDraftStyle,
    updateGlobalSettingDraftField,
    openCreateModal,
    handleCreateConfirm: () => runCreateConfirm(router)
  }
}
