/** 分镜图描述框内 @构图 / @景别 等参数引用（无缩略图，可点击切换） */

import { PROMPT_TYPE } from '~/composables/usePromptDictionary'
import {
  findParamGroup,
  findParamOptionByTag,
  PARAM_TYPE_ORDER,
  selectionToParamRef,
  stripAt,
  type PromptParamGroup,
  type PromptParamOption,
  type PromptParamRefValue,
  type PromptParamType
} from './storyboardPromptParamCore'

export * from './storyboardPromptParamCore'

const API_IMAGE_PLACEHOLDER_RE = /@图片\d+\[[^\]]+\]/g

type LabeledFieldDef = { paramType: PromptParamType; labels: string[] }

/** 分镜图提示词结构化字段（与接口「画面描述：/景别：/画面风格：」等格式一致） */
const IMAGE_LABELED_FIELD_DEFS: LabeledFieldDef[] = [
  { paramType: PROMPT_TYPE.composition, labels: ['构图方式', '构图'] },
  { paramType: PROMPT_TYPE.shot_size, labels: ['景别'] },
  { paramType: PROMPT_TYPE.camera_angle, labels: ['拍摄角度', '角度'] },
  { paramType: PROMPT_TYPE.focal_length, labels: ['镜头焦距', '焦距'] },
  { paramType: PROMPT_TYPE.color_tone, labels: ['色彩色调', '色调'] },
  { paramType: PROMPT_TYPE.lighting, labels: ['光线'] },
  { paramType: PROMPT_TYPE.exposure_blur, labels: ['曝光虚化', '摄影技法'] }
]

const IMAGE_SECTION_STOP_LABELS = [
  '画面描述',
  '人物表现',
  '画面风格',
  '构图方式',
  '构图',
  '景别',
  '拍摄角度',
  '角度',
  '镜头焦距',
  '焦距',
  '色彩色调',
  '色调',
  '光线',
  '曝光虚化',
  '摄影技法',
  '镜头运动',
  '特殊拍摄手法',
  '拍摄手法'
]

/** 分镜视频提示词结构化字段（与接口「镜头运动：/画面描述：」格式一致） */
const VIDEO_LABELED_FIELD_DEFS: LabeledFieldDef[] = [
  { paramType: PROMPT_TYPE.camera_movement, labels: ['镜头运动'] },
  { paramType: PROMPT_TYPE.shooting_technique, labels: ['特殊拍摄手法', '拍摄手法'] }
]

const VIDEO_SECTION_STOP_LABELS = [
  '镜头运动',
  '画面描述',
  '人物表现',
  '画面风格',
  '特殊拍摄手法',
  '拍摄手法',
  '构图方式',
  '构图',
  '景别',
  '拍摄角度',
  '角度',
  '镜头焦距',
  '焦距',
  '色彩色调',
  '色调',
  '光线',
  '曝光虚化',
  '摄影技法'
]

function isLabeledSectionHeader(plain: string, label: string, index: number): boolean {
  if (index < 0) return false
  const ch = plain[index + label.length]
  return ch === '：' || ch === ':'
}

/** 提取「景别：」等标签后的正文片段及其在原文中的起始下标 */
function extractLabeledSectionBody(
  plain: string,
  fieldLabel: string,
  stopLabels: string[]
): { body: string; bodyStart: number; bodyEnd: number } | null {
  const idx = plain.indexOf(fieldLabel)
  if (idx < 0 || !isLabeledSectionHeader(plain, fieldLabel, idx)) return null

  let bodyStart = idx + fieldLabel.length
  if (plain[bodyStart] === '：' || plain[bodyStart] === ':') bodyStart += 1
  while (bodyStart < plain.length && /\s/.test(plain[bodyStart] ?? '')) bodyStart += 1

  let bodyEnd = plain.length
  for (const stop of stopLabels) {
    if (stop === fieldLabel) continue
    let from = bodyStart
    while (from < plain.length) {
      const stopIdx = plain.indexOf(stop, from)
      if (stopIdx < 0) break
      if (isLabeledSectionHeader(plain, stop, stopIdx)) {
        bodyEnd = Math.min(bodyEnd, stopIdx)
        break
      }
      from = stopIdx + stop.length
    }
  }

  const body = plain.slice(bodyStart, bodyEnd).trim()
  if (!body) return null
  return { body, bodyStart, bodyEnd }
}

