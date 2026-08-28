'use client'

/** 原 pages/create/global-setting.vue（definePageMeta layout:'create'）：流程页内嵌项目配置面板 */

import { Suspense,useEffect } from 'react'
import GlobalSettingPagePanel from '~/components/steps/GlobalSettingPagePanel'
import { useCreateFlowShell } from '~/composables/useCreateFlowShell'
import { useGlobalSettingProjectHydrate } from '~/composables/useGlobalSettingProjectHydrate'
import { useRouteLike } from '~/hooks/useRouteLike'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
function GlobalSettingStepClient() {
  const route = useRouteLike()
  const {
    titleDraft,
    projectTypeDraft,
    draft,
    projectTypeLocked,
    syncFromStore,
    updateField,
    patchStyle,
    setTitleDraft,
    setProjectTypeDraft
  } = useCreateFlowShell().globalSetting

  const { hydrateFromProjectApi } = useGlobalSettingProjectHydrate()

  // 原 watch([route.path, query ids], immediate)：从 project/detail 回显草稿
  const routeQueryProjectId = route.query.projectId
  const routeQueryId = route.query.id
  const routeQueryWorkId = route.query.workId
  useEffect(() => {
    void (async () => {
      if (routePathToCreationStep(route.path) !== 'global-setting') return
      const hydrated = await hydrateFromProjectApi(route)
      if (hydrated) {
        syncFromStore()
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.path, routeQueryProjectId, routeQueryId, routeQueryWorkId])

  return (
    <GlobalSettingPagePanel
      title={titleDraft}
      projectType={projectTypeDraft}
      draft={draft}
      projectTypeLocked={projectTypeLocked}
      onTitleChange={setTitleDraft}
      onProjectTypeChange={setProjectTypeDraft}
      onFieldChange={updateField}
      onPatchStyle={patchStyle}
    />
  )
}

export default function GlobalSettingStepPage() {
  return (
    <Suspense fallback={null}>
      <GlobalSettingStepClient />
    </Suspense>
  )
}
