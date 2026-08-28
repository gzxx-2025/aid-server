'use client'

/**
 * 原 pages/create/storyboard-script.vue（definePageMeta layout:'create'）：分镜设计步骤薄页面。
 * v-model 绑定 creationStore.formData.storyboardScript.panels；
 * go-step → shell.goToStep；generation-complete → shell.syncVideoAndDubbingFromScriptPanels；
 * 引导 tooltip 两项来自壳层 createFlowShellContext。
 */

import { Suspense } from 'react'
import { StoryboardScript } from '~/components/steps/StoryboardScript'
import { useCreateFlowShell } from '~/composables/useCreateFlowShell'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'

function StoryboardScriptStepClient() {
  const panels = useCreationStore(
    (s) => s.formData.storyboardScript.panels as StoryboardPanel[]
  )
  const shell = useCreateFlowShell()

  function onPanelsChange(v: StoryboardPanel[]) {
    // 原 computed setter：creationStore.formData.storyboardScript.panels = v（整分支不可变替换）
    useCreationStore.setState((s) => ({
      formData: {
        ...s.formData,
        storyboardScript: { ...s.formData.storyboardScript, panels: v }
      }
    }))
  }

  return (
    <StoryboardScript
      value={panels}
      editScriptTooltipTargetIndex={shell.storyboardScriptTooltipTargetIndex}
      editScriptTooltipKey={shell.storyboardScriptTooltipKey}
      onChange={onPanelsChange}
      onGoStep={shell.goToStep}
      onGenerationComplete={shell.syncVideoAndDubbingFromScriptPanels}
    />
  )
}

export default function StoryboardScriptStepPage() {
  return (
    <Suspense fallback={null}>
      <StoryboardScriptStepClient />
    </Suspense>
  )
}
