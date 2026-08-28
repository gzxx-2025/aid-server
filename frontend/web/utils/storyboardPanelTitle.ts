/** 分镜脚本 / 分镜视频 / 音画同步标题：统一按列表下标（0-based index）生成序号 */

const SCRIPT_PREFIX = '分镜脚本'
const VIDEO_PREFIX = '分镜视频'
const DUBBING_PREFIX = '分镜配音'

/** 列表展示序号，与接口「分镜脚本01」格式一致 */
export function storyboardIndexLabel(index: number): string {
  return String(Math.max(0, index) + 1).padStart(2, '0')
}

/** 从任意分镜标题中提取冒号后的名称部分（去掉前缀序号） */
export function extractStoryboardTitleSuffix(title?: string | null): string {
  if (!title || !title.trim()) return ''
  const t = title.trim()
  const withSuffix = t.match(/^(?:分镜脚本|分镜视频|分镜配音)\d*[：:]\s*(.+)$/)
  if (withSuffix?.[1]) return withSuffix[1].trim()
  const pureNum = t.match(/^(?:分镜脚本|分镜视频|分镜配音)\d+$/)
  if (pureNum) return ''
  return t
}

export function formatStoryboardScriptTitle(index: number, suffix?: string | null): string {
  const num = storyboardIndexLabel(index)
  const s = (suffix ?? '').trim()
  return s ? `${SCRIPT_PREFIX}${num}：${s}` : `${SCRIPT_PREFIX}${num}`
}

export function formatStoryboardVideoTitle(index: number, suffix?: string | null): string {
  const num = storyboardIndexLabel(index)
  const s = (suffix ?? '').trim()
  return s ? `${VIDEO_PREFIX}${num}：${s}` : `${VIDEO_PREFIX}${num}`
}

export function formatStoryboardDubbingTitle(index: number, suffix?: string | null): string {
  const num = storyboardIndexLabel(index)
  const n = index + 1
  const s = (suffix ?? '').trim() || `分镜${n}-${n}`
  return `${DUBBING_PREFIX}${num}：${s}`
}

/** 列表展示用标题（优先 panel.title，否则按类型与下标生成） */
export function resolveStoryboardListDisplayTitle(
  panelTitle: string | undefined,
  index: number,
  kind: 'script' | 'video' | 'dubbing'
): string {
  const trimmed = (panelTitle ?? '').trim()
  if (trimmed) {
    const suffix = extractStoryboardTitleSuffix(trimmed)
    if (kind === 'script') return formatStoryboardScriptTitle(index, suffix)
    if (kind === 'video') return formatStoryboardVideoTitle(index, suffix)
    return formatStoryboardDubbingTitle(index, suffix)
  }
  if (kind === 'script') return formatStoryboardScriptTitle(index)
  if (kind === 'video') return formatStoryboardVideoTitle(index)
  return formatStoryboardDubbingTitle(index)
}
