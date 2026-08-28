'use client'

/** 原 pages/create/scene-character.vue（definePageMeta layout:'create'）：素材准备步骤薄页面 */

import { Suspense } from 'react'
import { SceneCharacterProp } from '~/components/steps/SceneCharacterProp'
import { useCreateFlowShell } from '~/composables/useCreateFlowShell'
import { useCreationStore } from '~/stores/creation'

function SceneCharacterStepClient() {
  const sceneCharacter = useCreationStore((s) => s.formData.sceneCharacter)
  const storyScriptContent = useCreationStore((s) => s.formData.storyScript.content)
  const isExtractingAssets = useCreationStore((s) => s.isExtractingAssets)
  const extractingStage = useCreationStore((s) => s.extractingStage)
  const extractingStages = useCreationStore((s) => s.extractingStages)
  const shell = useCreateFlowShell()

  return (
    <SceneCharacterProp
      modelValue={sceneCharacter}
      storyScriptContent={storyScriptContent}
      isExtracting={isExtractingAssets}
      extractingStage={extractingStage}
      extractingStages={extractingStages}
      onModelValueChange={(v) => useCreationStore.getState().updateSceneCharacterData(v)}
      onStopExtract={() => void shell.stopExtractAssets()}
      onOpenExtractModal={shell.openExtractModalFromScp}
    />
  )
}

export default function SceneCharacterStepPage() {
  return (
    <Suspense fallback={null}>
      <SceneCharacterStepClient />
    </Suspense>
  )
}
