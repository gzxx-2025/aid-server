/**
 * 分镜视频批量生成：模块级共享助手（原 composables/useStoryboardVideoBatchGenerate.ts
 * 顶部纯函数 + 批量目标 session 快照 + 卡片 loading 恢复拆分；主体见
 * hooks/useStoryboardVideoBatchGenerate.ts，follow 链路见 utils/storyboardVideoBatchFollowCore.ts）。
 */

import {
collectStoryboardVideoGenTaskEntriesInScopes,
resolveStoryboardVideoGenEntriesByTaskId
} from '~/composables/useCreationStoreHydration'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import type { StoryboardRecordRow } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import { userStoryboardSetFinalVideo } from '~/utils/businessApi'
import {
modalGenSessionScopeFromStore
} from '~/utils/modalGenSessionScope'
import {
clearProjectStoryboardRecordCache,
fetchProjectStoryboardRecords,
groupStoryboardRecordsByStoryboardId,
type ProjectEpisodeContext
} from '~/utils/storyboardRecordBatch'
import {
isOriginalStoryboardVideoRecord,
resolveStoryboardRecordDisplayName,
resolveStoryboardVideoSourceLabel
} from '~/utils/storyboardRecordRow'
import { readStoryboardVideoModalGenSession } from '~/utils/storyboardVideoModalGenSession'
import { parseTaskResultPayload } from '~/utils/taskChainOutcome'

export type CreationStoreState = ReturnType<typeof useCreationStore>

export function videoBatchBizErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export type StoryboardVideoPromptFollowResult = {
  ok: boolean
  partial?: boolean
  chainFailed?: boolean
  message?: string
  taskId?: number
  chainChildTaskIds?: number[]
}

export type StoryboardVideoGenerateFollowResult = {
  ok: boolean
  partial?: boolean
  message?: string
}

export function videoBatchSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function parseVideoBatchTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function extractStoryboardIdsFromTaskSnapshot(snapshot: unknown): number[] {
  const data = parseTaskResultPayload(snapshot)
  const rawIds = Array.isArray(data?.storyboardIds) ? data.storyboardIds : []
  return [...new Set(rawIds.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0))]
}

