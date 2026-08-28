'use client'

/**
 * 场景/角色/道具智能提取执行器（原 composables/useCreateFlowExtractAgents.ts 的执行侧拆分）：
 * 1) 预估（estimate）
 * 2) 提交异步任务（parallel）
 * 3) 跟任务 SSE 直至完成（不再调用 /api/user/task/detail 轮询）
 * 4) 完成后回刷 rps/list，同步第三步名称列表
 * 5) 对尚无形态的入库资产写入 store「待生成形态」列表，由用户在第三步小卡片上逐条触发
 *    /extract/form/generate（不再在提取成功后自动串行调用）
 */

import { message } from 'antd'
import type { ExtractModalScope } from '~/components/steps/ExtractAgentModal'
import { useCreationStore } from '~/stores/creation'
import type { AssetExtractType } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
userAssetRpsList
} from '~/utils/businessApi'
import {
isCreateFlowEmbeddedLibraryPanel,
isSeriesEpisodeListPath,
routePathToCreationStep
} from '~/utils/createFlowRoutes'
import { htmlPlainTextLength } from '~/utils/htmlPlain'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { requestCancelUserTaskById } from '~/utils/userTaskCancelFlow'
import { fetchFlowUserTaskList,filterUserTaskRowsForEpisode } from '~/utils/userTaskListFlowOnce'

export interface ExtractAgentsRuntime {
  extractStopRequested: { value: boolean }
  extractActiveTaskId: { value: number | null }
  extractStreamCloser: { value: null | (() => void) }
  extractStreamScopeKey: { value: string | null }
  extractFollowSession: { value: number }
  extractResumeGeneration: { value: number }
  getRoute(): RouteLikeLocation
}

export const MIN_EXTRACTING_VISIBLE_MS = 5000

export function store() {
  return useCreationStore.getState()
}

