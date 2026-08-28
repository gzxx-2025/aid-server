import type { CreationStep } from '~/types'
import type { UserProjectType } from '~/types/business-api'
/** 创作流程步骤元数据（与路由 `/create/<key>`、流程条一致） */
export interface CreationFlowStepMeta {
  key: CreationStep
  title: string
  description: string
  guide: string
}

export const CREATION_FLOW_STEPS: CreationFlowStepMeta[] = [
  {
    key: 'global-setting',
    title: '项目配置',
    description: '设定作品类型、画幅与创作策略',
    guide: '填写作品名称、类型，并配置画面比例、剧本类型与模型策略'
  },
  {
    key: 'story-script',
    title: '剧本创作',
    description: '撰写或导入剧本内容',
    guide: '在编辑器中编写或粘贴剧本正文，也可导入 Word 等文档'
  },
  {
    key: 'scene-character',
    title: '素材准备',
    description: '提取并完善场景、角色与道具',
    guide: '从剧本智能提取资产，批量生成形态图与设定素材'
  },
  {
    key: 'storyboard-script',
    title: '分镜设计',
    description: '规划镜头脚本与分镜画面',
    guide: '根据剧本拆分镜头，生成分镜脚本与分镜图'
  },
  {
    key: 'storyboard-video',
    title: '视频生成',
    description: '将分镜转为动态视频',
    guide: '为每个镜头生成或上传视频，批量提交出片任务'
  },
  {
    key: 'dubbing',
    title: '音画同步',
    description: '添加配音并实现音画对口',
    guide: '为镜头配置旁白或对白，系统自动匹配口型'
  },
  {
    key: 'preview',
    title: '成品预览',
    description: '预览成片并导出发布',
    guide: '完整播放检查节奏与画面，导出完整视频后可发布至案例广场或保存本地'
  }
]

/** 流程步骤名称唯一映射，供步骤条以外的流程目录复用。 */
export const CREATION_FLOW_STEP_TITLE_MAP = Object.fromEntries(
  CREATION_FLOW_STEPS.map((step) => [step.key, step.title])
) as Record<CreationStep, string>

/**
 * 流程条展示步骤：剧集隐藏「项目配置」，从剧本创作起共 6 步；
 * 后端步骤枚举仍为 7 步，点击/状态用 CREATE_FLOW_STEP_ORDER 真实下标。
 */
export function getCreateFlowDisplaySteps(
  projectType: UserProjectType | null | undefined
): CreationFlowStepMeta[] {
  if (projectType === 'series') {
    return CREATION_FLOW_STEPS.filter((s) => s.key !== 'global-setting')
  }
  return CREATION_FLOW_STEPS
}
