/** 分镜图描述框内 @构图 / @景别 等参数引用（无缩略图，可点击切换） */

import { PROMPT_TYPE } from '~/composables/usePromptDictionary'

export type PromptParamType =
  | typeof PROMPT_TYPE.composition
  | typeof PROMPT_TYPE.shot_size
  | typeof PROMPT_TYPE.camera_angle
  | typeof PROMPT_TYPE.focal_length
  | typeof PROMPT_TYPE.color_tone
  | typeof PROMPT_TYPE.lighting
  | typeof PROMPT_TYPE.exposure_blur
  | typeof PROMPT_TYPE.camera_movement
  | typeof PROMPT_TYPE.shooting_technique

export interface PromptParamRefValue {
  paramType: PromptParamType
  key: string
  /** 展示用，如 @三分法构图 */
  label: string
}

export interface PromptParamOption {
  key: string
  value: string
}

export interface PromptParamGroup {
  paramType: PromptParamType
  options: PromptParamOption[]
}

export interface StoryboardPromptParamOptionsInput {
  composition: PromptParamOption[]
  shotSize: PromptParamOption[]
  cameraAngle: PromptParamOption[]
  focalLength: PromptParamOption[]
  colorTone: PromptParamOption[]
  lighting: PromptParamOption[]
  technique: PromptParamOption[]
}

export const PARAM_TYPE_ORDER: PromptParamType[] = [
  PROMPT_TYPE.composition,
  PROMPT_TYPE.shot_size,
  PROMPT_TYPE.camera_angle,
  PROMPT_TYPE.focal_length,
  PROMPT_TYPE.color_tone,
  PROMPT_TYPE.lighting,
  PROMPT_TYPE.exposure_blur
]

export function stripAt(s: string): string {
  return s.startsWith('@') ? s.slice(1) : s
}

export function formatParamLabel(value: string): string {
  const v = String(value || '').trim()
  if (!v) return ''
  return v.startsWith('@') ? v : `@${v}`
}

export function selectionToParamRef(
  paramType: PromptParamType,
  selection: { key: string; value: string } | null | undefined
): PromptParamRefValue | null {
  if (!selection || selection.key === 'none') return null
  return {
    paramType,
    key: selection.key,
    label: formatParamLabel(selection.value)
  }
}

export function paramRefToSelection(
  ref: PromptParamRefValue,
  options: PromptParamOption[]
): { key: string; value: string } | null {
  const hit = options.find((o) => o.key === ref.key)
  if (hit) return { key: hit.key, value: hit.value }
  const label = stripAt(ref.label)
  const byLabel = options.find((o) => o.value === label || stripAt(o.value) === label)
  return byLabel ? { key: byLabel.key, value: byLabel.value } : null
}

export function buildStoryboardPromptParamGroups(
  input: StoryboardPromptParamOptionsInput
): PromptParamGroup[] {
  return [
    { paramType: PROMPT_TYPE.composition, options: input.composition },
    { paramType: PROMPT_TYPE.shot_size, options: input.shotSize },
    { paramType: PROMPT_TYPE.camera_angle, options: input.cameraAngle },
    { paramType: PROMPT_TYPE.focal_length, options: input.focalLength },
    { paramType: PROMPT_TYPE.color_tone, options: input.colorTone },
    { paramType: PROMPT_TYPE.lighting, options: input.lighting },
    { paramType: PROMPT_TYPE.exposure_blur, options: input.technique }
  ]
}

/** 图生视频：镜头运动 + 特殊拍摄手法 */
export function buildStoryboardVideoPromptParamGroups(input: {
  cameraMovement: PromptParamOption[]
  shootingTechnique: PromptParamOption[]
}): PromptParamGroup[] {
  return [
    { paramType: PROMPT_TYPE.camera_movement, options: input.cameraMovement },
    { paramType: PROMPT_TYPE.shooting_technique, options: input.shootingTechnique }
  ]
}

