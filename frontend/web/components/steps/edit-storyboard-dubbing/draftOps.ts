import { userVoiceLibraryList,userVoiceLibraryTags } from '~/utils/businessApi'
import { fallbackEmotionNameToCode } from './helpers'
import type { DubbingModalCtx } from './types'

export function persistCurrentDraft(ctx: DubbingModalCtx) {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  S.draftByIndex.set({
    ...S.draftByIndex.get(),
    [i]: {
      dialogue: S.draftDialogue.get(),
      emotion: S.draftEmotion.get(),
      lipSync: S.draftLipSync.get(),
      voiceName: S.draftVoiceName.get(),
      voiceAvatarUrl: S.draftVoiceAvatarUrl.get(),
      voiceLibraryId: S.draftVoiceLibraryId.get() > 0 ? S.draftVoiceLibraryId.get() : undefined,
      voiceModelId: S.draftVoiceModelId.get() > 0 ? S.draftVoiceModelId.get() : undefined,
      timbreCode: S.draftTimbreCode.get().trim() || undefined
    }
  })
}

export function applyVoiceFromLibraryRow(
  ctx: DubbingModalCtx,
  row: {
    id?: number
    voiceName?: string
    avatarUrl?: string
    modelId?: number
    voiceCode?: string
  }
) {
  const S = ctx.state
  const id = Number(row?.id)
  if (!Number.isFinite(id) || id <= 0) return
  S.draftVoiceName.set(String(row.voiceName || '').trim() || '未命名')
  S.draftVoiceAvatarUrl.set(String(row.avatarUrl || '').trim())
  S.draftVoiceLibraryId.set(id)
  const modelId = Number(row.modelId)
  S.draftVoiceModelId.set(Number.isFinite(modelId) && modelId > 0 ? modelId : 0)
  S.draftTimbreCode.set(String(row.voiceCode || '').trim())
}

/** 无音色时默认选音色库第一项；有展示名但缺 ID 时按名称反查 */
export async function ensureVoiceSelectionFromLibrary(ctx: DubbingModalCtx) {
  const S = ctx.state
  if (S.draftVoiceLibraryId.get() > 0) return
  try {
    const res = await userVoiceLibraryList({ pageNum: 1, pageSize: 100 })
    const list = Array.isArray(res.data) ? res.data : []
    if (!list.length) return

    const name = S.draftVoiceName.get().trim()
    if (name && name !== '无音色') {
      const hit = list.find(
        (row: { voiceName?: string }) => String(row?.voiceName || '').trim() === name
      )
      if (hit) {
        applyVoiceFromLibraryRow(ctx, hit)
        return
      }
    }

    if (!name || name === '无音色') {
      applyVoiceFromLibraryRow(ctx, list[0]!)
    }
  } catch {
    /* 列表失败时保持现状，用户可手动选音色 */
  }
}

export function loadDraftForIndex(ctx: DubbingModalCtx, i: number) {
  const S = ctx.state
  if (i < 0 || i >= ctx.props().dubbingPanels.length) {
    S.draftDialogue.set('')
    S.draftVoiceAvatarUrl.set('')
    S.draftVoiceLibraryId.set(0)
    S.draftVoiceModelId.set(0)
    S.draftTimbreCode.set('')
    S.draftVoiceProviderHint.set('')
    return
  }
  const saved = S.draftByIndex.get()[i]
  const panel = ctx.props().dubbingPanels[i]
  const script = ctx.props().storyboardScriptPanels[i]?.scriptContent?.trim() || ''
  if (saved) {
    S.draftDialogue.set(saved.dialogue)
    S.draftEmotion.set(saved.emotion)
    S.draftLipSync.set(saved.lipSync)
    S.draftVoiceName.set(saved.voiceName)
    S.draftVoiceAvatarUrl.set(saved.voiceAvatarUrl || '')
    S.draftVoiceLibraryId.set(
      saved.voiceLibraryId != null && saved.voiceLibraryId > 0 ? saved.voiceLibraryId : 0
    )
    S.draftVoiceModelId.set(
      saved.voiceModelId != null && saved.voiceModelId > 0 ? saved.voiceModelId : 0
    )
    S.draftTimbreCode.set(saved.timbreCode || '')
    void ensureVoiceSelectionFromLibrary(ctx)
    return
  }
  S.draftDialogue.set(panel?.dialogue?.trim() ? String(panel.dialogue) : script.slice(0, 50))
  S.draftEmotion.set(panel?.dubbingEmotion || '中性')
  S.draftLipSync.set(!!panel?.dubbingLipSync)
  S.draftVoiceName.set(panel?.dubbingVoiceName || '')
  S.draftVoiceAvatarUrl.set(panel?.dubbingVoiceAvatarUrl || '')
  S.draftVoiceLibraryId.set(0)
  S.draftVoiceModelId.set(0)
  S.draftTimbreCode.set('')
  void ensureVoiceSelectionFromLibrary(ctx)
}

export async function refreshEmotionTagCodeMap(ctx: DubbingModalCtx) {
  const S = ctx.state
  try {
    const data = await userVoiceLibraryTags()
    const m = new Map<string, string>()
    const labels: string[] = []
    for (const t of data.emotionTags || []) {
      const code = (t.tagCode || '').trim().toLowerCase()
      const name = String(t.tagName || t.tagCode || '').trim()
      if (name) labels.push(name)
      if (!code) continue
      m.set(code, code)
      if (t.tagName) m.set(t.tagName.trim(), code)
    }
    S.emotionNameToCode.set(m)
    if (labels.length) S.emotionLabelOptions.set(labels)
  } catch {
    S.emotionNameToCode.set(fallbackEmotionNameToCode())
  }
}
