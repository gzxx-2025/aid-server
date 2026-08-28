import type { DubbingPanel,StoryboardVideoPanel } from '~/types'
import type { StoryboardRecordRow } from '~/types/business-api'
import { resolveStoryboardRecordDisplayName } from '~/utils/storyboardRecordRow'
import { getPanelStoryboardVideoUrl } from '~/utils/storyboardVideoCover'
import type { ServerVideoThumb } from './types'

export function formatDubTime(d = new Date()) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function isPanelDubbingConfigured(p: DubbingPanel | undefined): boolean {
  if (!p) return false
  if (p.dubbingLipSyncVideoUrl && String(p.dubbingLipSyncVideoUrl).trim()) return true
  if (p.storyboardDubbingConfirmed === true) return true
  if (p.status === 'done') return true
  if (String(p.status) === 'done') return true
  if (p.dialogue && String(p.dialogue).trim()) return true
  // 确认提交后必定会写入音色展示名（含「无音色」），作兜底以免 status 未持久化时仍误判为未设置
  if (p.dubbingVoiceName != null && String(p.dubbingVoiceName).trim() !== '') return true
  return false
}

export function mapRecordRowToVideoThumb(r: StoryboardRecordRow): ServerVideoThumb {
  const url = (r.fileUrl || '').trim()
  return {
    id: String(r.id ?? ''),
    url,
    title: resolveStoryboardRecordDisplayName(r) || undefined,
    isSelected: r.isSelected === 1,
    _serverRow: r
  }
}

export function panelHasStoryboardVideoUrl(vPanels: StoryboardVideoPanel[], idx: number): boolean {
  const panel = vPanels[idx]
  return !!getPanelStoryboardVideoUrl(panel)
}

/** Tab 文案：已生成分镜视频则不显示「分镜生成中」；未生成则显示「未设置分镜」 */
export function formatDubbingSceneTabLabel(
  title: string,
  hasStoryboardVideo: boolean,
  index: number
): string {
  const raw = (title || '').trim()
  if (hasStoryboardVideo) {
    return (
      raw
        .replace(/[:：]\s*分镜生成中\s*$/u, '')
        .replace(/\s*分镜生成中\s*$/u, '')
        .trim() ||
      raw ||
      `分镜视频${index + 1}`
    )
  }
  if (/分镜生成中/.test(raw)) {
    return raw.replace(/分镜生成中/g, '未设置分镜')
  }
  const base =
    raw
      .replace(/[:：]\s*分镜生成中\s*$/u, '')
      .replace(/[:：]\s*$/, '')
      .trim() || raw
  if (!base) return `分镜视频${index + 1}：未设置分镜`
  return base.includes('未设置分镜') ? base : `${base}：未设置分镜`
}

/** Tab 主标题：去掉「未设置分镜」后缀，由下方状态行单独展示 */
export function formatDubbingSceneTabPrimaryLabel(
  title: string,
  hasStoryboardVideo: boolean,
  index: number
): string {
  const label = formatDubbingSceneTabLabel(title, hasStoryboardVideo, index)
  if (hasStoryboardVideo) return label
  return label.replace(/[:：]\s*未设置分镜\s*$/u, '').trim() || label
}

export function resolveEmotionApiCode(
  emotionNameToCode: Map<string, string>,
  label: string
): string {
  const raw = (label || '').trim()
  if (!raw) return 'neutral'
  const hit = emotionNameToCode.get(raw)
  if (hit) return hit
  if (/^[a-z][a-z0-9_]*$/i.test(raw)) return raw.toLowerCase()
  return 'neutral'
}

/** userVoiceLibraryTags 失败时的情绪编码兜底表 */
export function fallbackEmotionNameToCode(): Map<string, string> {
  return new Map([
    ['中性', 'neutral'],
    ['高兴', 'happy'],
    ['开心', 'happy'],
    ['悲伤', 'sad'],
    ['生气', 'angry'],
    ['愤怒', 'angry'],
    ['激动', 'excited'],
    ['neutral', 'neutral'],
    ['happy', 'happy'],
    ['sad', 'sad'],
    ['angry', 'angry']
  ])
}

export function loadAudioDurationSec(url: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const a = new Audio()
    a.preload = 'metadata'
    a.onloadedmetadata = () => {
      const d = a.duration
      a.removeAttribute('src')
      a.load()
      if (Number.isFinite(d) && d > 0) resolve(d)
      else reject(new Error('无法读取音频时长'))
    }
    a.onerror = () => reject(new Error('无法加载试听音频'))
    a.src = url
  })
}

export function buildTtsPreviewSignature(
  previewText: string,
  voiceLibraryId: number,
  voiceModelId: number,
  timbreCode: string
): string {
  return `${previewText}|lib:${voiceLibraryId}|model:${voiceModelId}|t:${timbreCode}`
}
