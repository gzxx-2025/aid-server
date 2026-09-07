'use client'

import { Button } from 'antd'
import { EditOutlined, EyeOutlined, SettingOutlined, SkinOutlined } from '@ant-design/icons'
import type { GlobalSettingData } from '@/types'
import { useStudioUiStore } from '@/stores/studioUi'
import { hasImmediateProjectConfigAssetLock } from '@/utils/projectConfigAssetGuard'
import { useStudioFlowRuntime } from '../StudioFlowRuntimeProvider'

const CREATION_MODE_LABEL: Record<string, string> = {
  i2v: '图生视频',
  multi: '多参',
  pro: '专业版',
  auto_grid: '自动宫格'
}

export function StudioFlowSetupSummary({ draft }: { draft: GlobalSettingData }) {
  const runtime = useStudioFlowRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const contentLocked = hasImmediateProjectConfigAssetLock({
    styleLocked: draft.styleLocked === true,
    nodes
  })
  return (
    <article className="studio-flow-setup-summary">
      <header>
        <SettingOutlined />
        <strong>项目配置</strong>
        <Button
          type="text"
          size="small"
          icon={contentLocked ? <EyeOutlined /> : <EditOutlined />}
          onClick={() => runtime?.openEditor('global-setting')}
        >
          {contentLocked ? '查看' : '修改'}
        </Button>
      </header>
      <div className="studio-flow-setup-summary__chips">
        <span className="studio-flow-setup-summary__chip">
          {draft.selectedStyle?.thumbnail ? (
            <i
              className="studio-flow-setup-style__preview"
              style={{ backgroundImage: `url("${String(draft.selectedStyle.thumbnail).replace(/"/g, '%22')}")` }}
            />
          ) : (
            <SkinOutlined />
          )}
          {draft.selectedStyle?.name || '未选风格'}
        </span>
        <span className="studio-flow-setup-summary__chip">
          {CREATION_MODE_LABEL[draft.creationMode || ''] || draft.creationMode}
        </span>
        <span className="studio-flow-setup-summary__chip">{draft.aspectRatio}</span>
        <span className="studio-flow-setup-summary__chip">
          {draft.scriptType === 'monologue' ? '真人解说' : '剧情演绎'}
        </span>
      </div>
      <p>配置已在创项目时保存。生成剧本并一键带入后，将自动提取素材、形态图、分镜、视频与配音；完成后可在顶栏「交付」菜单导出成片。</p>
    </article>
  )
}
