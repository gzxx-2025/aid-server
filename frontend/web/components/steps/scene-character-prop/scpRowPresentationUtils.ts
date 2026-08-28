import type {
UserAssetRpsFormRow,
UserAssetRpsRow
} from '~/types/business-api'
import { scriptApiTextToEditorHtml } from '~/utils/htmlPlain'
import type { TabKey } from './types'
export function delayMs(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 主表嵌套 forms（rps/list 已批量返回形态与使用中图片） */
export function getFormsForRpsRow(raw: UserAssetRpsRow): UserAssetRpsFormRow[] {
  return raw.forms ?? []
}

export function assetHasPersistedForm(raw: UserAssetRpsRow): boolean {
  return getFormsForRpsRow(raw).some((f) => Number.isFinite(Number(f?.id)) && Number(f?.id) > 0)
}

export function safeStr(v: unknown): string {
  if (typeof v === 'string') return v.trim()
  if (v == null) return ''
  return String(v).trim()
}

/** 主表 summary 与 introduction 全文相同则弹窗只展示一块，避免「概要/视觉描述」重复 */
export function sameMainSummaryAndIntroduction(summary: string, intro: string): boolean {
  const a = summary.trim()
  const b = intro.trim()
  return Boolean(a && b && a === b)
}

export function parseStringListField(v: unknown): string[] {
  if (Array.isArray(v)) return v.map((x) => String(x).trim()).filter(Boolean)
  if (typeof v === 'string' && v.trim()) {
    const t = v.trim()
    if (t.startsWith('[')) {
      try {
        const p = JSON.parse(t) as unknown
        if (Array.isArray(p)) return p.map((x) => String(x).trim()).filter(Boolean)
      } catch {
        /* ignore */
      }
    }
    return t
      .split(/[,，、|\n]/)
      .map((s) => s.trim())
      .filter(Boolean)
  }
  return []
}

export function stringListFromRowField(v: unknown): string[] {
  if (Array.isArray(v)) return v.map((x) => String(x).trim()).filter(Boolean)
  return parseStringListField(v)
}

export function formatTagList(arr: string[], sep = '、'): string {
  if (!arr?.length) return ''
  return arr
    .map((x) => String(x).trim())
    .filter(Boolean)
    .join(sep)
}

export function parseAvailableSlots(v: UserAssetRpsRow['availableSlots']): string[] {
  if (Array.isArray(v)) return v.map((x) => String(x).trim()).filter(Boolean)
  if (typeof v === 'string' && v.trim()) {
    const t = v.trim()
    if (t.startsWith('[')) {
      try {
        const p = JSON.parse(t) as unknown
        if (Array.isArray(p)) return p.map((x) => String(x).trim()).filter(Boolean)
      } catch {
        /* ignore */
      }
    }
    return t
      .split(/\n/)
      .map((s) => s.trim())
      .filter(Boolean)
  }
  return []
}

export function expectedAppearancesPlain(v: UserAssetRpsRow['expectedAppearances']): string {
  if (!v) return ''
  if (Array.isArray(v)) {
    return v
      .map((item) => {
        if (item && typeof item === 'object') {
          const o = item as { id?: number; label?: string; name?: string }
          const label = safeStr(o.label) || safeStr(o.name)
          if (!label) return ''
          const idPart = o.id != null && Number.isFinite(Number(o.id)) ? `#${o.id} ` : ''
          return `${idPart}${label}`.trim()
        }
        return String(item).trim()
      })
      .filter(Boolean)
      .join('\n')
  }
  if (typeof v === 'string' && v.trim()) {
    try {
      const p = JSON.parse(v) as unknown
      if (Array.isArray(p))
        return expectedAppearancesPlain(p as UserAssetRpsRow['expectedAppearances'])
    } catch {
      /* ignore */
    }
    return v.trim()
  }
  return ''
}

export function settingBlock(label: string, body: string): string {
  const b = (body || '').trim()
  if (!b) return ''
  return `<p><strong>${label}</strong></p>${scriptApiTextToEditorHtml(b)}`
}

/** 将 /api/user/asset/rps/list v2.25+ 主表结构化字段拼成设定弹窗富文本 */
export function settingEditorHtmlFromRpsMainRow(raw: UserAssetRpsRow, variant: TabKey): string {
  const parts: string[] = []
  const rowType = String(raw.assetType || variant).toLowerCase()

  if (variant === 'scene' || rowType === 'scene') {
    const summary = safeStr(raw.summary)
    const intro = safeStr(raw.introduction)
    if (sameMainSummaryAndIntroduction(summary, intro)) {
      parts.push(settingBlock('场景视觉描述', intro))
    } else {
      if (summary) parts.push(settingBlock('概要', summary))
      if (intro) parts.push(settingBlock('场景视觉描述', intro))
    }
    const slots = parseAvailableSlots(raw.availableSlots)
    if (slots.length) parts.push(settingBlock('角色可落位', slots.join('\n')))
    const hasCrowd = raw.hasCrowd
    const crowdDesc = safeStr(raw.crowdDescription)
    // 无人群且无说明时不展示「人群」小节（避免仅显示「无人群」占版面）
    if (hasCrowd === 1) {
      parts.push(settingBlock('人群', crowdDesc ? `有人群：${crowdDesc}` : '有人群'))
    } else if (crowdDesc) {
      parts.push(settingBlock('人群', hasCrowd === 0 ? `无人群：${crowdDesc}` : crowdDesc))
    }
  } else if (variant === 'character' || rowType === 'character') {
    const intro = safeStr(raw.introduction)
    if (intro) parts.push(settingBlock('角色介绍', intro))
    const aliases = safeStr(raw.aliasesName)
    if (aliases) parts.push(settingBlock('别名', aliases.replace(/,/g, '、')))
    const basics: string[] = []
    const g = safeStr(raw.gender)
    if (g) basics.push(`性别：${g}`)
    const ar = safeStr(raw.ageRange)
    if (ar) basics.push(`年龄段：${ar}`)
    const rl = safeStr(raw.roleLevel)
    if (rl) basics.push(`重要性：${rl}`)
    const arch = safeStr(raw.archetype)
    if (arch) basics.push(`原型：${arch}`)
    const era = safeStr(raw.eraPeriod)
    if (era) basics.push(`时代背景：${era}`)
    const occ = safeStr(raw.occupation)
    if (occ) basics.push(`职业：${occ}`)
    const ct = raw.costumeTier
    if (ct != null && String(ct).trim()) basics.push(`服装等级：${String(ct).trim()}`)
    const sc = safeStr(raw.socialClass)
    if (sc) basics.push(`社会阶层：${sc}`)
    if (basics.length) parts.push(settingBlock('基本信息', basics.join('\n')))
    const vk = formatTagList(stringListFromRowField(raw.visualKeywords))
    if (vk) parts.push(settingBlock('视觉关键词', vk))
    const pt = formatTagList(stringListFromRowField(raw.personalityTags))
    if (pt) parts.push(settingBlock('性格标签', pt))
    const scol = formatTagList(stringListFromRowField(raw.suggestedColors))
    if (scol) parts.push(settingBlock('推荐色系', scol))
    const pid = safeStr(raw.primaryIdentifier)
    if (pid) parts.push(settingBlock('主要识别特征', pid))
    const exp = expectedAppearancesPlain(raw.expectedAppearances)
    if (exp) parts.push(settingBlock('子形象列表', exp))
  } else {
    const summary = safeStr(raw.summary)
    const intro = safeStr(raw.introduction)
    if (sameMainSummaryAndIntroduction(summary, intro)) {
      parts.push(settingBlock('道具视觉描述', intro))
    } else {
      if (summary) parts.push(settingBlock('道具概要', summary))
      if (intro) parts.push(settingBlock('道具视觉描述', intro))
    }
  }

  let html = parts.join('')
  if (!html.trim() && raw.profileData?.trim()) {
    const pd = raw.profileData.trim()
    try {
      const j = JSON.parse(pd) as Record<string, unknown>
      const lines: string[] = []
      for (const [k, val] of Object.entries(j)) {
        if (val == null || val === '') continue
        const s =
          typeof val === 'string'
            ? val
            : Array.isArray(val)
              ? (val as unknown[]).map((x) => String(x)).join('、')
              : typeof val === 'object'
                ? JSON.stringify(val)
                : String(val)
        if (s.trim()) lines.push(`${k}：${s}`)
      }
      if (lines.length) html = scriptApiTextToEditorHtml(lines.join('\n\n'))
    } catch {
      html = scriptApiTextToEditorHtml(pd)
    }
  }
  return html
}

/** form-image/create 返回字段兼容：新后端返回 imgId，旧结构仍可能返回 id */
export function resolveCreatedFormImageId(created: any): number | null {
  const imgId = created?.imgId
  if (imgId != null && Number.isFinite(Number(imgId))) return Number(imgId)
  return null
}

/** form/list 单行兼容：部分后端 VO 未带 assetId 或 id 写在 formId */
export function normalizeFormRowFromApi(
  f: UserAssetRpsFormRow & { formId?: number }
): UserAssetRpsFormRow | null {
  const rawId = f?.id ?? f?.formId
  if (rawId == null || !Number.isFinite(Number(rawId))) return null
  return { ...f, id: Number(rawId) }
}

// 获取场景前缀（如"场景2:"）
export const getScenePrefix = (name: string) => {
  const match = name.match(/^(场景\d+):/)
  return match ? match[1] + ':' : ''
}

// 获取场景名称（去掉前缀）
export const getSceneName = (name: string) => {
  const match = name.match(/^场景\d+:\s*(.+)$/)
  return match ? match[1] : name
}

export const getCharacterPrefix = (name: string) => {
  const match = name.match(/^(角色\d+):/)
  return match ? match[1] + ':' : ''
}

export const getCharacterName = (name: string) => {
  const match = name.match(/^角色\d+:\s*(.+)$/)
  return match ? match[1] : name
}

export const getFormPrefix = (name: string) => {
  const match = name.match(/^(形态\d+):/)
  return match ? match[1] + ':' : ''
}

export const getFormName = (name: string) => {
  const match = name.match(/^形态\d+:\s*(.+)$/)
  return match ? match[1] : name
}

export const getPropPrefix = (name: string) => {
  const match = name.match(/^(道具\d+):/)
  return match ? match[1] + ':' : ''
}

export const getPropName = (name: string) => {
  const match = name.match(/^道具\d+:\s*(.+)$/)
  return match ? match[1] : name
}

export const getPropFormPrefix = (name: string) => {
  const match = name.match(/^(形态\d+):/)
  return match ? match[1] + ':' : ''
}

export const getPropFormName = (name: string) => {
  const match = name.match(/^形态\d+:\s*(.+)$/)
  return match ? match[1] : name
}

export function collectRpsImageIdsFromImageRows(imgs: unknown[]): number[] {
  if (!Array.isArray(imgs)) return []
  return imgs
    .map((img) =>
      Number(
        (img as { rpsImageId?: number; id?: number })?.rpsImageId ?? (img as { id?: number })?.id
      )
    )
    .filter((n) => Number.isFinite(n) && n > 0)
}

export function numRecordClone(rec: Record<number, any[]>): Record<number, any[]> {
  const o: Record<number, any[]> = {}
  for (const k of Object.keys(rec)) {
    const arr = (rec as Record<number, any[]>)[Number(k)]
    if (Array.isArray(arr)) o[Number(k)] = arr.map((x: any) => ({ ...x }))
  }
  return o
}

export function strRecordClone(rec: Record<string, any[]>): Record<string, any[]> {
  const o: Record<string, any[]> = {}
  for (const k of Object.keys(rec)) {
    if (Array.isArray(rec[k])) o[k] = rec[k].map((x: any) => ({ ...x }))
  }
  return o
}

export function formsNumClone<T extends { name: string }>(
  rec: Record<number, T[]>
): Record<number, T[]> {
  const o: Record<number, T[]> = {}
  for (const k of Object.keys(rec)) {
    o[Number(k)] = (rec[Number(k)] || []).map((f) => ({ ...f }))
  }
  return o
}
