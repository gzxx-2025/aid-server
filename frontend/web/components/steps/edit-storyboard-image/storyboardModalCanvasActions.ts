import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
runStoryboardImageUpscaleTask
} from '~/composables/useStoryboardImageUpscaleTask'
import {
runStoryboardMultiViewGridImageTask
} from '~/composables/useStoryboardMultiViewGridImageTask'
import { modalGenSessionScopeFromScopeKey } from '~/utils/modalGenSessionScope'
import type { MultiAngleGeneratePayload } from '~/utils/multiAngleCameraPrompt'
import {
clearModalImageGenSession
} from '~/utils/storyboardImageModalGenSession'
import { activeStoryboardImageModalOverlayFollowIds } from '~/utils/storyboardImageModalOwnedFollow'
import { formatTaskSseJoinedLiveText } from '~/utils/taskSseProgressText'
import type {
EditStoryboardImageModalCtx
} from './types'
import type { StoryboardModalCanvasOverlayApi } from './useStoryboardModalCanvasOverlay'

const activeCanvasOverlayFollowStoryboardIds = activeStoryboardImageModalOverlayFollowIds

type CanvasActionCore = Pick<
  StoryboardModalCanvasOverlayApi,
  | 'beginCanvasTaskOverlay'
  | 'endCanvasTaskOverlay'
  | 'clearUpscaleOverlay'
  | 'clearModalCanvasOverlayLoadingUi'
>

