import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { runFormImageGenerateCardTask } from '~/composables/useFormImageGenerateCardTask'
import { runFormImageUpscaleTask } from '~/composables/useFormImageUpscaleTask'
import { runMultiViewImageTask } from '~/composables/useMultiViewImageTask'
import type { MultiAngleGeneratePayload } from '~/utils/multiAngleCameraPrompt'
import { formatTaskSseJoinedLiveText } from '~/utils/taskSseProgressText'
import {
isDeferredModalFollowResult
} from './sceneModalTaskParsers'
import type { EditSceneImageModalCtx,SceneModalTaskOwner } from './types'

export function createSceneModalCanvasGenerateActions(ctx: EditSceneImageModalCtx) {
async function handleSettingCardSelect(payload: {
  agentCode?: string
  modelCode?: string
  imageIndex: number
}) {
  if (!ctx.isSettingCardTypeSupported()) {
    message.warning('设定卡生成仅支持角色形态')
    return
  }
  if (!ctx.whiteBaseImageReadyForSettingCard()) {
    message.warning('请先选择平台生成或本地上传的角色图')
    return
  }

  const agentCode = String(payload.agentCode || '').trim()
  const modelCode = String(payload.modelCode || '').trim()
  if (!agentCode && !modelCode) {
    message.warning('请选择智能体')
    return
  }

  const sceneIdx = ctx.currentSceneIndex.get()
  const modalScope = ctx.captureModalScopeSnapshot(sceneIdx)
  const imgIdx = payload.imageIndex
  const img = ctx.currentSceneImages()[imgIdx] as { rpsImageId?: number } | undefined
  const imageId = Number(img?.rpsImageId)
  if (!Number.isFinite(imageId) || imageId <= 0) {
    message.warning('图片ID不能为空')
    return
  }

  ctx.beginCanvasTaskOverlay(sceneIdx, imgIdx, '设定卡生成中…', 'setting-card')
  let submittedTaskId: number | null = null
  /** SSE 被新跟随抢占时保留 overlay / Pinia，交由 restore 续跟 */
  let deferOverlayCleanup = false
  const liveScope = captureCreationLiveGenScope()
  const scopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  const taskOwner = (): SceneModalTaskOwner => ({
    editorScopeKey: scopeKey,
    taskId: submittedTaskId,
    liveGenScopeKey: liveScope.scopeKey
  })
  const clearSettingTaskUi = () =>
    ctx.clearSceneModalTaskStateIfOwned(taskOwner(), { sceneIdx })
  try {
    let res = await runFormImageGenerateCardTask({
      imageId,
      projectId: ctx.store().currentProjectId,
      ...(agentCode ? { agentCode } : {}),
      ...(modelCode ? { modelCode } : {}),
      onSubmitted: ({ taskId }) => {
        submittedTaskId = taskId
        ctx.persistSceneModalSseTask(sceneIdx, imgIdx, 'setting-card', taskId, { imageId })
      },
      onProgress: (p) => {
        ctx.applyCanvasProgressIfCurrent({
          liveGenScopeKey: liveScope.scopeKey,
          editorScopeKey: scopeKey,
          taskId: submittedTaskId,
          text: formatTaskSseJoinedLiveText(p, '设定卡生成中…')
        })
      }
    })
    if (res.ok === false && submittedTaskId != null) {
      res = await ctx.resolveSettingCardFollowResult(submittedTaskId, res)
    }
    if (isDeferredModalFollowResult(res)) {
      deferOverlayCleanup = true
      if (scopeKey) {
        ctx.handleDeferredSceneModalFollow({
          sceneIdx,
          editorScopeKey: scopeKey,
          liveGenScopeKey: liveScope.scopeKey,
          errorMessage: (res as { errorMessage?: unknown }).errorMessage
        })
      }
      return
    }
    if (!matchesCreationLiveGenScope(liveScope)) {
      ctx.clearSceneModalTaskStateIfOwned(taskOwner())
      return
    }
    if (res.ok === false) {
      clearSettingTaskUi()
      if (ctx.isSameModalScope(modalScope)) message.error(res.errorMessage)
      return
    }
    if (!res.imageUrl) {
      clearSettingTaskUi()
      if (ctx.isSameModalScope(modalScope)) message.success('角色设定卡已生成')
      return
    }
    ctx.clearSceneModalTaskStateIfOwned(taskOwner())
    await ctx.applySettingCardGenerateSuccess(
      {
        imageUrl: res.imageUrl,
        imageId: res.imageId ?? null
      },
      { taskId: submittedTaskId, isCurrent: () => ctx.isSameModalScope(modalScope) }
    )
    clearSettingTaskUi()
  } finally {
    if (!deferOverlayCleanup && matchesCreationLiveGenScope(liveScope) && ctx.canClearSceneModalTaskUi(taskOwner())) {
      ctx.endCanvasTaskOverlay(sceneIdx, imgIdx)
    }
  }
}

const handleUpscaleModelSelect = async (payload: {
  modelCode: string
  resolution: string
  imageIndex: number
}) => {
  const sceneIdx = ctx.currentSceneIndex.get()
  const index = payload.imageIndex
  const img = ctx.currentSceneImages()[index] as any
  if (!img?.url) {
    message.warning('请先选择一张可用图片')
    return
  }
  const rid = Number(img?.rpsImageId)
  let imageId: number | null = Number.isFinite(rid) && rid > 0 ? rid : null
  if (imageId == null) {
    imageId = await ctx.resolveImageIdFromFormImageList({
      formId: img?.rpsFormId,
      imageId: img?.rpsImageId,
      imageUrl: img?.url,
      imageTitle: img?.title || img?.name
    })
  }
  if (imageId == null || !Number.isFinite(imageId)) {
    message.error('未找到形态图实例 ID，无法提交高清任务（请确认已同步到个人资产形态图）')
    return
  }

  /** 剧集隔离：任务归属启动时作品/集；切集后终态收尾不得写当前集 store、不得 toast */
  const liveScope = captureCreationLiveGenScope()

  ctx.beginCanvasTaskOverlay(sceneIdx, index, '正在提交高清任务…', 'upscale')
  const scenesSnapshot = ctx.cloneScenesForTask()
  const taskContext = {
    sceneIndex: sceneIdx,
    imageIndex: index,
    editorScopeKey: ctx.buildEditorScopeKeyForSceneIndex(sceneIdx),
    assetId: ctx.activeRpsAssetId(),
    taskId: null as number | null
  }
  ctx.upscaleContext.current = taskContext

  let upscaleResult: Awaited<ReturnType<typeof runFormImageUpscaleTask>>
  try {
    upscaleResult = await runFormImageUpscaleTask({
      imageId,
      modelCode: payload.modelCode,
      resolution: payload.resolution,
      onSubmitted: ({ taskId }) => {
        taskContext.taskId = taskId
        ctx.persistSceneModalSseTask(sceneIdx, index, 'upscale', taskId, { imageId })
      },
      onProgress: (p) => {
        ctx.applyCanvasProgressIfCurrent({
          liveGenScopeKey: liveScope.scopeKey,
          editorScopeKey: taskContext.editorScopeKey,
          taskId: taskContext.taskId,
          text:
            formatTaskSseJoinedLiveText(p, '') ||
            (typeof p.percent === 'number' ? `${Math.round(p.percent)}%` : '') ||
            '高清处理中…'
        })
      }
    })
  } catch (e: unknown) {
    if (ctx.upscaleContext.current === taskContext) {
      ctx.upscaleUiPhase.set('failed')
      ctx.canvasOverlayTaskKind.set(null)
      ctx.upscaleFailedMessage.set(String((e as Error)?.message || '高清任务异常'))
    }
    return
  }

  const taskCtx = taskContext
  const taskOwner: SceneModalTaskOwner = {
    editorScopeKey: taskCtx.editorScopeKey,
    taskId: taskCtx.taskId,
    liveGenScopeKey: liveScope.scopeKey
  }

  /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast、不回写图片 */
  if (!matchesCreationLiveGenScope(liveScope)) {
    ctx.clearSceneModalTaskStateIfOwned(taskOwner)
    return
  }

  // 关弹窗再进 / 双重 restore / suspend：旧 SSE 被抢占，勿清 Pinia / 勿标失败；按策略延迟续跟
  if (upscaleResult.ok === false && upscaleResult.deferred) {
    if (taskCtx.editorScopeKey) {
      ctx.handleDeferredSceneModalFollow({
        sceneIdx,
        editorScopeKey: taskCtx.editorScopeKey,
        liveGenScopeKey: liveScope.scopeKey,
        errorMessage: upscaleResult.errorMessage
      })
    }
    return
  }

  if (upscaleResult.ok === false) {
    const canClearTaskUi = ctx.clearSceneModalTaskStateIfOwned(taskOwner, { sceneIdx })
    if (canClearTaskUi && ctx.upscaleContext.current === taskCtx) {
      ctx.upscaleUiPhase.set('failed')
      ctx.canvasOverlayTaskKind.set(null)
      ctx.upscaleFailedMessage.set(upscaleResult.errorMessage || '高清任务失败')
    }
    return
  }

  const canClearTaskUi = ctx.clearSceneModalTaskStateIfOwned(taskOwner, { sceneIdx })
  if (canClearTaskUi) ctx.endCanvasTaskOverlay(sceneIdx, index)
  if (ctx.upscaleContext.current === taskCtx) ctx.upscaleContext.current = null
  const sameScope =
    taskCtx.editorScopeKey === ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()) &&
    taskCtx.assetId === ctx.activeRpsAssetId()
  if (canClearTaskUi) {
    if (sameScope && ctx.currentSceneIndex.get() === taskCtx.sceneIndex && ctx.localSceneImages.get()[taskCtx.imageIndex]) {
      const row = ctx.localSceneImages.get()[taskCtx.imageIndex] as any
      row.url = upscaleResult.imageUrl
      row.thumbnail = upscaleResult.imageUrl
      ctx.localSceneImages.set([...ctx.localSceneImages.get()])
      ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
    } else {
      const prev = scenesSnapshot[taskCtx.sceneIndex] || { name: '', images: [] as any[] }
      const imgs = [...(prev.images || [])]
      if (imgs[taskCtx.imageIndex]) {
        imgs[taskCtx.imageIndex] = {
          ...imgs[taskCtx.imageIndex],
          url: upscaleResult.imageUrl,
          thumbnail: upscaleResult.imageUrl
        }
        ctx.emitSceneUpdate(taskCtx.sceneIndex, { ...prev, images: imgs }, taskCtx.editorScopeKey)
      }
    }
  }
  if (canClearTaskUi && sameScope && ctx.currentSceneIndex.get() === taskCtx.sceneIndex) {
    message.success('高清处理完成')
  }
}

