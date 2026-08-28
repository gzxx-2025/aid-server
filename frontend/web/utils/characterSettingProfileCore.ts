/**
 * 场景 / 角色 / 道具设定 HTML（SceneSettingModal）解析、装饰与提交校验，
 * 与 `接口.md` 2.5.1～2.5.3、`SceneCharacterProp.buildUpdateMainPayloadFromSettingHtml` 保持一致。
 */
import type { UserAssetRpsUpdateMainRequest } from '../types/business-api'
import { htmlToPlainText,scriptApiTextToEditorHtml } from './htmlPlain'
/** 场景设定小节（2.5.2） */
export const SCENE_SECTION_KEYS = new Set(['概要', '场景视觉描述', '角色可落位', '人群'])

/** 道具设定小节（2.5.3） */
export const PROP_SECTION_KEYS = new Set(['道具概要', '道具视觉描述'])

/** 角色设定小节（2.5.1） */
export const CHARACTER_SECTION_KEYS = new Set([
  '角色介绍',
  '别名',
  '基本信息',
  '视觉关键词',
  '性格标签',
  '推荐色系',
  '主要识别特征',
  '子形象列表'
])

/** 分段时认可的「顶级」小节标题（角色 / 场景 / 道具）；不含基本信息内「性别：」等子标签 */
const SETTING_TOP_LEVEL_SECTION_KEYS = new Set<string>([
  ...CHARACTER_SECTION_KEYS,
  ...SCENE_SECTION_KEYS,
  ...PROP_SECTION_KEYS
])

/** 不可在编辑器内修改的小节标题（与 decorate / Quill Embed 一致） */
export const CHARACTER_LOCKED_SECTION_TITLES = new Set([
  '角色介绍',
  '基本信息',
  '视觉关键词',
  '性格标签',
  '推荐色系',
  '主要识别特征'
])

/**
 * 基本信息字段顺序（与展示/解析一致）
 * 每项：标签行样式同「一级小节标题」，内容单独占下一行
 */
export const CHARACTER_BASIC_FIELD_LABELS = [
  '性别',
  '年龄段',
  '重要性',
  '原型',
  '时代背景',
  '职业',
  '服装等级',
  '社会阶层'
] as const

