'use client'

/** 原 pages/create/story-script.vue（definePageMeta layout:'create'）：剧本创作步骤薄页面 */

import { Suspense } from 'react'
import { StoryScript } from '~/components/steps/StoryScript'
import { useCreationStore } from '~/stores/creation'

function StoryScriptStepClient() {
  const content = useCreationStore((s) => s.formData.storyScript.content)

  function onUpdate(v: string) {
    useCreationStore.getState().updateFormData({ storyScript: { content: v } })
  }

  return <StoryScript value={content} onChange={onUpdate} />
}

export default function StoryScriptStepPage() {
  return (
    <Suspense fallback={null}>
      <StoryScriptStepClient />
    </Suspense>
  )
}
