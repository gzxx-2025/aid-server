/**
 * 角色设定卡参考图：平台生成（含右侧编辑/对话生图 ai_edit_chat、多机位等）或本地上传可用；
 * 设定卡产物 ai_builder、官方库图不可用。
 */

/** 已是设定卡结果，不能再作为参考底图 */
const SETTING_CARD_RESULT_SOURCE_TYPES = new Set(['ai_builder'])

export function normalizeSettingCardBaseSourceType(raw: unknown): string {
  const s = String(raw || '')
    .trim()
    .toLowerCase()
  if (!s) return ''
  if (s === 'ai') return 'ai_auto'
  if (s === 'upload' || s === 'local') return 'upload'
  return s
}

/**
 * 解析形态图来源（供设定卡可用性判断）。
 * 注意：列表映射常把服务端图标成 source=server，不能当成 official。
 */
export function resolveSettingCardBaseSourceType(img: unknown): string {
  if (!img || typeof img !== 'object') return ''
  const row = img as Record<string, unknown>
  const server = normalizeSettingCardBaseSourceType(
    row._serverSourceType ?? row.sourceType ?? row.source_type
  )
  if (server) return server

  const rps = normalizeSettingCardBaseSourceType(row._rpsSourceType)
  if (rps) return rps

  const label = String(row.source || '')
    .trim()
    .toLowerCase()
  if (!label) return ''
  // 弹窗/列表落库后的占位 source，视为平台生成（与右侧生图同源）
  if (label === 'server' || /自动|对话|生成|编辑|\bai\b/.test(label)) return 'ai_edit_chat'
  if (/本地|本地上传|upload/.test(label)) return 'upload'
  if (/官方|official|资源库/.test(label)) return 'official'
  return ''
}

/** 平台 AI 产出（ai_auto / ai_edit_chat / ai_multi_view / ai_manual…）或本地上传 */
export function isSettingCardBaseSourceType(sourceType: unknown): boolean {
  const t = normalizeSettingCardBaseSourceType(sourceType)
  if (!t || SETTING_CARD_RESULT_SOURCE_TYPES.has(t)) return false
  if (t === 'upload') return true
  if (t === 'official' || t === 'migrate') return false
  // ai_auto / ai_edit_chat / ai_multi_view / ai_manual 等
  return t === 'ai' || t.startsWith('ai_')
}

/** 当前选中图是否可作为设定卡参考底图（有 url、有 rpsImageId、来源合法） */
export function isSettingCardBaseImage(img: unknown): boolean {
  const row = img as { rpsImageId?: number; url?: string; thumbnail?: string } | null
  const url = String(row?.url ?? row?.thumbnail ?? '').trim()
  if (!url) return false
  const rid = Number(row?.rpsImageId)
  if (!Number.isFinite(rid) || rid <= 0) return false
  return isSettingCardBaseSourceType(resolveSettingCardBaseSourceType(img))
}
