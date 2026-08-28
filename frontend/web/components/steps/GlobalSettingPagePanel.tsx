'use client'

import CreateFirstStepFormBody from '~/components/steps/CreateFirstStepFormBody'
import type { GlobalSettingData } from '~/types'
import './global-setting-page-panel.css'

type ProjectTypeValue = 'movie' | 'series'

export interface GlobalSettingPagePanelProps {
  title: string
  projectType: ProjectTypeValue
  draft: GlobalSettingData
  projectTypeLocked: boolean
  onTitleChange: (value: string) => void
  onProjectTypeChange: (value: ProjectTypeValue) => void
  onFieldChange: (
    key: keyof GlobalSettingData,
    value: GlobalSettingData[keyof GlobalSettingData]
  ) => void
  onPatchStyle: (
    patch: Pick<
      GlobalSettingData,
      'selectedStyle' | 'myStyles' | 'style' | 'styleSelectionTouched' | 'styleLocked'
    >
  ) => void
}

/** 流程页内嵌的项目配置面板（复用创建作品第一步表单），原 GlobalSettingPagePanel.vue */
export function GlobalSettingPagePanel({
  title,
  projectType,
  draft,
  projectTypeLocked,
  onTitleChange,
  onProjectTypeChange,
  onFieldChange,
  onPatchStyle
}: GlobalSettingPagePanelProps) {
  return (
    <div className="global-setting-page-panel">
      <CreateFirstStepFormBody
        pageLayout
        open={true}
        flowEditMode
        projectTypeLocked={projectTypeLocked}
        title={title}
        projectType={projectType}
        aspectRatio={draft.aspectRatio}
        scriptType={draft.scriptType}
        modelStrategy={draft.modelStrategy}
        creationMode={draft.creationMode}
        modelValue={draft}
        onTitleChange={onTitleChange}
        onProjectTypeChange={onProjectTypeChange}
        onAspectRatioChange={(v) => onFieldChange('aspectRatio', v)}
        onScriptTypeChange={(v) => onFieldChange('scriptType', v)}
        onModelStrategyChange={(v) => onFieldChange('modelStrategy', v)}
        onCreationModeChange={(v) => onFieldChange('creationMode', v)}
        onModelValueChange={(v) => onPatchStyle(v)}
      />
    </div>
  )
}

export default GlobalSettingPagePanel
