import { message } from 'antd'
import {
followEditImageTask
} from '~/composables/useEditImageTask'
import {
followFormImageGenerateCardTask,
recoverFormImageGenerateCardResultFromDetail
} from '~/composables/useFormImageGenerateCardTask'
import { followFormImageUpscaleTask } from '~/composables/useFormImageUpscaleTask'
import { followMultiViewImageTask } from '~/composables/useMultiViewImageTask'
import { waitUserTaskSseTerminal } from '~/composables/useTaskSseFollow'
import type { SceneModalSseTaskKind,SceneModalSseTaskSnapshot } from '~/stores/creation'
import { userTaskDetailCached } from '~/utils/businessApi'
import { parseImageIdsFromTaskInputSnapshot } from '~/utils/formImageAutoUse'
import { getStep3FormImageTaskMeta } from '~/utils/step3FormImageTaskRegistry'
import {
isDeferredModalFollowResult
} from './sceneModalTaskParsers'
import type {
EditSceneImageModalCtx,
GenericModalTaskFollowResult,
SceneModalTaskDetail,
SceneModalTaskOwner,
} from './types'

const FORM_IMAGE_TASK_SETTLED_EVENT = 'create-flow-form-image-task-settled'

export function createSceneModalTaskResultOps(ctx: EditSceneImageModalCtx) {
function resolveModalStep3Tab(): 'scene' | 'character' | 'prop' {
  if (ctx.props().imageType === 'scene') return 'scene'
  if (ctx.props().imageType === 'prop' || ctx.props().formParentAssetType === 'prop') return 'prop'
  return 'character'
}

/** 弹窗接管列表 SSE 后，终态须通知外层列表 finalize，否则卡片 loading 只清 Pinia、本地 ref 会残留 */
function notifyFormImageTaskSettledFromModal(payload: {
  taskId: number
  ok: boolean
  completeData?: unknown
  errorMessage?: string
  taskType?: string | null
  tab?: 'scene' | 'character' | 'prop'
}) {
  if (typeof window === 'undefined') return
  const taskId = Number(payload.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) return
  const meta = getStep3FormImageTaskMeta(taskId)
  window.dispatchEvent(
    new CustomEvent(FORM_IMAGE_TASK_SETTLED_EVENT, {
      detail: {
        taskId,
        ok: payload.ok,
        completeData: payload.completeData,
        errorMessage: payload.errorMessage,
        taskType: payload.taskType ?? meta?.taskType ?? 'form_image',
        tab: payload.tab ?? meta?.tab ?? resolveModalStep3Tab()
      }
    })
  )
}

async function followGenericExtractTaskForModal(payload: {
  taskId: number
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
}): Promise<GenericModalTaskFollowResult> {
  const taskId = Number(payload.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      onProgress: payload.onProgress
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      return { ok: false, errorMessage: '生图任务超时，请在任务中心查看进度' }
    }

    const r = terminal.event
    if (r.type === 'complete' || r.type === 'partial_failed') {
      return { ok: true, completeData: r.data, eventType: r.type }
    }
    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }
    return { ok: false, errorMessage: r.errorMessage || '生图任务失败' }
  } catch (e: unknown) {
    return { ok: false, errorMessage: String((e as Error)?.message || '生图任务异常') }
  }
}

function sceneModalTaskKindToAutoUseType(taskKind: SceneModalSseTaskKind): string | null {
  if (taskKind === 'edit-image' || taskKind === 'dialogue') return 'form_edit_chat'
  if (taskKind === 'form-image') return 'form_image'
  if (taskKind === 'multi-view') return 'form_multi_view'
  if (taskKind === 'setting-card') return 'form_card_image_batch'
  return null
}

async function claimFormImagesFromSceneModalTaskResult(
  taskId: number,
  taskKind: SceneModalSseTaskKind,
  result: unknown
): Promise<void> {
  const taskType = sceneModalTaskKindToAutoUseType(taskKind)
  if (!taskType) return
  if (taskKind === 'edit-image' || taskKind === 'dialogue') {
    const r = result as Awaited<ReturnType<typeof followEditImageTask>>
    if (r.ok === false) return
    await ctx.claimFormImagesForModal(taskId, taskType, { items: r.items })
    return
  }
  if (taskKind === 'multi-view') {
    const r = result as Awaited<ReturnType<typeof followMultiViewImageTask>>
    if (r.ok === false) return
    await ctx.claimFormImagesForModal(taskId, taskType, { imageId: r.imageId })
    return
  }
  if (taskKind === 'setting-card') {
    const r = result as Awaited<ReturnType<typeof followFormImageGenerateCardTask>>
    if (r.ok === false) return
    if (r.imageId != null) {
      await ctx.claimFormImagesForModal(taskId, taskType, { imageId: r.imageId })
    }
  }
}

