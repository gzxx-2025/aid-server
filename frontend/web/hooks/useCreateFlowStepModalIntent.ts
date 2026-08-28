'use client'

import { useSyncExternalStore } from 'react'
import {
  peekCreateFlowStepModalIntent,
  subscribeCreateFlowStepModalIntent,
  type CreateFlowStepModalIntent
} from '~/utils/createFlowStepModalIntent'

const getServerSnapshot = (): CreateFlowStepModalIntent | null => null

/** 跨步骤弹窗意图的 React 外部状态订阅，避免 effect 中二次镜像 token。 */
export function useCreateFlowStepModalIntent(): CreateFlowStepModalIntent | null {
  return useSyncExternalStore(
    subscribeCreateFlowStepModalIntent,
    peekCreateFlowStepModalIntent,
    getServerSnapshot
  )
}