/** 多参生视频：镜头运动 + 特殊拍摄手法（灵感空间右侧参数） */
export function buildMultiParamVideoPromptParamGroups(input: {
  cameraMovement: PromptParamOption[]
  shootingTechnique: PromptParamOption[]
}): PromptParamGroup[] {
  return buildStoryboardVideoPromptParamGroups(input)
}

/** 将参数 @ 标签写入多参提示词的 # 运镜 段落 */
export function insertParamLabelIntoMarkdownSection(
  plain: string,
  sectionTitle: string,
  label: string,
  linePrefix = '- 运镜：'
): string {
  const text = String(plain || '')
  const tag = formatParamLabel(label)
  const strippedTag = stripAt(tag)
  if (!strippedTag) return text

  const header = `# ${sectionTitle}`
  const headerIdx = text.indexOf(header)
  if (headerIdx < 0) return text

  const afterHeader = headerIdx + header.length
  const nextSection = text.slice(afterHeader).search(/\n#\s/)
  const sectionEnd = nextSection >= 0 ? afterHeader + nextSection : text.length
  const sectionBody = text.slice(afterHeader, sectionEnd)
  if (sectionBody.includes(strippedTag) || sectionBody.includes(tag)) return text

  const insertion = `\n${linePrefix}${tag}`
  return `${text.slice(0, sectionEnd).trimEnd()}${insertion}${text.slice(sectionEnd)}`
}

export function findParamGroup(
  groups: PromptParamGroup[],
  paramType: PromptParamType
): PromptParamGroup | undefined {
  return groups.find((g) => g.paramType === paramType)
}

/** 按展示名最长优先匹配，避免短标签误匹配 */
export function findParamOptionByTag(
  groups: PromptParamGroup[],
  tag: string
): { group: PromptParamGroup; option: PromptParamOption } | undefined {
  const raw = stripAt(tag)
  if (!raw) return undefined

  const candidates: Array<{ group: PromptParamGroup; option: PromptParamOption; label: string }> = []
  for (const group of groups) {
    for (const option of group.options) {
      if (option.key === 'none') continue
      const label = stripAt(formatParamLabel(option.value))
      if (label) candidates.push({ group, option, label })
    }
  }
  candidates.sort((a, b) => b.label.length - a.label.length)

  for (const c of candidates) {
    if (c.label === raw) return { group: c.group, option: c.option }
  }
  return undefined
}

export function promptParamRefSpanHtml(v: PromptParamRefValue): string {
  const label = v.label || formatParamLabel(v.key)
  const attrs = [
    `class="scp-prompt-param-ref scp-prompt-param-ref--${v.paramType}"`,
    'contenteditable="false"',
    `data-param-type="${escapeAttr(v.paramType)}"`,
    `data-key="${escapeAttr(v.key)}"`,
    `data-label="${escapeAttr(label)}"`
  ].join(' ')
  return `<span ${attrs}><span class="scp-prompt-param-ref__label">${escapeHtml(label)}</span></span>`
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeAttr(s: string): string {
  return escapeHtml(s).replace(/'/g, '&#39;')
}

export function readPromptParamRefFromNode(node: HTMLElement): PromptParamRefValue {
  return {
    paramType: (node.dataset.paramType || PROMPT_TYPE.composition) as PromptParamType,
    key: node.dataset.key || '',
    label: node.dataset.label || node.textContent?.trim() || ''
  }
}

export function extractParamRefsFromHtml(html: string): Map<PromptParamType, PromptParamRefValue> {
  const map = new Map<PromptParamType, PromptParamRefValue>()
  if (!html?.trim() || !(typeof window !== 'undefined')) return map
  try {
    const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
    doc.querySelectorAll('.scp-prompt-param-ref').forEach((el) => {
      const v = readPromptParamRefFromNode(el as HTMLElement)
      if (v.paramType) map.set(v.paramType, v)
    })
  } catch {
    /* ignore */
  }
  return map
}

export function paramRefsEqual(a: PromptParamRefValue | null, b: PromptParamRefValue | null): boolean {
  if (!a && !b) return true
  if (!a || !b) return false
  return a.paramType === b.paramType && a.key === b.key && a.label === b.label
}

