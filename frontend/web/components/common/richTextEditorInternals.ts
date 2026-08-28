import type QuillDelta from 'quill-delta'

/** RichTextEditor 纯函数辅助：受保护 Embed 比对与粘贴清洗（与组件状态无关，拆出以控制主文件体量） */

type DeltaCtor = typeof QuillDelta

/** 角色设定受保护 Embed：基本信息字段名 + 锁定小节标题 */
export function protectedEmbedMultiset(delta: {
  ops: ReadonlyArray<{ insert?: unknown }>
}): Map<string, number> {
  const m = new Map<string, number>()
  for (const op of delta.ops) {
    const ins = op.insert
    if (ins == null || typeof ins !== 'object' || Array.isArray(ins)) continue
    if ('characterSettingKey' in ins) {
      const k = `k:${String((ins as { characterSettingKey: string }).characterSettingKey)}`
      m.set(k, (m.get(k) ?? 0) + 1)
    }
    if ('characterSettingSection' in ins) {
      const k = `s:${String((ins as { characterSettingSection: string }).characterSettingSection)}`
      m.set(k, (m.get(k) ?? 0) + 1)
    }
  }
  return m
}

export function protectedMultisetLost(
  before: Map<string, number>,
  after: Map<string, number>
): boolean {
  for (const [key, c] of before) {
    if ((after.get(key) ?? 0) < c) return true
  }
  return false
}

/**
 * 仅当本次变更里出现「删除长度」时才比对受保护 Embed。
 * 否则在 Quill 2 下，纯插入也可能出现前后 getContents 与 oldDelta 序列化不一致，导致误判丢 Embed，
 * 从而每次输入都回滚并反复弹窗。
 */
export function deltaHasPositiveDelete(
  delta: { ops?: ReadonlyArray<{ delete?: number }> } | null | undefined
): boolean {
  if (!delta?.ops) return false
  return delta.ops.some((op) => typeof op.delete === 'number' && op.delete > 0)
}

/** 从 HTML 提取受保护块指纹（避免 v-model 与 getSemanticHTML 仅差空白/属性顺序时反复 applyHtml 破坏 Embed） */
export function protectedStructureFingerprint(html: string): string {
  if (typeof window === 'undefined' || !html?.trim()) return ''
  try {
    const doc = new DOMParser().parseFromString(`<div id="scp-fp">${html}</div>`, 'text/html')
    const root = doc.getElementById('scp-fp')
    if (!root) return ''
    const keys = [...root.querySelectorAll('.scp-char-setting-key')].map((e) =>
      (e.textContent ?? '').trim()
    )
    const secs = [...root.querySelectorAll('.scp-char-setting-section')].map((e) =>
      (e.textContent ?? '').trim()
    )
    const pack = (arr: string[], p: string) =>
      arr
        .filter(Boolean)
        .map((t) => `${p}:${t}`)
        .sort()
        .join('|')
    return `${pack(keys, 'k')}##${pack(secs, 's')}`
  } catch {
    return html
  }
}

// 粘贴 / dangerouslyPasteHTML：剥离外部站点的背景色、字色等内联样式，统一为深色底上的白字
const PASTE_TEXT_COLOR = '#ffffff'

export function stripExternalPasteAttrs(attrs?: Record<string, unknown>) {
  const next: Record<string, unknown> = { ...(attrs ?? {}) }
  delete next.background
  delete next['background-color']
  delete next.color
  next.color = PASTE_TEXT_COLOR
  return next
}

export function normalizeClipboardDelta(
  Delta: DeltaCtor,
  delta: InstanceType<DeltaCtor>
): InstanceType<DeltaCtor> {
  const d = new Delta()
  for (const op of delta.ops) {
    const ins = op.insert
    if (ins !== undefined && ins !== null) {
      if (typeof ins === 'string') {
        d.insert(ins, stripExternalPasteAttrs(op.attributes))
      } else {
        d.insert(ins, op.attributes ? stripExternalPasteAttrs(op.attributes) : op.attributes)
      }
    } else if (op.retain !== undefined) {
      d.retain(
        op.retain as number,
        op.attributes ? stripExternalPasteAttrs(op.attributes) : op.attributes
      )
    } else if (op.delete !== undefined) {
      d.delete(op.delete as number)
    }
  }
  return d
}

/** 小说站等来源的 HTML 常带 selection / 主题背景，先 DOM 层剥离再交给 Quill */
export function sanitizePastedHtml(html: string): string {
  if (!html?.trim()) return html
  try {
    const doc = new DOMParser().parseFromString(html, 'text/html')
    doc.body.querySelectorAll<HTMLElement>('*').forEach((el) => {
      el.style.removeProperty('background')
      el.style.removeProperty('background-color')
      el.style.removeProperty('-webkit-text-fill-color')
      el.style.removeProperty('color')
      el.removeAttribute('bgcolor')
    })
    return doc.body.innerHTML
  } catch {
    return html
  }
}
