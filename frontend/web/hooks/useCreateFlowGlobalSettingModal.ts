'use client'

import { message } from 'antd'
import { useRouter } from 'next/navigation'
import { useCallback,useRef,useState } from 'react'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import type { GlobalSettingData } from '~/types'
import type { UserProjectUpdateRequest } from '~/types/business-api'
import { buildProjectVideoStyleFields } from '~/utils/buildProjectVideoStyleFields'
import { userProjectUpdate } from '~/utils/businessApi'
import { creationStepToRoutePath } from '~/utils/createFlowRoutes'
import { resetProjectDetailHydrateCache } from '~/utils/hydrateCreationStoreFromProjectDetail'

/**
 * 壳层「项目配置」弹窗状态与确认（原 composables/useCreateFlowGlobalSettingModal.ts）
 */
export function useCreateFlowGlobalSettingModal() {
  const router = useRouter()

  const [showGlobalSettingModal, setShowGlobalSettingModalState] = useState(false)
  const [globalSettingConfirmLoading, setGlobalSettingConfirmLoading] = useState(false)
  const [creationTitleDraft, setCreationTitleDraftState] = useState('未命名作品')
  const [globalSettingProjectTypeDraft, setGlobalSettingProjectTypeDraftState] = useState<
    'movie' | 'series'
  >('movie')
  const [creationGlobalSettingDraft, setCreationGlobalSettingDraftState] =
    useState<GlobalSettingData>(() =>
      JSON.parse(JSON.stringify(useCreationStore.getState().formData.globalSetting))
    )

  /** 事件回调需读最新草稿（异步保存期间用户可能继续编辑） */
  const titleDraftRef = useRef(creationTitleDraft)
  const projectTypeDraftRef = useRef(globalSettingProjectTypeDraft)
  const draftRef = useRef(creationGlobalSettingDraft)
  const confirmLoadingRef = useRef(false)

  const setCreationTitleDraft = useCallback((v: string) => {
    titleDraftRef.current = v
    setCreationTitleDraftState(v)
  }, [])

  const setGlobalSettingProjectTypeDraft = useCallback((v: 'movie' | 'series') => {
    projectTypeDraftRef.current = v
    setGlobalSettingProjectTypeDraftState(v)
  }, [])

  const setDraft = useCallback((v: GlobalSettingData) => {
    draftRef.current = v
    setCreationGlobalSettingDraftState(v)
  }, [])

  const setShowGlobalSettingModal = useCallback((open: boolean) => {
    setShowGlobalSettingModalState(open)
  }, [])

  const syncGlobalSettingDraftFromStore = useCallback(() => {
    const store = useCreationStore.getState()
    setCreationTitleDraft(store.workTitle || '未命名作品')
    const nextDraft = JSON.parse(
      JSON.stringify(store.formData.globalSetting)
    ) as GlobalSettingData
    setDraft(nextDraft)
    const pt = store.currentProjectType
    if (pt === 'movie' || pt === 'series') {
      setGlobalSettingProjectTypeDraft(pt)
    } else {
      setGlobalSettingProjectTypeDraft(nextDraft.scriptType === 'monologue' ? 'series' : 'movie')
    }
  }, [setCreationTitleDraft, setDraft, setGlobalSettingProjectTypeDraft])

  const openGlobalSettingModal = useCallback(() => {
    syncGlobalSettingDraftFromStore()
    setShowGlobalSettingModalState(true)
  }, [syncGlobalSettingDraftFromStore])

  const updateGlobalSettingDraft = useCallback(
    (value: GlobalSettingData) => {
      setDraft(value)
    },
    [setDraft]
  )

  const patchGlobalSettingDraftStyle = useCallback(
    (
      patch: Pick<
        GlobalSettingData,
        'selectedStyle' | 'myStyles' | 'style' | 'styleSelectionTouched' | 'styleLocked'
      >
    ) => {
      setDraft({
        ...draftRef.current,
        selectedStyle: patch.selectedStyle,
        myStyles: patch.myStyles,
        style: patch.style,
        styleSelectionTouched: patch.styleSelectionTouched,
        styleLocked: patch.styleLocked
      })
    },
    [setDraft]
  )

  const updateGlobalSettingDraftField = useCallback(
    <K extends keyof GlobalSettingData>(key: K, value: GlobalSettingData[K]) => {
      setDraft({
        ...draftRef.current,
        [key]: value
      })
    },
    [setDraft]
  )

  const handleGlobalSettingConfirm = useCallback(
    async (options?: { navigateAfterSave?: boolean; contentConfigLocked?: boolean }) => {
      if (confirmLoadingRef.current) return
      confirmLoadingRef.current = true
      setGlobalSettingConfirmLoading(true)
      const creationStore = useCreationStore.getState()
      try {
        const finalTitle = (titleDraftRef.current || '').trim() || '未命名作品'
        const finalGlobalSetting: GlobalSettingData = {
          ...draftRef.current,
          title: finalTitle,
          style: draftRef.current.selectedStyle?.name || draftRef.current.style || ''
        }
        const contentConfigLocked = options?.contentConfigLocked === true
        const videoStylePayload = contentConfigLocked
          ? null
          : buildProjectVideoStyleFields(finalGlobalSetting.selectedStyle, finalGlobalSetting.style)
        if (!contentConfigLocked) {
          if (!videoStylePayload) {
            message.warning('请选择主题风格')
            return
          }
          if (
            finalGlobalSetting.styleSelectionTouched === true &&
            (!videoStylePayload.styleSource || !videoStylePayload.styleAssetId)
          ) {
            message.warning('请选择有效风格')
            return
          }
        }
        const pid = creationStore.currentProjectId
        if (!pid) {
          message.error('缺少项目信息，无法保存')
          return
        }

        const updatePayload: UserProjectUpdateRequest = {
          id: pid,
          projectName: finalTitle
        }
        if (!contentConfigLocked) {
          Object.assign(updatePayload, {
            projectDesc: finalGlobalSetting.description || '',
            aspectRatio: finalGlobalSetting.aspectRatio,
            scriptType: finalGlobalSetting.scriptType,
            defaultGenMode: finalGlobalSetting.modelStrategy,
            defaultCreationMode: finalGlobalSetting.creationMode
          })
        }
        // 只有用户主动选择了另一风格才通知后端重建快照；普通保存不携带任何风格字段。
        if (
          !contentConfigLocked &&
          finalGlobalSetting.styleSelectionTouched === true &&
          finalGlobalSetting.styleLocked !== true &&
          videoStylePayload
        ) {
          Object.assign(updatePayload, videoStylePayload)
        }

        const updatedProject = await userProjectUpdate(updatePayload)
        // 用户可能在请求期间切换作品；旧请求可以完成服务端保存，但不能覆盖新作品的本地状态。
        if (useCreationStore.getState().currentProjectId !== pid) return
        const savedGlobalSetting: GlobalSettingData = contentConfigLocked
          ? {
              ...useCreationStore.getState().formData.globalSetting,
              title: finalTitle,
              styleSelectionTouched: false
            }
          : {
              ...finalGlobalSetting,
              styleSelectionTouched: false,
              styleLocked:
                updatedProject.styleLocked == null
                  ? finalGlobalSetting.styleLocked === true
                  : updatedProject.styleLocked === true
            }

        const storeNow = useCreationStore.getState()
        storeNow.setWorkTitle(finalTitle)
        storeNow.updateFormData({ globalSetting: savedGlobalSetting })
        resetProjectDetailHydrateCache()
        setShowGlobalSettingModalState(false)
        message.success('已保存')
        if (options?.navigateAfterSave) {
          storeNow.setCurrentStepIndex(1)
          const route = getRouteLikeSnapshot()
          const qs = new URLSearchParams()
          for (const [k, v] of Object.entries(route.query)) {
            if (v == null) continue
            qs.set(k, Array.isArray(v) ? String(v[0] ?? '') : String(v))
          }
          const path = creationStepToRoutePath('story-script')
          router.push(qs.toString() ? `${path}?${qs.toString()}` : path)
        }
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '保存失败，请稍后重试')
      } finally {
        confirmLoadingRef.current = false
        setGlobalSettingConfirmLoading(false)
      }
    },
    [router]
  )

  return {
    showGlobalSettingModal,
    setShowGlobalSettingModal,
    globalSettingConfirmLoading,
    creationTitleDraft,
    setCreationTitleDraft,
    globalSettingProjectTypeDraft,
    setGlobalSettingProjectTypeDraft,
    creationGlobalSettingDraft,
    syncGlobalSettingDraftFromStore,
    openGlobalSettingModal,
    updateGlobalSettingDraft,
    patchGlobalSettingDraftStyle,
    updateGlobalSettingDraftField,
    handleGlobalSettingConfirm
  }
}
