import type { DubbingPanel } from '~/types'
import type { StoryboardRecordRow } from '~/types/business-api'
import { isComposeStoryboardVideoRecord,resolveStoryboardRecordDisplayName } from '~/utils/storyboardRecordRow'

export type DubbingGenHistoryItem = {
  id: string
  url: string
  title: string
  dialogue: string
  voiceName: string
  emotion: string
}

export function mapComposeRecordToDubbingGenItem(
  row: StoryboardRecordRow,
  panel?: DubbingPanel | null
): DubbingGenHistoryItem {
  const recordId = Number(row.id)
  const url = String(row.fileUrl ?? '').trim()
  return {
    id: Number.isFinite(recordId) && recordId > 0 ? `compose-${recordId}` : `compose-${url}`,
    url,
    title: resolveStoryboardRecordDisplayName(row) || '配音合成',
    dialogue: String(panel?.dialogue ?? '').trim(),
    voiceName: String(panel?.dubbingVoiceName ?? '').trim() || '无音色',
    emotion: String(panel?.dubbingEmotion ?? '').trim() || '中性'
  }
}

/** 将 list-by-storyboard 返回的 compose 记录合并进配音弹窗右侧生成历史 */
export function mergeComposeRecordsIntoDubbingGenHistory(
  existing: DubbingGenHistoryItem[],
  rows: StoryboardRecordRow[],
  panel?: DubbingPanel | null
): DubbingGenHistoryItem[] {
  const composeRows = rows.filter(
    (r) => isComposeStoryboardVideoRecord(r) && String(r.fileUrl ?? '').trim()
  )
  if (!composeRows.length) return existing

  const merged = [...existing]
  const urlToIndex = new Map<string, number>()
  merged.forEach((item, idx) => {
    if (item.url) urlToIndex.set(item.url, idx)
  })

  for (const row of composeRows) {
    const item = mapComposeRecordToDubbingGenItem(row, panel)
    const idx = urlToIndex.get(item.url)
    if (idx != null) {
      const prev = merged[idx]!
      merged[idx] = {
        ...prev,
        id: String(prev.id).startsWith('compose-') ? prev.id : item.id,
        title: item.title || prev.title,
        dialogue: prev.dialogue || item.dialogue,
        voiceName: prev.voiceName && prev.voiceName !== '无音色' ? prev.voiceName : item.voiceName,
        emotion: prev.emotion || item.emotion
      }
      continue
    }
    merged.push(item)
    urlToIndex.set(item.url, merged.length - 1)
  }

  const createTimeByUrl = new Map<string, string>()
  for (const r of composeRows) {
    const url = String(r.fileUrl ?? '').trim()
    if (url) createTimeByUrl.set(url, String(r.createTime ?? ''))
  }

  return merged.sort((a, b) => {
    const ta = createTimeByUrl.get(a.url) ?? ''
    const tb = createTimeByUrl.get(b.url) ?? ''
    if (ta && tb) {
      const cmp = ta.localeCompare(tb)
      if (cmp !== 0) return cmp
    }
    if (ta && !tb) return -1
    if (!ta && tb) return 1
    return 0
  })
}

export function isSameDubbingGenHistory(
  a: DubbingGenHistoryItem[],
  b: DubbingGenHistoryItem[]
): boolean {
  if (a.length !== b.length) return false
  return a.every((item, i) => item.id === b[i]?.id && item.url === b[i]?.url)
}