export function createStoryboardModalCanvasActions(
  ctx: EditStoryboardImageModalCtx,
  core: CanvasActionCore
) {
  const {
    beginCanvasTaskOverlay,
    endCanvasTaskOverlay,
    clearUpscaleOverlay,
    clearModalCanvasOverlayLoadingUi
  } = core

const handleUpscaleModelSelect = async (payload: {
  modelCode: string
  resolution: string
  imageIndex: number
}) => {
  const sceneIdx = ctx.currentSceneIndex.get()
  const imageIndex = payload.imageIndex
  const img = ctx.currentSceneImages()[imageIndex] as any
  if (!img?.url) {
    message.warning('请先选择一张可用图片')
    return
  }

  const storyboardId = ctx.sceneStoryboardIdNum(sceneIdx)
  if (storyboardId == null) {
    message.warning('分镜ID缺失，无法发起变清晰')
    return
  }

  const genRecordId = ctx.resolveStoryboardRecordId(img)
  if (genRecordId == null) {
    message.warning('当前图片无有效生成记录，请先生成分镜图后再使用变清晰')
    return
  }

  const modelCode = String(payload.modelCode || '').trim()
  if (!modelCode) {
    message.warning('暂无可用高清模型，请联系管理员配置 image_upscale 功能池')
    return
  }

  const beforeCount = (ctx.props().scenes[sceneIdx]?.images || []).length

  /** 剧集隔离：任务归属启动时 scope；切集后终态收尾不得写当前集扁平 store、不得 toast */
  const taskScope = captureCreationLiveGenScope()
  const taskSessionScope = modalGenSessionScopeFromScopeKey(taskScope.scopeKey)

  beginCanvasTaskOverlay(sceneIdx, imageIndex, 'upscale', '正在提交高清任务…')
  ctx.upscaleContext.current = { sceneIndex: sceneIdx, imageIndex }
  activeCanvasOverlayFollowStoryboardIds.add(storyboardId)

  let upscaleResult: Awaited<ReturnType<typeof runStoryboardImageUpscaleTask>>
  try {
    upscaleResult = await runStoryboardImageUpscaleTask({
      genRecordId,
      modelCode,
      resolution: payload.resolution,
      onSubmitted: ({ taskId }) => {
        ctx.store().setStoryboardImageGenTask(
          storyboardId,
          { taskId, sceneIdx, kind: 'upscale', imageIdx: imageIndex },
          taskScope.scopeKey
        )
        ctx.syncModalImageGenSessionTaskId(
          storyboardId,
          sceneIdx,
          taskId,
          {
            tab: 'upscale',
            imageIdx: imageIndex
          },
          taskSessionScope,
          taskScope.scopeKey
        )
        ctx.suspendLateModalImageFollowIfScopeChanged(taskId, taskScope)
      },
      onProgress: (p) => {
        ctx.upscaleProgressText.set(
          formatTaskSseJoinedLiveText(p, '') ||
            (typeof p.percent === 'number' ? `${Math.round(p.percent)}%` : '') ||
            '高清处理中…'
        )
        const task = ctx.getModalImageGenTask(storyboardId)
        if (task?.taskId) {
          ctx.store().setStoryboardImageGenTask(
            storyboardId,
            {
              taskId: task.taskId,
              sceneIdx,
              kind: 'upscale',
              imageIdx: imageIndex,
              message: p.message,
              stepTitle: p.stepTitle
            },
            taskScope.scopeKey
          )
        }
      }
    })
  } catch (e: unknown) {
    if (!matchesCreationLiveGenScope(taskScope)) {
      ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
      clearModalImageGenSession(taskSessionScope)
      return
    }
    ctx.upscaleUiPhase.set('failed')
    ctx.canvasOverlayTaskKind.set('upscale')
    clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
    ctx.store().clearStoryboardImageGenTask(storyboardId)
    ctx.clearStoryboardPanelImageGenerating(storyboardId)
    ctx.upscaleFailedMessage.set(String((e as Error)?.message || '高清任务异常'))
    return
  } finally {
    activeCanvasOverlayFollowStoryboardIds.delete(storyboardId)
  }

  /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast */
  if (!matchesCreationLiveGenScope(taskScope)) {
    ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
    clearModalImageGenSession(taskSessionScope)
    return
  }

  const overlayCtx = ctx.upscaleContext.current
  if (!overlayCtx) {
    clearUpscaleOverlay()
    return
  }

  if (!upscaleResult.ok) {
    ctx.upscaleUiPhase.set('failed')
    ctx.canvasOverlayTaskKind.set('upscale')
    clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
    ctx.store().clearStoryboardImageGenTask(storyboardId)
    ctx.clearStoryboardPanelImageGenerating(storyboardId)
    ctx.clearLocalGeneratingPlaceholdersForScene(sceneIdx)
    ctx.upscaleFailedMessage.set(
      'errorMessage' in upscaleResult
        ? upscaleResult.errorMessage || '高清任务失败'
        : '高清任务失败'
    )
    return
  }

  clearModalCanvasOverlayLoadingUi(storyboardId, sceneIdx, imageIndex, 'upscale')

  await ctx.refreshSceneRecords(
    overlayCtx.sceneIndex,
    upscaleResult.recordId ?? genRecordId,
    beforeCount,
    {
      force: true
    }
  )

  message.success('高清处理完成')
}

const handleMultiAngle = (imageIndex: number) => {
  const img = ctx.currentSceneImages()[imageIndex]
  if (!img?.url) {
    message.warning('请先选择一张可用图片')
    return
  }
  ctx.multiAngleTargetIndex.current = imageIndex
  ctx.multiAngleImageUrl.set(img.url)
  ctx.showMultiAngleModal.set(true)
}

const handleMultiAngleGenerate = async (payload: MultiAngleGeneratePayload) => {
  const imageIndex = ctx.multiAngleTargetIndex.current
  if (imageIndex === null) return
  if (!ctx.currentSceneImages()[imageIndex]) {
    message.warning('当前图片已失效，请关闭多机位弹窗后重试')
    return
  }

  const storyboardId = ctx.currentStoryboardId()
  if (!storyboardId) {
    message.warning('分镜ID缺失，无法发起多机位生图')
    return
  }

  const rawImg = ctx.currentSceneImages()[imageIndex] as Record<string, unknown>
  const imageUrl = String(rawImg?.url || rawImg?.thumbnail || payload.imageUrl || '').trim()
  if (!imageUrl) {
    message.warning('当前图片地址无效，无法发起多机位生图')
    return
  }

  const isNineGrid = payload.mode === 'nineGridFixed'
  const modelCode = String(
    (isNineGrid ? ctx.nineGridSelectedModel() : ctx.multiViewSelectedModel())?.id || ''
  ).trim()
  if (!modelCode) {
    message.warning(isNineGrid ? '暂无可用九宫格生图模型' : '请先选择多机位生图模型')
    return
  }

  let angles: string[]
  if (isNineGrid) {
    angles = (payload.nineGridAngles || []).map((a) => String(a || '').trim())
    if (angles.length !== 9 || angles.some((a) => !a)) {
      message.warning('九宫格机位数据异常，请重试')
      return
    }
  } else {
    const anglePrompt = String(payload.multiAnglePromptConcat || '').trim()
    if (!anglePrompt) {
      message.warning('机位提示词不能为空')
      return
    }
    angles = [anglePrompt]
  }

  const sceneIdx = ctx.currentSceneIndex.get()
  const beforeCount = (ctx.props().scenes[sceneIdx]?.images || []).length
  const overlayKind = isNineGrid ? 'ninegrid' : 'multiangle'

  /** 剧集隔离：任务归属启动时 scope；切集后终态收尾不得写当前集扁平 store、不得 toast */
  const taskScope = captureCreationLiveGenScope()
  const taskSessionScope = modalGenSessionScopeFromScopeKey(taskScope.scopeKey)

  beginCanvasTaskOverlay(
    sceneIdx,
    imageIndex,
    overlayKind,
    isNineGrid ? '九宫格生图任务提交中...' : '多机位生图任务提交中...'
  )
  activeCanvasOverlayFollowStoryboardIds.add(storyboardId)

  let result: Awaited<ReturnType<typeof runStoryboardMultiViewGridImageTask>>
  try {
    result = await runStoryboardMultiViewGridImageTask({
      storyboardId,
      imageUrl,
      angles,
      modelCode,
      aspectRatio: isNineGrid
        ? ctx.nineGridAspectRatio.get() || '1:1'
        : ctx.generationSettings.get().aspectRatio || '1:1',
      onSubmitted: ({ taskId }) => {
        ctx.store().setStoryboardImageGenTask(
          storyboardId,
          { taskId, sceneIdx, kind: overlayKind, imageIdx: imageIndex },
          taskScope.scopeKey
        )
        ctx.syncModalImageGenSessionTaskId(
          storyboardId,
          sceneIdx,
          taskId,
          {
            tab: overlayKind,
            imageIdx: imageIndex
          },
          taskSessionScope,
          taskScope.scopeKey
        )
        ctx.suspendLateModalImageFollowIfScopeChanged(taskId, taskScope)
      },
      onProgress: (p) => {
        ctx.upscaleProgressText.set(
          p.message || p.stepTitle || (isNineGrid ? '九宫格生图中...' : '多机位生图中...')
        )
        const task = ctx.getModalImageGenTask(storyboardId)
        if (task?.taskId) {
          ctx.store().setStoryboardImageGenTask(
            storyboardId,
            {
              taskId: task.taskId,
              sceneIdx,
              kind: overlayKind,
              imageIdx: imageIndex,
              message: p.message,
              stepTitle: p.stepTitle
            },
            taskScope.scopeKey
          )
        }
      }
    })
  } catch (e: unknown) {
    if (!matchesCreationLiveGenScope(taskScope)) {
      ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
      clearModalImageGenSession(taskSessionScope)
      return
    }
    clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
    ctx.store().clearStoryboardImageGenTask(storyboardId)
    ctx.clearStoryboardPanelImageGenerating(storyboardId)
    endCanvasTaskOverlay()
    message.error(
      String((e as Error)?.message || (isNineGrid ? '九宫格生图失败' : '多机位生图失败'))
    )
    return
  } finally {
    activeCanvasOverlayFollowStoryboardIds.delete(storyboardId)
  }

  // 关弹窗再进：旧跟随被抢占 / 良性断连且任务仍进行中 → 勿清 Pinia、勿 toast
  if (!result.ok && 'deferred' in result && result.deferred) {
    return
  }

  /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast */
  if (!matchesCreationLiveGenScope(taskScope)) {
    ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
    clearModalImageGenSession(taskSessionScope)
    return
  }

  if (!result.ok) {
    clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
    ctx.store().clearStoryboardImageGenTask(storyboardId)
    ctx.clearStoryboardPanelImageGenerating(storyboardId)
    endCanvasTaskOverlay()
    message.error(
      'errorMessage' in result
        ? result.errorMessage || (isNineGrid ? '九宫格生图失败' : '多机位生图失败')
        : isNineGrid
          ? '九宫格生图失败'
          : '多机位生图失败'
    )
    return
  }

  clearModalCanvasOverlayLoadingUi(storyboardId, sceneIdx, imageIndex, overlayKind)
  await ctx.refreshSceneRecords(sceneIdx, result.recordId, beforeCount, { force: true })

  message.success(isNineGrid ? '九宫格生图完成' : '多机位生图完成')
}

const handleModifyImage = (imageIndex: number) => {
  const img = ctx.currentSceneImages()[imageIndex]
  if (!img?.url) {
    message.warning('请先选择一张可编辑的图片')
    return
  }
  ctx.touchEditImageUrl.set(img.url)
  ctx.showTouchEditModal.set(true)
}


  return {
    handleUpscaleModelSelect,
    handleMultiAngle,
    handleMultiAngleGenerate,
    handleModifyImage
  }
}
