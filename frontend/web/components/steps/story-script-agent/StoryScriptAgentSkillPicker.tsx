'use client'

import {
  SkillPickerPopover,
  type SkillPickerPopoverProps
} from '~/components/common/skill-picker/SkillPickerPopover'

/** 对话面板入口：默认 skill.svg 触发器，面板实现见公用 SkillPickerPopover。 */
export function StoryScriptAgentSkillPicker(props: SkillPickerPopoverProps) {
  return <SkillPickerPopover {...props} />
}

export default StoryScriptAgentSkillPicker
