'use client'

import { message } from 'antd'
import { buildModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import { isStoryboardVideoTaskOngoing } from '~/composables/useStoryboardVideoGenerateTask'
import type { AsyncPromptApplyTicket } from '~/utils/asyncPromptApplyGuard'
import {
userStoryboardGenerateVideoPrompt,
userStoryboardGenerateVideoPromptGrid,
userStoryboardGenerateVideoPromptImage
} from '~/utils/businessApi'
import {
resolveStoryboardGenConfigLlmFields,
STORYBOARD_GEN_CONFIG_SCENE_CODES
} from '~/utils/projectGenConfig'
import {
awaitStoryboardPromptGenerateTask,
fetchStoryboardPromptPlainWithRetry,
resumeStoryboardPromptGenerateTask,
sanitizeStoryboardPromptModelCode
} from '~/utils/storyboardPromptGenerateFlow'
import { confirmTaskResumeWithBilling } from '~/utils/taskResumeBilling'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { VideoModalCtx,VideoModalPromptFlowsApi,VideoPromptGenTaskKind } from './types'
import type { Mirrored } from './useMirrored'

/** 视频提示词生成任务：提交 / SSE 跟随 / 刷新恢复（原 runStoryboardVideoPromptGenerateFlow 段逻辑） */
export function useVideoModalPromptFlows(ctx: VideoModalCtx): void {
  async function fetchStoryboardImageToVideoPromptAfterGenerate(
    storyboardId: number
  ): Promise<string> {
    return fetchStoryboardPromptPlainWithRetry(storyboardId, 'videoPromptImage')
  }

  async function fetchStoryboardMultiVideoPromptAfterGenerate(storyboardId: number): Promise<string> {
    return fetchStoryboardPromptPlainWithRetry(storyboardId, 'videoPrompt')
  }

  function resolveVideoPromptModelCode(): string {
    return sanitizeStoryboardPromptModelCode(
      ctx.store().storyboardVideoGenerateSettings.videoPromptModelCode
    )
  }

  /** 图生视频提示词：手动「生成设置」优先，否则读项目生成配置 */
  async function resolveImageVideoPromptSubmitFields() {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    const manualAgent = ctx.resolveImageVideoPromptAgentCode()
    const manualModel = resolveVideoPromptModelCode()
    const manualPick = Boolean(manualAgent || manualModel)
    return resolveStoryboardGenConfigLlmFields(
      saveCtx?.projectId ?? null,
      STORYBOARD_GEN_CONFIG_SCENE_CODES.videoPromptImage,
      manualPick,
      manualAgent,
      manualModel
    )
  }

  /** 宫格视频提示词：手动「生成设置」优先，否则读项目生成配置 */
  async function resolveGridVideoPromptSubmitFields() {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    const manualAgent = ctx.resolveGridVideoPromptAgentCode()
    const manualModel = resolveVideoPromptModelCode()
    const manualPick = Boolean(manualAgent || manualModel)
    return resolveStoryboardGenConfigLlmFields(
      saveCtx?.projectId ?? null,
      STORYBOARD_GEN_CONFIG_SCENE_CODES.videoPromptGrid,
      manualPick,
      manualAgent,
      manualModel
    )
  }

  /** 多参视频提示词：手动「生成设置」优先，否则读项目生成配置 */
  async function resolveMultiVideoPromptSubmitFields() {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    const manualAgent = ctx.resolveMultiVideoPromptAgentCode()
    const manualModel = resolveVideoPromptModelCode()
    const manualPick = Boolean(manualAgent || manualModel)
    return resolveStoryboardGenConfigLlmFields(
      saveCtx?.projectId ?? null,
      STORYBOARD_GEN_CONFIG_SCENE_CODES.videoPrompt,
      manualPick,
      manualAgent,
      manualModel
    )
  }

  function resolveVideoPromptGenUiRefs(taskKind: VideoPromptGenTaskKind): {
    targetKey: Mirrored<string>
    isGenerating: Mirrored<boolean>
  } {
    if (taskKind === 'multi-video-prompt-gen') {
      return {
        targetKey: ctx.multiParamPromptGenerateTargetKey,
        isGenerating: ctx.isGeneratingMultiParamPrompt
      }
    }
    return {
      targetKey: ctx.videoPromptGenerateTargetKey,
      isGenerating: ctx.isGeneratingVideoPrompt
    }
  }

  /** 刷新或重新打开弹窗后，恢复当前分镜的视频提示词生成 loading 与 SSE 追踪 */
  async function restoreStoryboardVideoPromptGenerateIfNeeded(sceneIdx: number) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return
    if (ctx.activeStoryboardPromptFollowStoryboardIds.has(storyboardId)) return

    const persisted = ctx.store().getStoryboardVideoPromptGenTask(storyboardId)
    const taskId = persisted?.taskId ?? null
    if (!taskId) return

    const taskKind: VideoPromptGenTaskKind = persisted?.taskKind ?? 'video-prompt-gen'
    const promptChannel = taskKind === 'multi-video-prompt-gen' ? 'multiParam' : 'imageToVideo'
    const promptApplyTicket = ctx.beginVideoPromptApply(promptChannel, storyboardId)
    const ui = resolveVideoPromptGenUiRefs(taskKind)

    const gen = ++ctx.resumeStoryboardVideoPromptFollowGen.current
    const ongoing = await isStoryboardVideoTaskOngoing(taskId)
    if (gen !== ctx.resumeStoryboardVideoPromptFollowGen.current) return

    if (!ongoing) {
      ctx.store().clearStoryboardVideoPromptGenTask(storyboardId)
      return
    }

    ui.targetKey.set(buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, taskKind)))
    ui.isGenerating.set(true)
    ctx.activeStoryboardPromptFollowStoryboardIds.add(storyboardId)
    ctx.activeStoryboardPromptFollowTaskIds.add(taskId)
    let keepPendingUi = false

    try {
      let taskOutcome = await awaitStoryboardPromptGenerateTask(taskId)
      if (gen !== ctx.resumeStoryboardVideoPromptFollowGen.current) {
        keepPendingUi = true
        return
      }
      if (taskOutcome.ok === false) {
        keepPendingUi = 'deferred' in taskOutcome && !!taskOutcome.deferred
        return
      }

      if (taskOutcome.ok && 'partial' in taskOutcome && taskOutcome.partial) {
        const partialWarning = taskOutcome.partialWarning
        const shouldResume = await confirmTaskResumeWithBilling(taskId, {
          title: '部分生成失败',
          content: partialWarning,
          okText: '续生',
          cancelText: '暂不续生'
        })
        if (shouldResume) {
          taskOutcome = await resumeStoryboardPromptGenerateTask(taskId, 'video')
          if (taskOutcome.ok === false && 'deferred' in taskOutcome && taskOutcome.deferred) {
            keepPendingUi = true
            return
          }
        }
      }

      if (taskOutcome.ok !== false && sceneIdx === ctx.currentSceneIndex.get()) {
        const prompt =
          taskKind === 'multi-video-prompt-gen'
            ? await fetchStoryboardMultiVideoPromptAfterGenerate(storyboardId)
            : await fetchStoryboardImageToVideoPromptAfterGenerate(storyboardId)
        if (prompt) {
          let applied = false
          if (taskKind === 'multi-video-prompt-gen') {
            applied = await ctx.applyMultiParamPromptFromApi(prompt, promptApplyTicket)
          } else {
            applied = await ctx.applyVideoPromptFromApi(prompt, promptApplyTicket)
          }
          if (applied) {
            await ctx.refreshRecommendedDurationAfterPromptGenerate(storyboardId)
          }
        }
      }
    } catch {
      /* ignore */
    } finally {
      ctx.activeStoryboardPromptFollowStoryboardIds.delete(storyboardId)
      ctx.activeStoryboardPromptFollowTaskIds.delete(taskId)
      if (
        !keepPendingUi &&
        ui.targetKey.get() === buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, taskKind))
      ) {
        ui.targetKey.set('')
      }
      if (!keepPendingUi) {
        ui.isGenerating.set(false)
        ctx.store().clearStoryboardVideoPromptGenTask(storyboardId)
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
    }
  }

  async function runStoryboardVideoPromptGenerateFlow(opts: {
    sceneIdx: number
    taskKind: VideoPromptGenTaskKind
    loadingMessage: string
    successMessage: string
    isGenerating: Mirrored<boolean>
    targetKey: Mirrored<string>
    submit: (
      saveCtx: { projectId: number; episodeId: number },
      storyboardId: number
    ) => Promise<{
      taskId?: number
    }>
    fetchPromptAfterGenerate: (storyboardId: number) => Promise<string>
    applyPrompt: (plain: string, ticket: AsyncPromptApplyTicket) => Promise<boolean>
  }) {
    if (ctx.isStoryboardVideoPromptGeneratingForScene(opts.sceneIdx)) return

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法生成提示词')
      return
    }
    const promptChannel =
      opts.taskKind === 'multi-video-prompt-gen' ? 'multiParam' : 'imageToVideo'
    const promptApplyTicket = ctx.beginVideoPromptApply(promptChannel, storyboardId)

    opts.targetKey.set(buildModalTaskOverlayKey(ctx.overlayKeyParts(opts.sceneIdx, opts.taskKind)))
    opts.isGenerating.set(true)
    ctx.activeStoryboardPromptFollowStoryboardIds.add(storyboardId)
    ctx.resumeStoryboardVideoFollowGen.current++
    const hideLoading = message.loading(opts.loadingMessage, 0)
    let followedTaskId: number | null = null
    let keepPendingUi = false

    try {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
        return
      }

      const submitted = await opts.submit(saveCtx, storyboardId)
      const taskId = Number(submitted.taskId)
      if (!Number.isFinite(taskId) || taskId <= 0) {
        message.error('提交失败：未返回任务ID')
        return
      }

      followedTaskId = taskId
      ctx.activeStoryboardPromptFollowTaskIds.add(taskId)
      ctx.store().setStoryboardVideoPromptGenTask(storyboardId, {
        taskId,
        sceneIdx: opts.sceneIdx,
        taskKind: opts.taskKind
      })
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }

      let taskOutcome = await awaitStoryboardPromptGenerateTask(taskId)
      if (taskOutcome.ok === false) {
        if ('deferred' in taskOutcome && taskOutcome.deferred) {
          keepPendingUi = true
          return
        }
        const errMsg = taskOutcome.errorMessage
        if (errMsg.includes('取消')) {
          message.warning(errMsg)
        } else {
          message.error(errMsg)
        }
        return
      }
      if (taskOutcome.ok && 'partial' in taskOutcome && taskOutcome.partial) {
        const partialWarning = taskOutcome.partialWarning
        message.warning(partialWarning)
        const shouldResume = await confirmTaskResumeWithBilling(taskId, {
          title: '部分生成失败',
          content: partialWarning,
          okText: '续生',
          cancelText: '暂不续生'
        })
        if (shouldResume) {
          taskOutcome = await resumeStoryboardPromptGenerateTask(taskId, 'video')
          if (taskOutcome.ok === false) {
            if ('deferred' in taskOutcome && taskOutcome.deferred) {
              keepPendingUi = true
              return
            }
            message.error(taskOutcome.errorMessage)
            return
          }
          if (taskOutcome.ok && 'partial' in taskOutcome && taskOutcome.partial) {
            message.warning(taskOutcome.partialWarning)
          }
        }
      }

      const prompt = await opts.fetchPromptAfterGenerate(storyboardId)
      if (!prompt) {
        message.warning('生成完成，但未获取到视频提示词内容')
        return
      }

      const applied = await opts.applyPrompt(prompt, promptApplyTicket)
      if (!applied) {
        message.warning('检测到分镜切换或手动修改，已保留当前提示词')
        return
      }
      if (opts.sceneIdx === ctx.currentSceneIndex.get()) {
        await ctx.refreshRecommendedDurationAfterPromptGenerate(storyboardId)
      }
      message.success(opts.successMessage)
    } catch (e: unknown) {
      message.error(ctx.storyboardVideoBizErr(e))
    } finally {
      if (followedTaskId != null) {
        ctx.activeStoryboardPromptFollowTaskIds.delete(followedTaskId)
      }
      ctx.activeStoryboardPromptFollowStoryboardIds.delete(storyboardId)
      ctx.resumeStoryboardVideoFollowGen.current++
      hideLoading()
      if (!keepPendingUi) {
        opts.isGenerating.set(false)
        opts.targetKey.set('')
        ctx.store().clearStoryboardVideoPromptGenTask(storyboardId)
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
    }
  }

  async function handleImageToVideoGeneratePrompt() {
    const sceneIdx = ctx.currentSceneIndex.get()
    if (ctx.leftActiveTab.get() === 'gridVideo') {
      await runStoryboardVideoPromptGenerateFlow({
        sceneIdx,
        taskKind: 'grid-video-prompt-gen',
        loadingMessage: '正在生成宫格视频提示词...',
        successMessage: '宫格视频提示词生成成功',
        isGenerating: ctx.isGeneratingVideoPrompt,
        targetKey: ctx.videoPromptGenerateTargetKey,
        submit: async (saveCtx, storyboardId) => {
          const llmFields = await resolveGridVideoPromptSubmitFields()
          return userStoryboardGenerateVideoPromptGrid({
            projectId: saveCtx.projectId,
            episodeId: saveCtx.episodeId,
            storyboardIds: [storyboardId],
            ...llmFields
          })
        },
        fetchPromptAfterGenerate: fetchStoryboardImageToVideoPromptAfterGenerate,
        applyPrompt: ctx.applyVideoPromptFromApi
      })
      return
    }
    await runStoryboardVideoPromptGenerateFlow({
      sceneIdx,
      taskKind: 'video-prompt-gen',
      loadingMessage: '正在生成视频提示词...',
      successMessage: '视频提示词生成成功',
      isGenerating: ctx.isGeneratingVideoPrompt,
      targetKey: ctx.videoPromptGenerateTargetKey,
      submit: async (saveCtx, storyboardId) => {
        const llmFields = await resolveImageVideoPromptSubmitFields()
        return userStoryboardGenerateVideoPromptImage({
          projectId: saveCtx.projectId,
          episodeId: saveCtx.episodeId,
          storyboardIds: [storyboardId],
          ...llmFields
        })
      },
      fetchPromptAfterGenerate: fetchStoryboardImageToVideoPromptAfterGenerate,
      applyPrompt: ctx.applyVideoPromptFromApi
    })
  }

  async function handleMultiParamGeneratePrompt() {
    const sceneIdx = ctx.currentSceneIndex.get()
    await runStoryboardVideoPromptGenerateFlow({
      sceneIdx,
      taskKind: 'multi-video-prompt-gen',
      loadingMessage: '正在生成多参视频提示词...',
      successMessage: '多参视频提示词生成成功',
      isGenerating: ctx.isGeneratingMultiParamPrompt,
      targetKey: ctx.multiParamPromptGenerateTargetKey,
      submit: async (saveCtx, storyboardId) => {
        const llmFields = await resolveMultiVideoPromptSubmitFields()
        return userStoryboardGenerateVideoPrompt({
          projectId: saveCtx.projectId,
          episodeId: saveCtx.episodeId,
          storyboardIds: [storyboardId],
          ...llmFields
        })
      },
      fetchPromptAfterGenerate: fetchStoryboardMultiVideoPromptAfterGenerate,
      applyPrompt: ctx.applyMultiParamPromptFromApi
    })
  }

  const api: VideoModalPromptFlowsApi = {
    restoreStoryboardVideoPromptGenerateIfNeeded,
    handleImageToVideoGeneratePrompt,
    handleMultiParamGeneratePrompt
  }
  Object.assign(ctx, api)
}
