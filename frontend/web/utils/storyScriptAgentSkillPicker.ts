import type { UserSkillDefinition } from '~/types/user-skill'

export interface SkillPickerCard {
  skillCode: string
  name: string
  description: string
  iconUrl: string
}

/** Skill 列表接口里适合在选择面板展示的字段：名称、简介、图标。 */
export function toSkillPickerCard(skill: UserSkillDefinition): SkillPickerCard {
  return {
    skillCode: String(skill.skillCode || '').trim(),
    name: String(skill.name || skill.skillCode || '未命名 Skill').trim(),
    description: String(skill.description || '').trim(),
    iconUrl: String(skill.iconUrl || '').trim()
  }
}

/** 按快捷入口提示匹配剧本对话可用的 Skill。 */
export function resolveFlowShortcutSkillCode(
  skills: Array<{
    skillCode: string
    name?: string | null
    description?: string | null
    capability?: string | null
  }>,
  hint: string
): string | undefined {
  const normalized = hint.trim()
  if (!normalized) return undefined
  const pattern = /分镜|脚本|screenplay|storyboard/i.test(normalized)
    ? /分镜|脚本|screenplay|storyboard/i
    : /剧本|创作|screenplay|script|story/i
  return skills.find((skill) =>
    pattern.test(`${skill.skillCode} ${skill.name || ''} ${skill.description || ''} ${skill.capability || ''}`)
  )?.skillCode
}

export function resolveFlowShortcutEmptyHint(
  skills: Array<{
    skillCode: string
    name?: string | null
    description?: string | null
    capability?: string | null
  }>,
  selectedSkillCode: string,
  fallback: string
): string {
  const skill = skills.find((item) => item.skillCode === selectedSkillCode)
  if (!skill) return fallback
  const label = `${skill.skillCode} ${skill.name || ''} ${skill.description || ''} ${skill.capability || ''}`
  if (/角色|三视|character/i.test(label)) return '结合当前项目风格生成角色三视图，确认后可带入画布继续推进素材。'
  if (/参考生视频|reference/i.test(label)) return '结合当前项目风格与参考素材生成视频，确认后一键带入画布。'
  if (/收尾帧|首尾帧|end.?frame/i.test(label)) return '结合当前项目风格用收尾帧生成视频，确认后一键带入画布。'
  return fallback
}