/** 在自然语言片段中匹配词库项（支持「固定镜头」≈「固定机位」等近似表述） */
function matchParamOptionInFragment(
  fragment: string,
  group: PromptParamGroup
): { option: PromptParamOption; matchedPhrase: string } | null {
  const text = String(fragment || '').trim()
  if (!text) return null

  const byTag = findParamOptionByTag([group], text)
  if (byTag) {
    return { option: byTag.option, matchedPhrase: stripAt(byTag.option.value) }
  }

  const candidates = group.options
    .filter((o) => o.key !== 'none')
    .map((o) => ({ option: o, label: stripAt(o.value) }))
    .filter((c) => c.label)
    .sort((a, b) => b.label.length - a.label.length)

  for (const { option, label } of candidates) {
    if (text.includes(label)) return { option, matchedPhrase: label }
  }

  const firstClause = text.split(/[，,。；;\n]/)[0]?.trim() || ''
  if (!firstClause) return null

  for (const { option, label } of candidates) {
    if (label.includes(firstClause) || firstClause.includes(label)) {
      return { option, matchedPhrase: firstClause }
    }
    if (firstClause.length >= 2 && label.length >= 2 && label.slice(0, 2) === firstClause.slice(0, 2)) {
      return { option, matchedPhrase: firstClause }
    }
  }
  return null
}

export interface ParamRefTextRange {
  start: number
  end: number
  ref: PromptParamRefValue
}

/**
 * 参数 embed 转纯文本时会保留 @ 前缀（如 @引导线法）。
 * 再次渲染为标签块时需把紧邻的 @（及中间空白）一并替换，避免出现 @@。
 */
function expandParamPhraseRangeInPlain(
  text: string,
  phraseStart: number,
  phraseEnd: number
): { start: number; end: number } {
  let start = phraseStart
  const end = phraseEnd
  while (start > 0) {
    const ch = text[start - 1]
    if (ch === '@' || /\s/.test(ch ?? '')) {
      start -= 1
      continue
    }
    break
  }
  return { start, end }
}

function collectLabeledParamRefRanges(
  plain: string,
  groups: PromptParamGroup[],
  fieldDefs: LabeledFieldDef[],
  stopLabels: string[]
): ParamRefTextRange[] {
  const text = String(plain || '')
  if (!text) return []

  const ranges: ParamRefTextRange[] = []
  const usedTypes = new Set<PromptParamType>()

  for (const field of fieldDefs) {
    if (usedTypes.has(field.paramType)) continue
    const group = findParamGroup(groups, field.paramType)
    if (!group) continue

    for (const label of field.labels) {
      const section = extractLabeledSectionBody(text, label, stopLabels)
      if (!section) continue

      const match = matchParamOptionInFragment(section.body, group)
      if (!match) continue

      const phraseStart = text.indexOf(match.matchedPhrase, section.bodyStart)
      if (phraseStart < 0 || phraseStart + match.matchedPhrase.length > section.bodyEnd) continue

      const ref = selectionToParamRef(field.paramType, match.option)
      if (!ref) continue

      const expanded = expandParamPhraseRangeInPlain(
        text,
        phraseStart,
        phraseStart + match.matchedPhrase.length
      )
      if (expanded.end > section.bodyEnd) continue

      ranges.push({
        start: expanded.start,
        end: expanded.end,
        ref
      })
      usedTypes.add(field.paramType)
      break
    }
  }

  return ranges.sort((a, b) => a.start - b.start)
}