async function applySettingCardGenerateSuccess(
  result: { imageUrl: string; imageId: number | null },
  opts?: {
    silentComplete?: boolean
    skipClaim?: boolean
    taskId?: number | null
    isCurrent?: () => boolean
  }
) {
  const isCurrent = opts?.isCurrent ?? (() => true)
  if (!opts?.skipClaim && result.imageId != null) {
    await ctx.claimFormImagesForModal(opts?.taskId ?? 0, 'form_card_image_batch', { imageId: result.imageId })
  }
  if (!isCurrent()) return
  await ctx.refreshFormImageListAfterTask(result.imageId, {
    imageUrl: result.imageUrl,
    isCurrent
  })
  if (!isCurrent()) return
  let idx = ctx.localSceneImages.get().findIndex((x: any) => {
    const byId =
      result.imageId != null &&
      Number.isFinite(result.imageId) &&
      Number(x?.rpsImageId) === result.imageId
    const byUrl = String(x?.url || '').trim() === result.imageUrl
    return byId || byUrl
  })
  if (idx < 0) {
    idx = ctx.appendSettingCardToLocalListIfMissing({
      imageId: result.imageId,
      imageUrl: result.imageUrl,
      title: '角色设定卡'
    })
  }
  if (idx >= 0) ctx.currentImageIndex.set(idx)
  ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
  if (!opts?.silentComplete) message.success('角色设定卡已生成')
}

async function resolveSettingCardFollowResult(
  taskId: number,
  result: Awaited<ReturnType<typeof followFormImageGenerateCardTask>>
): Promise<Awaited<ReturnType<typeof followFormImageGenerateCardTask>>> {
  if (result.ok) return result
  const recovered = await recoverFormImageGenerateCardResultFromDetail(taskId)
  if (recovered?.ok) return recovered
  return result
}