const handleMultiAngle = (index: number) => {
  const img = ctx.currentSceneImages()[index]
  if (!img?.url) {
    message.warning('请先选择一张可用图片')
    return
  }
  ctx.multiAngleTargetIndex.current = index
  ctx.multiAngleImageUrl.set(img.url)
  ctx.showMultiAngleModal.set(true)
}

const handleMultiAngleGenerate = async (payload: MultiAngleGeneratePayload) => {
  const index = ctx.multiAngleTargetIndex.current
  if (index === null) return
  // 须与左侧列表一致：RPS 形态编辑时 localSceneImages 可能多于 props.scenes[].images（父级常仅同步 isUse=1），用 sceneRow.images 下标会误判并静默 return
  if (!ctx.currentSceneImages()[index]) {
    message.warning('当前图片已失效，请关闭多机位弹窗后重试')
    return
  }
  const sceneIdx = ctx.currentSceneIndex.get()
  const modalScope = ctx.captureModalScopeSnapshot(sceneIdx)
  const taskScopeKey = modalScope.editorScopeKey
  /** 剧集隔离：任务归属启动时作品/集；切集后终态收尾不得写当前集 store、不得 toast */
  const liveScope = captureCreationLiveGenScope()

  const rawImg = ctx.currentSceneImages()[index] as Record<string, unknown>
  const imageUrl = String(rawImg?.url || rawImg?.thumbnail || payload.imageUrl || '').trim()

  // 解析 formId 用于接口调用
  let formId: number | null = Number(rawImg?.rpsFormId)
  if (!Number.isFinite(formId) || formId <= 0) formId = null

  if (formId == null) {
    message.warning('当前图片缺少形态信息，无法发起多机位生图')
    return
  }

  // 与接口 modelCode 一致：须来自 image_multi_view 功能池（listByFunc）
  const modelCode = String(ctx.multiViewSelectedModel()?.id || '').trim()
  if (!modelCode) {
    message.warning('暂无可用多机位模型，请联系管理员配置 image_multi_view 功能池')
    return
  }

  ctx.beginCanvasTaskOverlay(sceneIdx, index, '多机位生图任务提交中...', 'multi-view')

  let submittedTaskId: number | null = null
  const result = await runMultiViewImageTask({
    formId,
    imageUrl,
    anglePrompt: payload.multiAnglePromptConcat,
    modelCode,
    aspectRatio: ctx.generationSettings.get().aspectRatio || undefined,
    onSubmitted: ({ taskId }) => {
      submittedTaskId = taskId
      ctx.persistSceneModalSseTask(sceneIdx, index, 'multi-view', taskId, { formId })
    },
    onProgress: (p) => {
      ctx.applyCanvasProgressIfCurrent({
        liveGenScopeKey: liveScope.scopeKey,
        editorScopeKey: taskScopeKey,
        taskId: submittedTaskId,
        text: formatTaskSseJoinedLiveText(p, '多机位生图中...')
      })
    }
  })

  // 关弹窗再进 / suspend：旧 SSE 被抢占，勿清 overlay / Pinia / 勿 toast；释放锁后交给 restore 续跟
  if (result.ok === false && result.deferred) {
    if (taskScopeKey) {
      ctx.handleDeferredSceneModalFollow({
        sceneIdx,
        editorScopeKey: taskScopeKey,
        liveGenScopeKey: liveScope.scopeKey,
        errorMessage: result.errorMessage
      })
    }
    return
  }

  const taskOwner: SceneModalTaskOwner = {
    editorScopeKey: taskScopeKey,
    taskId: submittedTaskId,
    liveGenScopeKey: liveScope.scopeKey
  }

  /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast、不回写图片 */
  if (!matchesCreationLiveGenScope(liveScope)) {
    ctx.clearSceneModalTaskStateIfOwned(taskOwner)
    return
  }

  const canClearTaskUi = ctx.clearSceneModalTaskStateIfOwned(taskOwner, { sceneIdx })
  if (canClearTaskUi) ctx.endCanvasTaskOverlay(sceneIdx, index)

  if (result.ok === false) {
    if (ctx.isSameModalScope(modalScope)) message.error(result.errorMessage || '多机位生图失败')
    return
  }
  await ctx.claimFormImagesForModal(submittedTaskId ?? 0, 'form_multi_view', { imageId: result.imageId })

  // 以 form-image/list 为准回填 URL（task resultData 中的 imageUrl 可能未走 @MediaUrl，直接展示会裂图）
  if (ctx.isSameModalScope(modalScope)) {
    await ctx.refreshFormImageListAfterTask(result.imageId, {
      isCurrent: () => ctx.isSameModalScope(modalScope)
    })
  }

  if (ctx.isSameModalScope(modalScope)) message.success('多机位生图完成')

  // 通知全局任务列表刷新
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }
}


  return {
    handleSettingCardSelect,
    handleUpscaleModelSelect,
    handleMultiAngle,
    handleMultiAngleGenerate
  }
}