function extractLabeledParamSelectionsFromPlain(
  plain: string,
  groups: PromptParamGroup[],
  fieldDefs: LabeledFieldDef[],
  stopLabels: string[]
): Partial<Record<PromptParamType, { key: string; value: string }>> {
  const result: Partial<Record<PromptParamType, { key: string; value: string }>> = {
    ...extractParamSelectionsFromPlain(plain, groups)
  }

  for (const field of fieldDefs) {
    if (result[field.paramType]) continue
    const group = findParamGroup(groups, field.paramType)
    if (!group) continue

    for (const label of field.labels) {
      const section = extractLabeledSectionBody(plain, label, stopLabels)
      if (!section) continue
      const match = matchParamOptionInFragment(section.body, group)
      if (match) {
        result[field.paramType] = { key: match.option.key, value: match.option.value }
        break
      }
    }
  }

  return result
}

function plainHasLabeledParamFields(
  plain: string,
  fieldDefs: LabeledFieldDef[],
  stopLabels: string[]
): boolean {
  const text = String(plain || '')
  return fieldDefs.some((field) =>
    field.labels.some((label) => extractLabeledSectionBody(text, label, stopLabels) != null)
  )
}

/** 收集分镜图提示词「景别：/构图：」等字段中可渲染为可点击标签的文本区间 */
export function collectImageLabeledParamRefRanges(
  plain: string,
  groups: PromptParamGroup[]
): ParamRefTextRange[] {
  return collectLabeledParamRefRanges(plain, groups, IMAGE_LABELED_FIELD_DEFS, IMAGE_SECTION_STOP_LABELS)
}

/** 收集视频提示词「镜头运动：xxx」等字段中可渲染为可点击标签的文本区间 */
export function collectVideoLabeledParamRefRanges(
  plain: string,
  groups: PromptParamGroup[]
): ParamRefTextRange[] {
  return collectLabeledParamRefRanges(plain, groups, VIDEO_LABELED_FIELD_DEFS, VIDEO_SECTION_STOP_LABELS)
}

/**
 * 分镜图提示词参数提取：@标签 + 「景别：/构图：/拍摄角度：」等结构化字段
 */
export function extractImagePromptParamSelectionsFromPlain(
  plain: string,
  groups: PromptParamGroup[]
): Partial<Record<PromptParamType, { key: string; value: string }>> {
  return extractLabeledParamSelectionsFromPlain(
    plain,
    groups,
    IMAGE_LABELED_FIELD_DEFS,
    IMAGE_SECTION_STOP_LABELS
  )
}

/**
 * 分镜视频提示词参数提取：@标签 + 「镜头运动：/特殊拍摄手法：」结构化字段
 */
export function extractVideoPromptParamSelectionsFromPlain(
  plain: string,
  groups: PromptParamGroup[]
): Partial<Record<PromptParamType, { key: string; value: string }>> {
  return extractLabeledParamSelectionsFromPlain(
    plain,
    groups,
    VIDEO_LABELED_FIELD_DEFS,
    VIDEO_SECTION_STOP_LABELS
  )
}

export function plainHasImageLabeledParamFields(plain: string): boolean {
  return plainHasLabeledParamFields(plain, IMAGE_LABELED_FIELD_DEFS, IMAGE_SECTION_STOP_LABELS)
}

export function plainHasVideoLabeledParamFields(plain: string): boolean {
  return plainHasLabeledParamFields(plain, VIDEO_LABELED_FIELD_DEFS, VIDEO_SECTION_STOP_LABELS)
}

/** 从接口纯文本中提取各参数类型的当前选中项（用于同步右侧下拉） */
export function extractParamSelectionsFromPlain(
  plain: string,
  groups: PromptParamGroup[]
): Partial<Record<PromptParamType, { key: string; value: string }>> {
  const result: Partial<Record<PromptParamType, { key: string; value: string }>> = {}
  if (!plain?.includes('@')) return result

  const stripped = plain.replace(API_IMAGE_PLACEHOLDER_RE, ' ')
  const re = /@([^\s@]+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(stripped)) !== null) {
    const hit = findParamOptionByTag(groups, m[1])
    if (hit && !result[hit.group.paramType]) {
      result[hit.group.paramType] = { key: hit.option.key, value: hit.option.value }
    }
  }
  return result
}

export { PARAM_TYPE_ORDER }