async function applySceneModalSseTaskResult(
  snap: SceneModalSseTaskSnapshot,
  result: unknown,
  opts?: {
    silentComplete?: boolean
    isCurrent?: () => boolean
    taskDetail?: SceneModalTaskDetail
    liveGenScopeKey?: string
  }
): Promise<number[] | undefined> {
  const { sceneIdx, imageIdx, taskKind } = snap
  const scopeKey = snap.editorScopeKey
  const modalScope = {
    editorScopeKey: scopeKey,
    assetId: ctx.rpsAssetIdForSceneIndex(sceneIdx)
  }
  const isCurrent =
    opts?.isCurrent ??
    (() =>
      ctx.props().open &&
      ctx.currentSceneIndex.get() === sceneIdx &&
      ctx.isSameModalScope(modalScope))
  const taskOwner: SceneModalTaskOwner = {
    editorScopeKey: snap.editorScopeKey,
    taskId: snap.taskId,
    liveGenScopeKey: opts?.liveGenScopeKey ?? ctx.currentModalLiveGenScopeKey()
  }
  const clearTaskUi = () => ctx.clearSceneModalTaskStateIfOwned(taskOwner, { sceneIdx })

  if (isDeferredModalFollowResult(result)) {
    return undefined
  }

  if (taskKind === 'edit-image' || taskKind === 'dialogue') {
    const r = result as Awaited<ReturnType<typeof followEditImageTask>>
    if (r.ok === false) {
      if (!opts?.silentComplete && isCurrent()) message.error(r.errorMessage || '生图失败')
      clearTaskUi()
      return undefined
    }
    await claimFormImagesFromSceneModalTaskResult(snap.taskId, taskKind, r)
    if (isCurrent()) await ctx.refreshAfterEditChatGenerate(r.items, modalScope)
    clearTaskUi()
    if (!opts?.silentComplete && isCurrent()) message.success('生图完成')
    return undefined
  }

  if (taskKind === 'form-image') {
    const r = result as GenericModalTaskFollowResult
    if (r.ok === false) {
      if (!opts?.silentComplete && isCurrent()) message.error(r.errorMessage || '生图失败')
      clearTaskUi()
      notifyFormImageTaskSettledFromModal({
        taskId: snap.taskId,
        ok: false,
        completeData: r.completeData,
        errorMessage: r.errorMessage || '生图失败',
        taskType: 'form_image'
      })
      return undefined
    }
    if (isCurrent()) {
      await ctx.initFormImageListOnOpen()
      if (isCurrent()) ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
    }
    clearTaskUi()
    notifyFormImageTaskSettledFromModal({
      taskId: snap.taskId,
      ok: true,
      completeData: r.completeData,
      taskType: r.eventType === 'partial_failed' ? 'form_image_batch' : 'form_image'
    })
    if (!opts?.silentComplete && isCurrent()) message.success('生图完成')
    return undefined
  }

  if (taskKind === 'upscale') {
    const r = result as Awaited<ReturnType<typeof followFormImageUpscaleTask>>
    if (r.ok === false) {
      if (!opts?.silentComplete && isCurrent()) message.error(r.errorMessage || '高清任务失败')
      clearTaskUi()
      return undefined
    }
    if (isCurrent() && ctx.localSceneImages.get()[imageIdx]) {
      const next = [...ctx.localSceneImages.get()]
      const row = { ...next[imageIdx] } as any
      row.url = r.imageUrl
      row.thumbnail = r.imageUrl
      next[imageIdx] = row
      ctx.localSceneImages.set(next)
      ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
    }
    clearTaskUi()
    if (!opts?.silentComplete && isCurrent()) message.success('高清处理完成')
    return undefined
  }

  if (taskKind === 'multi-view') {
    const r = result as Awaited<ReturnType<typeof followMultiViewImageTask>>
    if (r.ok === false) {
      if (!opts?.silentComplete && isCurrent()) message.error(r.errorMessage || '多机位生图失败')
      clearTaskUi()
      return undefined
    }
    await claimFormImagesFromSceneModalTaskResult(snap.taskId, taskKind, r)
    if (isCurrent()) await ctx.refreshFormImageListAfterTask(r.imageId, { isCurrent })
    clearTaskUi()
    if (!opts?.silentComplete && isCurrent()) message.success('多机位生图完成')
    return undefined
  }

  if (taskKind === 'setting-card') {
    const taskId = snap.taskId
    const r = await resolveSettingCardFollowResult(
      taskId,
      result as Awaited<ReturnType<typeof followFormImageGenerateCardTask>>
    )
    let sourceImageIds: number[] = []
    try {
      const detail = opts?.taskDetail ?? (await userTaskDetailCached(taskId))
      sourceImageIds = parseImageIdsFromTaskInputSnapshot(detail?.inputSnapshot)
      if (detail?.resultData != null) {
        await ctx.claimFormImagesForModal(taskId, 'form_card_image_batch', detail.resultData)
      }
    } catch {
      /* ignore */
    }
    if (!sourceImageIds.length && snap.imageId != null) {
      const single = Number(snap.imageId)
      if (Number.isFinite(single) && single > 0) sourceImageIds = [single]
    }

    if (r.ok === false) {
      if (!opts?.silentComplete && isCurrent()) message.error(r.errorMessage || '设定卡生成失败')
      clearTaskUi()
      return sourceImageIds
    }

    if (r.imageUrl && isCurrent()) {
      await applySettingCardGenerateSuccess(
        { imageUrl: r.imageUrl, imageId: r.imageId ?? null },
        { ...opts, silentComplete: true, skipClaim: true }
      )
    } else if (isCurrent()) {
      await ctx.initFormImageListOnOpen()
      if (isCurrent()) ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
      if (!opts?.silentComplete && isCurrent()) message.success('角色设定卡已生成')
    }

    clearTaskUi()
    return sourceImageIds
  }

  return undefined
}


  return {
    resolveModalStep3Tab,
    notifyFormImageTaskSettledFromModal,
    followGenericExtractTaskForModal,
    sceneModalTaskKindToAutoUseType,
    claimFormImagesFromSceneModalTaskResult,
    applySettingCardGenerateSuccess,
    resolveSettingCardFollowResult,
    applySceneModalSseTaskResult
  }
}
