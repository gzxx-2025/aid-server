'use client'

import { useContext } from 'react'
import { createFlowShellContext,type CreateFlowShellContext } from '~/utils/createFlowInjection'
/** 原 composables/useCreateFlowShell.ts（inject 版）：必须在创作流程壳层 Provider 内使用 */
export function useCreateFlowShell(): CreateFlowShellContext {
  const ctx = useContext(createFlowShellContext)
  if (!ctx) {
    throw new Error('useCreateFlowShell: 必须在创作流程布局内使用')
  }
  return ctx
}
