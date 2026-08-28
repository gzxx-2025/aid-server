'use client'

/**
 * 原 pages/create/storyboard-video.vue（definePageMeta layout:'create'）：视频生成步骤薄页面。
 * v-model 绑定 creationStore.formData.storyboardVideo.panels；
 * go-step → shell.goToStep；jump-to-storyboard-script → shell.jumpToStoryboardScriptFromVideo。
 */

import { Suspense } from 'react'
import { StoryboardVideo } from '~/components/steps/StoryboardVideo'
import { useCreateFlowShell } from '~/composables/useCreateFlowShell'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
function StoryboardVideoStepClient() {
  const videoPanels = useCreationStore(
    (s) => s.formData.storyboardVideo.panels as StoryboardVideoPanel[]
  )
  /** 直接透传 store 引用，避免每次 render 都 map 出新数组触发子组件同步逻辑 */
  const storyboardScriptPanelsForVideo = useCreationStore(
    (s) => s.formData.storyboardScript.panels as StoryboardPanel[]
  )
  const shell = useCreateFlowShell()

  function onPanelsChange(v: StoryboardVideoPanel[]) {
    // 原 computed setter：creationStore.formData.storyboardVideo.panels = v（整分支不可变替换）
    useCreationStore.setState((s) => ({
      formData: {
        ...s.formData,
        storyboardVideo: { ...s.formData.storyboardVideo, panels: v }
      }
    }))
  }

  return (
    <StoryboardVideo
      value={videoPanels}
      description="将分镜转为动态视频，批量提交出片任务"
      storyboardScriptPanels={storyboardScriptPanelsForVideo}
      onChange={onPanelsChange}
      onGoStep={shell.goToStep}
      onJumpToStoryboardScript={shell.jumpToStoryboardScriptFromVideo}
    />
  )
}

export default function StoryboardVideoStepPage() {
  return (
    <Suspense fallback={null}>
      <StoryboardVideoStepClient />
    </Suspense>
  )
}
