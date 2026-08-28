import { isTaskOngoingStatus } from '~/composables/useTaskOngoing'
import type { SceneModalSseTaskSnapshot } from '~/stores/creation'
import type { UserTaskRow } from '~/types/business-api'
import {
isFormCardImageTaskType,
parseImageIdsFromTaskInputSnapshot
} from '~/utils/formImageAutoUse'
import { fetchFlowUserTaskListOnce } from '~/utils/userTaskListFlowOnce'
import {
mapTaskTypeToModalKind,
normModalTaskType,
parseFormIdFromTaskSnapshot,
parseFormIdsFromTaskSnapshot,
parseImageIdFromTaskSnapshot,
taskSnapshotMatchesModalFormIds
} from './sceneModalTaskParsers'
import type { EditSceneImageModalCtx,SceneModalTaskDetailLoader } from './types'

export async function fetchRecentProjectTaskRows(projectId: number): Promise<UserTaskRow[]> {
  return fetchFlowUserTaskListOnce(Number(projectId)).catch(() => [])
}

export async function resolveOngoingSettingCardModalTask(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number,
  editorScopeKey: string,
  preloadedRows: UserTaskRow[],
  loadTaskDetail: SceneModalTaskDetailLoader,
  isCurrent: () => boolean
): Promise<SceneModalSseTaskSnapshot | null> {
  const pid = ctx.store().currentProjectId
  if (pid == null || !Number.isFinite(Number(pid)) || Number(pid) <= 0) return null

  const modalSourceImageIds = ctx.localSceneImages
    .get()
    .map((img) =>
      Number((img as { rpsImageId?: number; id?: number })?.rpsImageId ?? (img as { id?: number })?.id)
    )
    .filter((id) => Number.isFinite(id) && id > 0)

  for (const row of preloadedRows) {
    if (!isCurrent()) return null
    if (!row || !isTaskOngoingStatus(row.status)) continue
    if (!isFormCardImageTaskType(row.taskType)) continue

    const tid = Number(row.id)
    if (!Number.isFinite(tid) || tid <= 0) continue

    let snapshotRec: { inputSnapshot?: string | null; taskType?: string | null } = row
    if (!row.inputSnapshot) {
      try {
        snapshotRec = await loadTaskDetail(tid)
        if (!isCurrent()) return null
      } catch {
        /* keep list row */
      }
    }

    const batchImageIds = parseImageIdsFromTaskInputSnapshot(snapshotRec.inputSnapshot)
    const sourceImageId =
      batchImageIds.find((id) => modalSourceImageIds.includes(id)) ??
      parseImageIdFromTaskSnapshot(snapshotRec.inputSnapshot)
    if (sourceImageId == null) continue
    if (batchImageIds.length && !batchImageIds.includes(sourceImageId)) continue

    const imgIdx = ctx.resolveImageIdxByRpsImageId(sourceImageId)
    const rowAtIdx = ctx.localSceneImages.get()[imgIdx] as { rpsImageId?: number } | undefined
    if (Number(rowAtIdx?.rpsImageId) !== sourceImageId) continue

    return {
      taskId: tid,
      taskKind: 'setting-card',
      sceneIdx,
      imageIdx: imgIdx,
      editorScopeKey,
      imageId: sourceImageId
    }
  }
  return null
}

export async function resolveFallbackSceneModalSseTask(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number,
  editorScopeKey: string,
  isCurrent: () => boolean,
  loadTaskDetail: SceneModalTaskDetailLoader
): Promise<SceneModalSseTaskSnapshot | null> {
  const pid = ctx.store().currentProjectId
  if (pid == null || !Number.isFinite(Number(pid)) || Number(pid) <= 0) return null

  const rows = await fetchRecentProjectTaskRows(Number(pid))
  if (!isCurrent()) return null
  const settingCardTask = await resolveOngoingSettingCardModalTask(
    ctx,
    sceneIdx,
    editorScopeKey,
    rows,
    loadTaskDetail,
    isCurrent
  )
  if (settingCardTask) return settingCardTask
  if (!isCurrent()) return null

  const sessionTask = ctx.rebuildPersistedFromSession(sceneIdx)
  if (sessionTask) return sessionTask

  const formIds = ctx.collectModalFormIdsForSceneIndex(sceneIdx)
  if (!formIds.length) return null
  const formIdSet = new Set(formIds)

  for (const row of rows) {
    if (!isCurrent()) return null
    if (!row || !isTaskOngoingStatus(row.status)) continue
    const ty = normModalTaskType(row.taskType)
    if (
      ty !== 'form_edit_chat' &&
      ty !== 'form_multi_view' &&
      ty !== 'form_image' &&
      ty !== 'form_image_batch' &&
      ty !== 'image_upscale' &&
      ty !== 'form_card_image' &&
      ty !== 'form_card_image_batch'
    ) {
      continue
    }
    const tid = Number(row.id)
    if (!Number.isFinite(tid) || tid <= 0) continue

    let snapshotRec: { inputSnapshot?: string | null; taskType?: string | null } = row
    if (!row.inputSnapshot) {
      try {
        snapshotRec = await loadTaskDetail(tid)
        if (!isCurrent()) return null
      } catch {
        /* keep list row */
      }
    }
    if (!taskSnapshotMatchesModalFormIds(snapshotRec, formIdSet)) continue

    const matchedFormId =
      parseFormIdsFromTaskSnapshot(snapshotRec.inputSnapshot).find((id) => formIdSet.has(id)) ??
      parseFormIdFromTaskSnapshot(snapshotRec.inputSnapshot)

    const currentFormId = ctx.resolveFormIdForSceneIndex(sceneIdx)
    // 双保险：任务绑定的 formId 必须是当前 Tab 形态
    if (
      currentFormId != null &&
      matchedFormId != null &&
      Number(matchedFormId) !== Number(currentFormId)
    ) {
      continue
    }

    return {
      taskId: tid,
      taskKind: mapTaskTypeToModalKind(ty),
      sceneIdx,
      imageIdx: ctx.currentImageIndex.get() >= 0 ? ctx.currentImageIndex.get() : 0,
      editorScopeKey,
      formId: matchedFormId ?? formIds[0] ?? null
    }
  }
  return null
}
