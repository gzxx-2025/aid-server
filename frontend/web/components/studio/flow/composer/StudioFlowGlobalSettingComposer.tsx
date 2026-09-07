'use client'

import { useMemo } from 'react'
import { Tag } from 'antd'
import { SettingOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'
import { useCreationStore } from '@/stores/creation'
import { useCreateFlowGlobalSettingModal } from '@/hooks/useCreateFlowGlobalSettingModal'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { requestStudioFlowAdvancedModal } from '@/utils/studio/studioFlowAdvancedModal'
import {
  StudioFlowComposerHeader
} from './StudioFlowComposerShared'

export function StudioFlowGlobalSettingComposer({ node }: { node: StudioCanvasNode }) {
  const globalSetting = useCreationStore((state) => state.formData.globalSetting)
  const workTitle = useCreationStore((state) => state.workTitle)
  const { handleGlobalSettingConfirm } = useCreateFlowGlobalSettingModal()
  const parameters = useMemo(() => [
    globalSetting.aspectRatio ? `比例 ${globalSetting.aspectRatio}` : '',
    globalSetting.creationMode ? `模式 ${globalSetting.creationMode}` : '',
    globalSetting.modelStrategy ? `策略 ${globalSetting.modelStrategy}` : '',
    globalSetting.scriptType ? `剧本 ${globalSetting.scriptType}` : ''
  ].filter(Boolean), [globalSetting])

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader node={node} subtitle={`${workTitle || '未命名作品'} · 项目配置`} />
      <div className="studio-flow-composer__parameters">
        {parameters.map((item) => <Tag key={item}>{item}</Tag>)}
      </div>
      <StudioComposerFrame
        node={node}
        readOnly
        showSend={false}
        placeholder="项目级参数在下方操作保存；需要完整表单时使用生成配置或完整配置。"
        bottomLeft={(
          <>
            <StudioComposerChip
              icon={<SettingOutlined />}
              label="生成配置"
              arrow={false}
              onClick={() => requestStudioFlowAdvancedModal({ kind: 'project-gen-config' })}
            />
            <StudioComposerChip
              icon={<SettingOutlined />}
              label="完整配置"
              arrow={false}
              onClick={() => requestStudioFlowAdvancedModal({ kind: 'global-setting' })}
            />
          </>
        )}
      />
      <button
        type="button"
        className="studio-flow-composer__primary-action"
        onClick={() => void handleGlobalSettingConfirm({ navigateAfterSave: false })}
      >
        保存当前项目参数
      </button>
    </div>
  )
}