export function isStoryScriptContentFilledForExtract(content: unknown): boolean {
  return typeof content === 'string' && htmlPlainTextLength(content) > 0
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function parseTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function scopeToExtractTypes(scope: ExtractModalScope): AssetExtractType[] {
  if (scope === 'scene') return ['scene']
  if (scope === 'character') return ['character']
  if (scope === 'prop') return ['prop']
  /** 与常见流水线一致：先场景再角色再道具，便于 Tab 与 SSE 对齐 */
  return ['scene', 'character', 'prop']
}

/** 仅通过任务 SSE 等待结束，不调用 /api/user/task/detail */
function shouldUseStep3CurrentTabRefresh(route: RouteLikeLocation): boolean {
  return (
    routePathToCreationStep(route.path) === 'scene-character' &&
    !isCreateFlowEmbeddedLibraryPanel(route.query)
  )
}

export function extractResultRefreshTypes(
  scope: ExtractModalScope,
  types: AssetExtractType[],
  route: RouteLikeLocation
): AssetExtractType[] {
  if (shouldUseStep3CurrentTabRefresh(route)) return []
  if (scope === 'scene' || scope === 'character' || scope === 'prop') return [scope]
  return types.length ? [types[0]!] : []
}

/** 路由 + 作品/剧集上下文：await 期间若用户切换作品，用于丢弃过期的异步逻辑 */
export function getExtractAutoOpenContextKey(route: RouteLikeLocation): string {
  const q = route.query
  const s = store()
  return [
    route.path,
    String(s.currentProjectId ?? ''),
    String(s.currentEpisodeId ?? ''),
    String(q.projectId ?? ''),
    String(q.id ?? ''),
    String(q.workId ?? ''),
    String(q.episodeId ?? '')
  ].join('|')
}

export function getExtractFlowContextKey(runtime: ExtractAgentsRuntime): string {
  return getExtractAutoOpenContextKey(runtime.getRoute())
}

export function isBenignExtractStreamAbortError(e: unknown): boolean {
  const err = e as { name?: string; message?: string }
  if (err?.name === 'AbortError') return true
  const msg = String((e as Error)?.message ?? e ?? '').toLowerCase()
  if (!msg) return false
  return (
    msg.includes('abort') ||
    msg.includes('cancel') ||
    msg.includes('signal is aborted') ||
    msg.includes('ended unexpectedly') ||
    msg.includes('networkerror') ||
    msg.includes('failed to fetch') ||
    msg.includes('load failed') ||
    msg.includes('body stream')
  )
}

export function releaseExtractStreamFollow(runtime: ExtractAgentsRuntime) {
  if (runtime.extractStreamScopeKey.value) {
    store().setAssetExtractFollowTask(runtime.extractStreamScopeKey.value, null)
    runtime.extractStreamScopeKey.value = null
  }
  store().setAssetExtractShellLiveTaskId(null)
}

export function stopExtractStreamForContextChange(runtime: ExtractAgentsRuntime) {
  runtime.extractFollowSession.value++
  releaseExtractStreamFollow(runtime)
  try {
    runtime.extractStreamCloser.value?.()
  } catch {
    /* ignore */
  }
  runtime.extractStreamCloser.value = null
  runtime.extractActiveTaskId.value = null
}

/** 切作品/从内嵌库回到流程：提取 loading 仍在但 SSE 已断时，通知第三步重连 */
export async function tryResumeAssetExtractTrack(runtime: ExtractAgentsRuntime) {
  if (typeof window === 'undefined') return
  const route = runtime.getRoute()
  if (isCreateFlowEmbeddedLibraryPanel(route.query)) return
  const onSceneCharacter = routePathToCreationStep(route.path) === 'scene-character'
  const onSeriesEpisodeList = isSeriesEpisodeListPath(route.path)
  if (!onSceneCharacter && !onSeriesEpisodeList) return
  if (runtime.extractStreamCloser.value) return

  const gen = ++runtime.extractResumeGeneration.value
  const scopeKey = store().step3GenVisualScopeKey()
  if (store().getAssetExtractShellLiveTaskId()) {
    store().setAssetExtractShellLiveTaskId(null)
    store().setAssetExtractFollowTask(scopeKey, null)
  }
  if (!store().isExtractingAssets) return

  const taskId = await resolveLatestOngoingAssetExtractTaskId(runtime)
  if (gen !== runtime.extractResumeGeneration.value) return
  if (!taskId) {
    store().finishAssetExtractUiForCurrentScope()
    return
  }
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
  if (gen !== runtime.extractResumeGeneration.value) return
  window.dispatchEvent(
    new CustomEvent('create-flow-track-task', {
      detail: { taskId, taskType: 'asset_extract' }
    })
  )
}

/**
 * 提取完成后只按目标 Tab 拉一次 rps/list：
 * - 更新该 Tab 的名称列表
 * - 同时收集该 Tab 尚无形态的资产，供第三步小卡片展示
 */
export async function syncExtractedAssetsFromServer(
  ctx: { projectId: number; episodeId: number },
  extractTypes: AssetExtractType[]
) {
  const out: Array<{ assetId: number; assetType: 'scene' | 'character' | 'prop'; title: string }> =
    []
  /** 三类资产列表相互独立，并行拉取缩短提取完成后的回显耗时 */
  const listByType = await Promise.all(
    extractTypes.map(async (type) => {
      const { rows } = await userAssetRpsList({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId,
        assetType: type
      })
      return { type, rows }
    })
  )
  for (const { type, rows } of listByType) {
    if (type === 'scene') {
      store().updateSceneCharacterData({
        scenes: rows.map((r, i) => (r.assetName || '').trim() || `场景${i + 1}`)
      })
    } else if (type === 'character') {
      store().updateSceneCharacterData({
        characters: rows.map((r, i) => (r.assetName || '').trim() || `角色${i + 1}`)
      })
    } else {
      store().updateSceneCharacterData({
        props: rows.map((r, i) => (r.assetName || '').trim() || `道具${i + 1}`)
      })
    }

    for (const row of rows ?? []) {
      const hasAnyForm =
        Array.isArray(row.forms) &&
        row.forms.some((f) => Number.isFinite(Number(f?.id)) && Number(f?.id) > 0)
      if (hasAnyForm) continue
      const id = Number(row.id)
      if (!Number.isFinite(id) || id <= 0) continue
      const title = String(row.assetName || '').trim() || '未命名'
      out.push({ assetId: id, assetType: type, title })
    }
  }
  return out
}

/**
 * 本地尚未写入 taskId 时（仍在 estimate/提交 parallel 前）用户会点「停止」：
 * 用任务列表兜底查找当前项目下进行中的 asset_extract，再调 /api/user/asset/extract/cancel
 */
export async function resolveLatestOngoingAssetExtractTaskId(
  runtime: ExtractAgentsRuntime
): Promise<number | null> {
  const ctx = await resolveStoryScriptSaveContext(store(), runtime.getRoute())
  if (!ctx) return null
  try {
    /** 剧集隔离：兜底取消只找本集进行中的提取任务，防止误取消其它集任务 */
    const all = filterUserTaskRowsForEpisode(
      await fetchFlowUserTaskList(ctx.projectId, { intent: 'read' }),
      ctx.episodeId
    )
    const st = (s: unknown) => String(s ?? '').trim().toUpperCase()
    const rows = all.filter((t) => {
      if (t?.taskType !== 'asset_extract') return false
      const u = st(t.status)
      return (
        u === 'PENDING' || u === 'PROCESSING' || u === 'RUNNING' || u === 'QUEUED' || u === 'WAITING'
      )
    })
    rows.sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
    return parseTaskId(rows[0]?.id)
  } catch {
    return null
  }
}

export async function stopExtractAssets(runtime: ExtractAgentsRuntime) {
  runtime.extractStopRequested.value = true
  const close = runtime.extractStreamCloser.value

  let cancelTaskId = runtime.extractActiveTaskId.value
  if (!cancelTaskId) {
    cancelTaskId = await resolveLatestOngoingAssetExtractTaskId(runtime)
    if (!cancelTaskId) {
      await sleep(400)
      cancelTaskId = await resolveLatestOngoingAssetExtractTaskId(runtime)
    }
  }

  if (cancelTaskId) {
    try {
      await requestCancelUserTaskById(cancelTaskId)
      message.success('已请求停止提取任务')
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '停止请求失败，已断开本页进度')
    }
  } else {
    message.info('任务尚未提交或已结束，已关闭本页提取状态')
  }

  close?.()
  runtime.extractStreamCloser.value = null
  runtime.extractActiveTaskId.value = null
  store().finishAssetExtractUiForCurrentScope()
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }
}