function normStoryboardVideoPromptBatchTaskType(ty: unknown): string {
  return String(ty ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function isStoryboardVideoPromptBatchTask(ty: unknown): boolean {
  return normStoryboardVideoPromptBatchTaskType(ty) === 'storyboard_video_prompt_batch'
}

export function isStoryboardVideoGenerateTaskType(ty: unknown): boolean {
  return normStoryboardVideoPromptBatchTaskType(ty) === 'storyboard_video_generate'
}

export function isOngoingVideoBatchUserTaskStatus(status: unknown): boolean {
  // 与任务中心 isTaskOngoingStatus 对齐：后端可能用 '0' 表示进行中
  const s = String(status ?? '')
    .trim()
    .toUpperCase()
  return (
    s === 'PENDING' ||
    s === 'PROCESSING' ||
    s === 'RUNNING' ||
    s === 'QUEUED' ||
    s === 'WAITING' ||
    s === '0'
  )
}

export function mapRecordToPanelVideo(r: StoryboardRecordRow, title: string) {
  const url = String(r.fileUrl ?? '').trim()
  return {
    id: String(r.id),
    url,
    title: resolveStoryboardRecordDisplayName(r, title) || title,
    source: resolveStoryboardVideoSourceLabel({ _fromServer: true, _serverRow: r }),
    importDate: r.createTime || undefined,
    isStoryboardVideo: r.isSelected === 1 && isOriginalStoryboardVideoRecord(r),
    _fromServer: true,
    _serverRow: r
  }
}

function pickLatestVideoRecord(rows: StoryboardRecordRow[]): StoryboardRecordRow | null {
  const withUrl = rows.filter(
    (r) => isOriginalStoryboardVideoRecord(r) && String(r?.fileUrl ?? '').trim()
  )
  if (!withUrl.length) return null
  return (
    [...withUrl].sort((a, b) => {
      const ta = String(a.createTime ?? '')
      const tb = String(b.createTime ?? '')
      return tb.localeCompare(ta) || Number(b.id) - Number(a.id)
    })[0] ?? null
  )
}

function markRecordSelectedInVideoMap(
  videoByStoryboardId: Map<number, StoryboardRecordRow[]>,
  storyboardId: number,
  recordId: number
) {
  const sid = Number(storyboardId)
  const rid = Number(recordId)
  const rows = videoByStoryboardId.get(sid)
  if (!rows?.length) return
  videoByStoryboardId.set(
    sid,
    rows.map((r) => {
      const id = Number(r.id)
      if (id === rid) return { ...r, isSelected: 1 }
      if (r.isSelected === 1) return { ...r, isSelected: 0 }
      return r
    })
  )
}

export function buildPanelVideosFromRows(
  rows: StoryboardRecordRow[],
  panelTitle: string
): NonNullable<StoryboardVideoPanel['videos']> {
  return rows
    .filter((r) => isOriginalStoryboardVideoRecord(r) && !!String(r?.fileUrl ?? '').trim())
    .map((r) => mapRecordToPanelVideo(r, panelTitle))
}

/** 批量设主视频：list-by-storyboard 只请求一次，再单次 setFinalVideo（items 批量） */
export async function setFinalVideosForStoryboards(
  ctx: ProjectEpisodeContext,
  storyboardIds: number[],
  cachedVideoByStoryboardId?: Map<number, StoryboardRecordRow[]>
): Promise<{
  results: Map<number, boolean>
  videoByStoryboardId: Map<number, StoryboardRecordRow[]>
}> {
  const results = new Map<number, boolean>()
  let videoByStoryboardId = cachedVideoByStoryboardId ?? new Map<number, StoryboardRecordRow[]>()

  if (!storyboardIds.length) return { results, videoByStoryboardId }

  if (!cachedVideoByStoryboardId) {
    try {
      const videoRows = await fetchProjectStoryboardRecords(ctx, 'video')
      videoByStoryboardId = groupStoryboardRecordsByStoryboardId(videoRows)
    } catch {
      for (const sid of storyboardIds) results.set(sid, false)
      return { results, videoByStoryboardId }
    }
  }

  const sidToRecordId = new Map<number, number>()
  const items: Array<{ storyboardId: number; recordId: number }> = []

  for (const storyboardId of storyboardIds) {
    const sid = Number(storyboardId)
    const latest = pickLatestVideoRecord(videoByStoryboardId.get(sid) ?? [])
    const rid = Number(latest?.id)
    if (!Number.isFinite(rid) || rid <= 0) {
      results.set(sid, false)
      continue
    }
    sidToRecordId.set(sid, rid)
    items.push({ storyboardId: sid, recordId: rid })
  }

  if (!items.length) return { results, videoByStoryboardId }

  const SET_FINAL_BATCH_MAX = 50
  const successRecordIds = new Set<number>()

  for (let i = 0; i < items.length; i += SET_FINAL_BATCH_MAX) {
    const chunk = items.slice(i, i + SET_FINAL_BATCH_MAX)
    try {
      const data = await userStoryboardSetFinalVideo({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId,
        items: chunk
      })
      for (const rid of (data?.successIds ?? []).map(Number)) {
        if (Number.isFinite(rid) && rid > 0) successRecordIds.add(rid)
      }
    } catch {
      /* 本批失败，对应分镜记为 false */
    }
  }

  for (const [sid, rid] of sidToRecordId) {
    if (successRecordIds.has(rid)) {
      markRecordSelectedInVideoMap(videoByStoryboardId, sid, rid)
      results.set(sid, true)
    } else {
      results.set(sid, false)
    }
  }

  if (successRecordIds.size > 0) {
    clearProjectStoryboardRecordCache(ctx)
  }

  return { results, videoByStoryboardId }
}

export type ModalVideoRestoreEntry = [
  string,
  { taskId: number; sceneIdx: number; taskKind: 'i2v' | 'multi' | 'edge' | 'grid' }
]

export function toModalVideoRestoreEntries(
  entries: ReturnType<typeof collectStoryboardVideoGenTaskEntriesInScopes>
): ModalVideoRestoreEntry[] {
  return entries.map((e) => [
    String(e.storyboardId),
    {
      taskId: e.taskId,
      sceneIdx: e.sceneIdx,
      taskKind: e.taskKind
    }
  ])
}

export function resolveModalVideoRestoreEntriesForTaskId(
  taskId: number,
  pairs: StoryboardVideoPair[],
  creationStore: CreationStoreState,
  route: RouteLikeLocation
): ModalVideoRestoreEntry[] {
  const fromStore = toModalVideoRestoreEntries(
    resolveStoryboardVideoGenEntriesByTaskId(creationStore, taskId, route)
  )
  if (fromStore.length) return fromStore

  const session = readStoryboardVideoModalGenSession(modalGenSessionScopeFromStore(creationStore))
  if (!session?.storyboardId) return []

  const sessionTaskId = Number(session.taskId)
  const sessionTaskMatches =
    !Number.isFinite(sessionTaskId) || sessionTaskId <= 0 || sessionTaskId === Number(taskId)
  if (!sessionTaskMatches) return []

  const pair = pairs.find((p) => p.storyboardId === session.storyboardId)
  const sceneIdx = pair?.index ?? session.sceneIdx
  if (sceneIdx < 0 || sceneIdx >= pairs.length) return []

  const taskKind =
    session.taskKind === 'multi' || session.taskKind === 'edge' || session.taskKind === 'grid'
      ? session.taskKind
      : 'i2v'

  return [
    [
      String(session.storyboardId),
      {
        taskId: Number(taskId),
        sceneIdx,
        taskKind
      }
    ]
  ]
}

export type StoryboardVideoPair = {
  script: StoryboardPanel
  video: StoryboardVideoPanel | undefined
  index: number
  storyboardId: number
}

export function collectStoryboardVideoPairs(
  scriptPanels: StoryboardPanel[],
  videoPanels: StoryboardVideoPanel[]
): StoryboardVideoPair[] {
  return scriptPanels
    .map((sp, index) => ({
      script: sp,
      video: videoPanels[index],
      index,
      storyboardId: parseServerStoryboardId(sp.id)
    }))
    .filter((x) => x.storyboardId != null) as StoryboardVideoPair[]
}

export function panelHasStoryboardVideo(panel: StoryboardVideoPanel): boolean {
  const list = Array.isArray(panel.videos) ? panel.videos : []
  return list.some((v) => v.isStoryboardVideo && String(v.url ?? '').trim())
}

function storePanelHasVideoFailure(
  creationStore: CreationStoreState,
  sid: number,
  errors: Record<string, string>
): boolean {
  const key = String(sid)
  const err = String(errors[key] ?? '').trim()
  const status = creationStore.storyboardPanelVideoGenStatusByStoryboardId[key]
  return !!err || status === 'failed'
}

/** 将 scope 内持久化的 generating / 失败文案合并进列表 panels（刷新/拉列表后恢复 UI） */
export function applyStoryboardVideoPanelUiFromStore(
  creationStore: CreationStoreState,
  scriptPanels: StoryboardPanel[],
  videoPanels: StoryboardVideoPanel[]
): StoryboardVideoPanel[] {
  return videoPanels.map((panel, index) => {
    const sp = scriptPanels[index]
    const sid = sp ? parseServerStoryboardId(sp.id) : null
    if (sid == null) return panel

    const key = String(sid)
    const batchTargets = creationStore.storyboardVideoBatchTargetStoryboardIds
    const isBatchActive =
      creationStore.isGeneratingStoryboardVideo ||
      creationStore.storyboardVideoBatchActivePromptTaskId != null ||
      creationStore.storyboardVideoBatchActiveVideoTaskId != null
    const isBatchTarget = creationStore.isStoryboardVideoBatchTarget(sid)

    if (batchTargets.length > 0 && isBatchActive && !isBatchTarget) {
      return { ...panel, generating: false, generateError: undefined }
    }

    const storeGenerating =
      creationStore.storyboardPanelVideoGenStatusByStoryboardId[key] === 'generating'
    const storeErr = String(
      creationStore.storyboardPanelVideoGenErrorByStoryboardId[key] ?? ''
    ).trim()
    const storeStatus = creationStore.storyboardPanelVideoGenStatusByStoryboardId[key]
    const failMsg = storeErr || (storeStatus === 'failed' ? '视频生成失败' : '')

    if (failMsg) {
      if (panelHasStoryboardVideo(panel)) {
        return {
          ...panel,
          generating: false,
          generateError: undefined
        }
      }
      return {
        ...panel,
        generating: false,
        generateError: failMsg,
        videos: []
      }
    }

    const shouldGenerate =
      storeGenerating || (creationStore.isGeneratingStoryboardVideo && isBatchTarget)

    return {
      ...panel,
      generating: shouldGenerate,
      generateError: undefined
    }
  })
}

export function panelHasPersistedVideoFailure(
  creationStore: CreationStoreState,
  storyboardId: number
): boolean {
  return storePanelHasVideoFailure(creationStore, storyboardId, {})
}

