import type { UserStoryboardListRow,UserStoryboardSpeakerVoice } from '~/types/business-api'
/** 发言角色展示：按出现顺序用顿号拼接 */
export function formatStoryboardSpeakerRoles(roles: string[] | null | undefined): string {
  const list = (Array.isArray(roles) ? roles : [])
    .map((r) => String(r ?? '').trim())
    .filter(Boolean)
  return list.length ? list.join('、') : '暂无'
}

/** 批量配音弹窗：优先取已绑定音色的角色段（与素材准备绑定口径一致） */
export function resolveStoryboardPrimarySpeakerVoice(
  voices: UserStoryboardSpeakerVoice[] | null | undefined
): UserStoryboardSpeakerVoice | null {
  if (!Array.isArray(voices) || !voices.length) return null
  const boundWithName = voices.find(
    (v) => v != null && v.voiceBound && String(v.voiceName ?? '').trim()
  )
  if (boundWithName) return boundWithName
  const bound = voices.find((v) => v != null && v.voiceBound)
  if (bound) return bound
  return voices.find((v) => v != null) ?? null
}

export function resolveStoryboardDubbingVoiceDisplayName(
  voice: UserStoryboardSpeakerVoice | null | undefined
): string {
  if (!voice) return '未绑定'
  const name = String(voice.voiceName ?? '').trim()
  if (name) return name
  if (voice.voiceBound) return '未命名'
  return '未绑定'
}

/**
 * 批量配音卡片：发言角色 / 配音音色展示。
 * 权威源为 POST /api/user/storyboard/list 的 speakerRoles + speakerVoices
 *（素材准备绑定音色后，由后端按台词角色标记解析回填）。
 */
export function resolveBatchDubbingCardSpeakerMeta(input: {
  speakerRoles?: string[] | null
  speakerVoices?: UserStoryboardSpeakerVoice[] | null
  fallbackSpeakerRole?: string | null
  fallbackVoiceName?: string | null
}): { speakerRole: string; voiceName: string } {
  let speakerRole = formatStoryboardSpeakerRoles(input.speakerRoles)
  if (speakerRole === '暂无') {
    const fallback = String(input.fallbackSpeakerRole ?? '').trim()
    if (fallback) speakerRole = fallback
  }
  const primaryVoice = resolveStoryboardPrimarySpeakerVoice(input.speakerVoices)
  let voiceName = resolveStoryboardDubbingVoiceDisplayName(primaryVoice)
  if (voiceName === '未绑定') {
    const fallback = String(input.fallbackVoiceName ?? '').trim()
    if (fallback) voiceName = fallback
  }
  return { speakerRole, voiceName }
}

export function storyboardRowHasDubbingDialogue(row: UserStoryboardListRow | null | undefined): boolean {
  if (!row) return false
  const dialogue = String(row.dialogueText ?? '').trim()
  if (dialogue) return true
  const voiceType = String(row.voiceType ?? '').trim()
  return !!voiceType
}

/** 列表接口未返回角色与音色时，该分镜无需进入批量配音。 */
export function storyboardRowNeedsNoDubbing(
  row: UserStoryboardListRow | null | undefined
): boolean {
  if (!row) return false
  const hasSpeakerRole = Array.isArray(row.speakerRoles) && row.speakerRoles.length > 0
  const hasSpeakerVoice = Array.isArray(row.speakerVoices) && row.speakerVoices.length > 0
  return !hasSpeakerRole && !hasSpeakerVoice
}
