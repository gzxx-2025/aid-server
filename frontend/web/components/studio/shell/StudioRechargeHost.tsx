'use client'

import { useCallback, useEffect, useState } from 'react'
import { message } from 'antd'
import { requireLogin } from '~/utils/authLoginNavigation'
import RechargeModal from '@/components/common/RechargeModal'
import { useAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useUserStore } from '@/stores/user'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  clearStudioFlowAutoPipelineResume,
  dispatchStudioFlowAutoPipelineStart,
  readStudioFlowAutoPipelineResume,
  writeStudioFlowAutoPipelineResume
} from '@/utils/studio/studioFlowAutoPipelineEvents'
import { reconcileStudioFlowAutoPipelineResume } from '@/utils/studio/studioFlowAutoPipelineProgress'
import { studioFlowAutoPipelineStageTitle } from '@/utils/studio/studioFlowAutoPipelineRunner'

/**
 * Studio 不挂 CreateFlowShell / HomeNewShell，需独立承接 `open-recharge-modal`，
 * 否则流水线/SSE/axios 余额不足时只会 toast，不会弹出充值窗。
 * 充值成功后若有中断的自动流水线，会询问并尝试续跑。
 */
export function StudioRechargeHost() {
  const token = useUserStore((state) => state.token)
  const fetchProfile = useUserStore((state) => state.fetchProfile)
  const { anyPaymentEnabled, loadPublicConfig } = useAuthPublicConfig()
  const [open, setOpen] = useState(false)
  const isLoggedIn = Boolean(token)

  useEffect(() => {
    void loadPublicConfig()
  }, [loadPublicConfig])

  const openRecharge = useCallback(() => {
    if (!isLoggedIn) {
      requireLogin()
      return
    }
    if (!anyPaymentEnabled) {
      message.warning('暂未开放充值')
      return
    }
    setOpen(true)
  }, [anyPaymentEnabled, isLoggedIn])

  useEffect(() => {
    const onOpen = () => openRecharge()
    window.addEventListener('open-recharge-modal', onOpen)
    return () => window.removeEventListener('open-recharge-modal', onOpen)
  }, [openRecharge])

  return (
    <RechargeModal
      open={open}
      onOpenChange={setOpen}
      onPaid={() => {
        void fetchProfile()
        const projectId = useCreationStore.getState().currentProjectId
        const episodeId = Number(useCreationStore.getState().currentEpisodeId ?? 0)
        if (projectId) {
          const stored = readStudioFlowAutoPipelineResume(projectId, episodeId)
          if (stored) {
            const fromStage = reconcileStudioFlowAutoPipelineResume(stored, {
              nodes: useStudioUiStore.getState().nodes,
              creationMode: useCreationStore.getState().formData.globalSetting.creationMode
            })
            if (!fromStage) {
              clearStudioFlowAutoPipelineResume(projectId, episodeId)
              message.success('充值成功，画布阶段已全部完成')
              return
            }
            if (fromStage !== stored) {
              writeStudioFlowAutoPipelineResume(projectId, episodeId, fromStage)
            }
            message.success(
              fromStage === stored
                ? '充值成功，正在从中断处继续自动创作…'
                : `充值成功，正在从「${studioFlowAutoPipelineStageTitle(fromStage)}」继续…`
            )
            dispatchStudioFlowAutoPipelineStart({ projectId, episodeId, fromStage })
            return
          }
        }
        message.success('充值成功，可继续创作')
      }}
    />
  )
}
