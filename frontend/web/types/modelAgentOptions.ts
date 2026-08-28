/**
 * 智能体/模型选择项共享类型。
 * 原定义在 components/steps/AgentPickerModal.vue 与 ModelSelectDropdown.vue，
 * 迁移后提升到 types 供 utils 与组件共同引用（组件迁移时请从这里导入，勿再各自定义）。
 */

export interface AgentOption {
  /** 智能体 agentCode，用于形态/分镜等需传 agentCode 的接口 */
  id: string
  name: string
  desc?: string
  thumbnail?: string
  /** 业务分类编码（列表分组用；parallel 提交用 id/agentCode） */
  bizCategoryCode?: string
  /** 智能体默认 modelCode（切换智能体时优先选中） */
  defaultModelCode?: string
}

export interface ModelOption {
  /**
   * 与接口一致的模型编码（如 wan2.7-image），用于 modelCode 类入参。
   * 勿用服务端数字主键，否则多机位/生图等会误传 "5" 这类值。
   */
  id: string
  name: string
  icon?: string
  iconBg?: string
  tag?: string
  tagType?: 'best' | 'cost-effective' | 'default'
  desc?: string
  prices?: Array<{ resolution: string; cost: number }>
  /** POST /api/user/storyboard/generate/media 等仍要求 modelId 时使用 */
  serverModelId?: number
  /** capability.supportsAudio：是否支持音画同步（音画同出） */
  supportsAudio?: boolean
  /** 接口明确标记为 true 时显示免费角标。 */
  isFree?: boolean
  /** 服务端已按最终倍率折算的计费规则；MIXED 仍属于同一个模型项。 */
  billing?: ModelBillingDetailVO | null
}
import type { ModelBillingDetailVO } from '~/types/business-api'
