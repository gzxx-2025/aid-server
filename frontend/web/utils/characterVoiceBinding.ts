import type { RoleVoiceBindingVO } from '~/types/business-api'

export type CharacterFormVoiceFields = {
  voiceover?: string
  voiceoverId?: string
  voiceoverAvatarUrl?: string
  voiceoverPreviewUrl?: string
}

/** 将服务端 voiceBinding 转为角色形态 UI 使用的配音字段 */
export function roleVoiceBindingToFormFields(
  binding?: RoleVoiceBindingVO | null
): CharacterFormVoiceFields {
  if (!binding?.voiceLibraryId || !String(binding.voiceName || '').trim()) {
    return {
      voiceover: undefined,
      voiceoverId: undefined,
      voiceoverAvatarUrl: undefined,
      voiceoverPreviewUrl: undefined
    }
  }
  return {
    voiceover: String(binding.voiceName || '').trim(),
    voiceoverId: String(binding.voiceLibraryId),
    voiceoverAvatarUrl: String(binding.avatarUrl || '').trim() || undefined,
    voiceoverPreviewUrl: String(binding.sampleUrl || '').trim() || undefined
  }
}

/** 角色级 1:1 音色绑定：同一角色下所有形态展示相同配音 */
export function applyVoiceFieldsToCharacterForms<T extends CharacterFormVoiceFields>(
  forms: T[],
  fields: CharacterFormVoiceFields
): void {
  for (const form of forms) {
    form.voiceover = fields.voiceover
    form.voiceoverId = fields.voiceoverId
    form.voiceoverAvatarUrl = fields.voiceoverAvatarUrl
    form.voiceoverPreviewUrl = fields.voiceoverPreviewUrl
  }
}
