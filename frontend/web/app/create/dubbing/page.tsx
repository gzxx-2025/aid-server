'use client'

/**
 * 原 pages/create/dubbing.vue（definePageMeta layout:'create'）：音画同步步骤薄页面。
 * v-model 绑定 creationStore.formData.dubbing.panels；
 * update:storyboard-video-panels → storyboardVideo.panels；
 * go-step → shell.goToStep；generating → shell.setDubbingGenerating。
 */

import { Suspense } from 'react'
import { Dubbing } from '~/components/steps/Dubbing'
import { useCreateFlowShell } from '~/composables/useCreateFlowShell'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel,StoryboardPanel,StoryboardVideoPanel } from '~/types'

function DubbingStepClient() {
  const dubbingPanels = useCreationStore((s) => s.formData.dubbing.panels as DubbingPanel[])
  const storyboardVideoPanels = useCreationStore(
    (s) => s.formData.storyboardVideo.panels as StoryboardVideoPanel[]
  )
  const storyboardScriptPanels = useCreationStore(
    (s) => s.formData.storyboardScript.panels as StoryboardPanel[]
  )
  const sceneCharacters = useCreationStore((s) => s.formData.sceneCharacter.characters)
  const shell = useCreateFlowShell()

  function onDubbingPanelsChange(v: DubbingPanel[]) {
    // 原 computed setter：creationStore.formData.dubbing.panels = v（整分支不可变替换）
    useCreationStore.setState((s) => ({
      formData: {
        ...s.formData,
        dubbing: { ...s.formData.dubbing, panels: v }
      }
    }))
  }

  function onUpdateStoryboardVideo(v: StoryboardVideoPanel[]) {
    useCreationStore.setState((s) => ({
      formData: {
        ...s.formData,
        storyboardVideo: { ...s.formData.storyboardVideo, panels: v }
      }
    }))
  }

  return (
    <Dubbing
      value={dubbingPanels}
      description="添加配音并实现音画对口，自动匹配口型"
      storyboardVideoPanels={storyboardVideoPanels}
      storyboardScriptPanels={storyboardScriptPanels}
      sceneCharacters={sceneCharacters}
      onChange={onDubbingPanelsChange}
      onStoryboardVideoPanelsChange={onUpdateStoryboardVideo}
      onGoStep={shell.goToStep}
      onGenerating={shell.setDubbingGenerating}
    />
  )
}

export default function DubbingStepPage() {
  return (
    <Suspense fallback={null}>
      <DubbingStepClient />
    </Suspense>
  )
}