function htmlToPlainPreserveLineBreaks(html: string): string {
  if (!html) return ''
  let s = html
    .replace(/<\/p>\s*<p[^>]*>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/div>\s*<div[^>]*>/gi, '\n')
    .replace(/<\/li>\s*<li[^>]*>/gi, '\n')
  s = s
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
  return s
    .split('\n')
    .map((line) => line.replace(/\s+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function normalizeSectionTitleKey(title: string): string {
  return title.replace(/\s+/g, ' ').trim().replace(/[：:]+$/u, '').trim()
}

/** 仅角色/场景/道具约定的一级小节算分段；基本信息内「性别：」等虽有 section class 但标题不在本集合 */
function isTopLevelSettingSectionTitle(title: string): boolean {
  return SETTING_TOP_LEVEL_SECTION_KEYS.has(normalizeSectionTitleKey(title))
}

/**
 * 按小节标题拆分：兼容 `<p><strong>标题</strong></p>`（接口/旧数据）
 * 与 `<p><span class="scp-char-setting-section">标题</span></p>`（弹窗锁定标题）。
 * 基本信息下的「性别：」等字段与一级标题共用 class，但不作为分段边界。
 */
export function splitSettingHtmlBySectionTitles(html: string): Record<string, string> {
  const out: Record<string, string> = {}
  if (!html?.trim()) return out
  const reStrong = /<p[^>]*>\s*<strong[^>]*>\s*([^<]+?)\s*<\/strong>\s*<\/p>/gi
  const reSectionSpan =
    /<p[^>]*>\s*<span[^>]*\bscp-char-setting-section\b[^>]*>\s*([^<]+?)\s*<\/span>\s*<\/p>/gi
  const matches: { title: string; start: number; headerEnd: number }[] = []
  let m: RegExpExecArray | null
  while ((m = reStrong.exec(html)) !== null) {
    const title = m[1].trim()
    if (!isTopLevelSettingSectionTitle(title)) continue
    matches.push({ title, start: m.index, headerEnd: m.index + m[0].length })
  }
  while ((m = reSectionSpan.exec(html)) !== null) {
    const title = m[1].trim()
    if (!isTopLevelSettingSectionTitle(title)) continue
    matches.push({ title, start: m.index, headerEnd: m.index + m[0].length })
  }
  matches.sort((a, b) => a.start - b.start)
  for (let i = 0; i < matches.length; i++) {
    const bodyStart = matches[i].headerEnd
    const bodyEnd = i + 1 < matches.length ? matches[i + 1].start : html.length
    out[matches[i].title] = htmlToPlainPreserveLineBreaks(html.slice(bodyStart, bodyEnd))
  }
  return out
}

export function splitTagLikePlain(text: string): string[] {
  if (!text?.trim()) return []
  return text
    .split(/[,，、|/\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 判定是否已按某类资产约定小节拆分（至少命中一个小节 key） */
export function sectionsLookStructuredFor(
  sections: Record<string, string>,
  keys: ReadonlySet<string>
): boolean {
  return Object.keys(sections).some((k) => keys.has(k))
}

/**
 * 解析场景「人群」小节（与 SceneCharacterProp 原 parseCrowdSection 一致）
 */
export function parseSceneCrowdSection(
  text: string
): Pick<UserAssetRpsUpdateMainRequest, 'hasCrowd' | 'crowdDescription'> {
  const t = (text || '').trim()
  const empty: Pick<UserAssetRpsUpdateMainRequest, 'hasCrowd' | 'crowdDescription'> = {}
  if (!t) return empty
  if (/^有人群[：:]/.test(t)) {
    return { hasCrowd: 1, crowdDescription: t.replace(/^有人群[：:]\s*/, '').trim() }
  }
  if (t === '有人群') return { hasCrowd: 1, crowdDescription: '' }
  if (/^无人群[：:]/.test(t)) {
    return { hasCrowd: 0, crowdDescription: t.replace(/^无人群[：:]\s*/, '').trim() }
  }
  if (t === '无人群') return { hasCrowd: 0, crowdDescription: '' }
  return { crowdDescription: t }
}

/**
 * 解析「基本信息」纯文本：支持
 * - 新版：标签独占一行，下一行为值；
 * - 旧版：同一行「性别：男」。
 */
export function parseCharacterBasicsSection(text: string): Partial<UserAssetRpsUpdateMainRequest> {
  const o: Partial<UserAssetRpsUpdateMainRequest> = {}
  if (!text?.trim()) return o

  const lines = text.split('\n').map((l) => l.replace(/\u200B/g, '').trim())

  const fields: Array<{
    label: string
    key: keyof UserAssetRpsUpdateMainRequest
    numeric?: boolean
  }> = [
    { label: '性别', key: 'gender' },
    { label: '年龄段', key: 'ageRange' },
    { label: '重要性', key: 'roleLevel' },
    { label: '原型', key: 'archetype' },
    { label: '时代背景', key: 'eraPeriod' },
    { label: '职业', key: 'occupation' },
    { label: '服装等级', key: 'costumeTier', numeric: true },
    { label: '社会阶层', key: 'socialClass' }
  ]

  const startsFieldLabel = (line: string, label: string) =>
    new RegExp(`^${escapeRe(label)}[：:]`).test(line.trim())

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (!line) continue

    for (const { label, key, numeric } of fields) {
      if (!startsFieldLabel(line, label)) continue

      const m = line.match(new RegExp(`^${escapeRe(label)}[：:]\\s*(.*)$`))
      if (!m) continue

      const inlineRest = (m[1] || '').trim()

      if (inlineRest.length > 0) {
        if (numeric) {
          const n = inlineRest.match(/^(\d+)$/)
          if (n) (o as Record<string, unknown>)[key as string] = Number(n[1])
        } else {
          (o as Record<string, string>)[key as string] = inlineRest
        }
      } else {
        let j = i + 1
        while (j < lines.length && lines[j] === '') j++
        if (j >= lines.length) {
          if (!numeric) (o as Record<string, string>)[key as string] = ''
        } else {
          const nextLine = lines[j]
          const nextIsLabel = fields.some(
            (f) => f.label !== label && startsFieldLabel(nextLine, f.label)
          )
          if (nextIsLabel) {
            if (!numeric) (o as Record<string, string>)[key as string] = ''
          } else if (numeric) {
            const n = nextLine.trim().match(/^(\d+)$/)
            if (n) (o as Record<string, unknown>)[key as string] = Number(n[1])
          } else {
            (o as Record<string, string>)[key as string] = nextLine.trim()
          }
        }
      }
      break
    }
  }
  return o
}

export function sectionsLookStructured(sections: Record<string, string>): boolean {
  return sectionsLookStructuredFor(sections, CHARACTER_SECTION_KEYS)
}

export function profilePlainFromSettingHtml(html: string): { introduction: string; summary: string } {
  const introduction = htmlToPlainText(html ?? '').trim()
  const summary =
    introduction.length > 300 ? `${introduction.slice(0, 300).trim()}…` : introduction
  return { introduction, summary }
}

function settingBlock(label: string, body: string): string {
  const b = (body || '').trim()
  if (!b) return ''
  return `<p><strong>${label}</strong></p>${scriptApiTextToEditorHtml(b)}`
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function escapeInlineHtml(s: string): string {
  return (s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * 「基本信息」字段：标签单独一行，样式与一级小节标题一致（`scp-char-setting-section` → Quill 锁定）；
 * 可编辑内容单独下一行（普通段落，白色正文）。
 * 兼容纯文本已拆成「标签行 + 值行」的旧拆分结果。
 */
export function decorateCharacterBasicsPlainToHtml(plain: string): string {
  const lines = plain.split('\n')
  const out: string[] = []
  let i = 0

  const isFieldLabelStart = (s: string) =>
    CHARACTER_BASIC_FIELD_LABELS.some((lab) =>
      new RegExp(`^${escapeRe(lab)}[：:]`).test(s.trim())
    )

  while (i < lines.length) {
    const raw = lines[i]
    const line = raw.replace(/\s+$/, '')
    const t = line.trim()

    if (!t) {
      out.push('<p><br /></p>')
      i++
      continue
    }

    let fieldHandled = false
    for (const lab of CHARACTER_BASIC_FIELD_LABELS) {
      const re = new RegExp(`^(${escapeRe(lab)})([：:])(.*)$`)
      const mm = t.match(re)
      if (!mm) continue

      fieldHandled = true
      let val = mm[3].trim().replace(/\u200B/g, '').trim()

      if (!val) {
        let j = i + 1
        while (j < lines.length && !lines[j].trim()) j++
        if (j < lines.length) {
          const next = lines[j].trim()
          if (!isFieldLabelStart(next)) {
            val = next.replace(/\u200B/g, '').trim()
            i = j
          }
        }
      }

      out.push(`<p><span class="scp-char-setting-section">${mm[1]}${mm[2]}</span></p>`)
      out.push(val ? `<p>${escapeInlineHtml(val)}</p>` : '<p><br /></p>')
      break
    }

    if (!fieldHandled) {
      out.push(scriptApiTextToEditorHtml(t))
    }
    i++
  }
  return out.join('')
}

/** 打开弹窗时：锁定小节标题与基本信息字段名为原子 Embed 所需 HTML */
export function decorateCharacterSettingHtml(html: string): string {
  const trimmed = (html || '').trim()
  if (!trimmed) return ''
  const sections = splitSettingHtmlBySectionTitles(trimmed)
  if (!sectionsLookStructured(sections)) return trimmed

  const sectionOrder = [
    '角色介绍',
    '别名',
    '基本信息',
    '视觉关键词',
    '性格标签',
    '推荐色系',
    '主要识别特征',
    '子形象列表'
  ]
  const parts: string[] = []
  for (const title of sectionOrder) {
    if (!(title in sections)) continue
    const body = sections[title] ?? ''
    if (title === '基本信息') {
      parts.push(
        `<p><span class="scp-char-setting-section">${title}</span></p>${decorateCharacterBasicsPlainToHtml(body)}`
      )
    } else if (CHARACTER_LOCKED_SECTION_TITLES.has(title)) {
      parts.push(
        `<p><span class="scp-char-setting-section">${title}</span></p>${scriptApiTextToEditorHtml(body)}`
      )
    } else {
      parts.push(settingBlock(title, body))
    }
  }
  for (const k of Object.keys(sections)) {
    if (sectionOrder.includes(k)) continue
    parts.push(settingBlock(k, sections[k] ?? ''))
  }
  return parts.join('')
}

/** 打开场景设定弹窗：锁定各小节标题为原子 Embed（与角色一级小节同色） */
export function decorateSceneSettingHtml(html: string): string {
  const trimmed = (html || '').trim()
  if (!trimmed) return ''
  const sections = splitSettingHtmlBySectionTitles(trimmed)
  if (!sectionsLookStructuredFor(sections, SCENE_SECTION_KEYS)) return trimmed
  const sectionOrder = ['概要', '场景视觉描述', '角色可落位', '人群']
  const parts: string[] = []
  for (const title of sectionOrder) {
    if (!(title in sections)) continue
    const body = sections[title] ?? ''
    parts.push(
      `<p><span class="scp-char-setting-section">${title}</span></p>${scriptApiTextToEditorHtml(body)}`
    )
  }
  for (const k of Object.keys(sections)) {
    if (sectionOrder.includes(k)) continue
    parts.push(settingBlock(k, sections[k] ?? ''))
  }
  return parts.join('')
}

/** 打开道具设定弹窗：锁定各小节标题为原子 Embed */
export function decoratePropSettingHtml(html: string): string {
  const trimmed = (html || '').trim()
  if (!trimmed) return ''
  const sections = splitSettingHtmlBySectionTitles(trimmed)
  if (!sectionsLookStructuredFor(sections, PROP_SECTION_KEYS)) return trimmed
  const sectionOrder = ['道具概要', '道具视觉描述']
  const parts: string[] = []
  for (const title of sectionOrder) {
    if (!(title in sections)) continue
    const body = sections[title] ?? ''
    parts.push(
      `<p><span class="scp-char-setting-section">${title}</span></p>${scriptApiTextToEditorHtml(body)}`
    )
  }
  for (const k of Object.keys(sections)) {
    if (sectionOrder.includes(k)) continue
    parts.push(settingBlock(k, sections[k] ?? ''))
  }
  return parts.join('')
}

/** 从设定 HTML 提取场景 update-main 平铺字段（与 buildUpdateMainPayload scene 分支一致） */
export function extractScenePayloadFromSettingHtml(html: string): Partial<UserAssetRpsUpdateMainRequest> {
  const sections = splitSettingHtmlBySectionTitles(html || '')
  if (sectionsLookStructuredFor(sections, SCENE_SECTION_KEYS)) {
    const payload: Partial<UserAssetRpsUpdateMainRequest> = {}
    const hasSummary = '概要' in sections
    const hasIntro = '场景视觉描述' in sections
    if (hasIntro && !hasSummary) {
      Object.assign(
        payload,
        profilePlainFromSettingHtml(scriptApiTextToEditorHtml(sections['场景视觉描述'] ?? ''))
      )
    } else if (hasSummary && !hasIntro) {
      Object.assign(
        payload,
        profilePlainFromSettingHtml(scriptApiTextToEditorHtml(sections['概要'] ?? ''))
      )
    } else {
      if ('概要' in sections) payload.summary = sections['概要'] ?? ''
      if ('场景视觉描述' in sections) payload.introduction = sections['场景视觉描述'] ?? ''
    }
    if ('角色可落位' in sections) {
      const slots = (sections['角色可落位'] ?? '')
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean)
      payload.availableSlots = JSON.stringify(slots)
    }
    if ('人群' in sections) {
      Object.assign(payload, parseSceneCrowdSection(sections['人群'] ?? ''))
    }
    return payload
  }
  return profilePlainFromSettingHtml(html || '')
}

/** 从设定 HTML 提取道具 update-main 平铺字段 */
export function extractPropPayloadFromSettingHtml(html: string): Partial<UserAssetRpsUpdateMainRequest> {
  const sections = splitSettingHtmlBySectionTitles(html || '')
  if (sectionsLookStructuredFor(sections, PROP_SECTION_KEYS)) {
    const payload: Partial<UserAssetRpsUpdateMainRequest> = {}
    const hasSum = '道具概要' in sections
    const hasIntro = '道具视觉描述' in sections
    if (hasIntro && !hasSum) {
      Object.assign(
        payload,
        profilePlainFromSettingHtml(scriptApiTextToEditorHtml(sections['道具视觉描述'] ?? ''))
      )
    } else if (hasSum && !hasIntro) {
      Object.assign(
        payload,
        profilePlainFromSettingHtml(scriptApiTextToEditorHtml(sections['道具概要'] ?? ''))
      )
    } else {
      if ('道具概要' in sections) payload.summary = sections['道具概要'] ?? ''
      if ('道具视觉描述' in sections) payload.introduction = sections['道具视觉描述'] ?? ''
    }
    return payload
  }
  return profilePlainFromSettingHtml(html || '')
}

export function parseCharacterDisplayName(full: string): string {
  const t = (full || '').trim()
  const m = t.match(/^角色\d+:\s*(.+)$/)
  return (m ? m[1] : t).trim()
}

export function parseSceneDisplayName(full: string): string {
  const t = (full || '').trim()
  const m = t.match(/^场景\d*[:：]\s*(.+)$/u) || t.match(/^场景[:：]\s*(.+)$/u)
  return (m ? m[1] : t).trim()
}

export function parsePropDisplayName(full: string): string {
  const t = (full || '').trim()
  const m = t.match(/^道具\d*[:：]\s*(.+)$/u) || t.match(/^道具[:：]\s*(.+)$/u)
  return (m ? m[1] : t).trim()
}

