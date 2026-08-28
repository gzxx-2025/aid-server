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
import type { ExtractAgents,ExtractModalScope } from '~/components/steps/ExtractAgentModal'
import { createTaskStream } from '~/composables/useTaskStream'
import type { AssetExtractType,UserTaskStatus } from '~/types/business-api'
import {
userAssetExtractEstimate,
userAssetExtractParallel
} from '~/utils/businessApi'
import type { ExtractModelCodes } from '~/utils/extractAgentBiz'
import { shouldAnnounceExtractSuccess } from '~/utils/extractTaskSuccessAnnounce'
import { inferExtractAssetTabFromSse } from '~/utils/inferExtractAssetTabFromSse'
import { buildAssetExtractSubmitBody } from '~/utils/assetExtractSubmitBody'
import { scriptExtractBaselineStore } from '~/utils/scriptExtractBaseline'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { formatPartialFailedMessage } from '~/utils/taskPartialFailed'
import { resolveStepIndexTotalFromSse } from '~/utils/taskSseProgressText'
import { requestCancelUserTaskById } from '~/utils/userTaskCancelFlow'

import { MIN_EXTRACTING_VISIBLE_MS,extractResultRefreshTypes,getExtractFlowContextKey,isBenignExtractStreamAbortError,isStoryScriptContentFilledForExtract,parseTaskId,releaseExtractStreamFollow,scopeToExtractTypes,sleep,store,syncExtractedAssetsFromServer,type ExtractAgentsRuntime } from '~/hooks/createFlowExtractAgents/extractRuntime'
export function startExtractAssets(
  runtime: ExtractAgentsRuntime,
  payload: {
    agents: ExtractAgents
    modelCodes: ExtractModelCodes
    manualModelPickByKind?: Partial<Record<AssetExtractType, boolean>>
    scope: ExtractModalScope
    overwrite?: boolean
  }
) {
  void (async () => {
    const { agents, modelCodes, scope } = payload
    const overwrite = payload.overwrite === true
    const manualModelPickByKind = payload.manualModelPickByKind ?? {}
    const flowCtxAtStart = getExtractFlowContextKey(runtime)
    const sessionAtStart = runtime.extractFollowSession.value
    store().updateExtractAgents(agents)
    store().updateExtractModelCodes(modelCodes)
    store().setExtractingAssets(true)
    runtime.extractStopRequested.value = false
    runtime.extractActiveTaskId.value = null
    runtime.extractStreamCloser.value = null
    store().syncExtractUiToCurrentScope()
    const extractingVisibleStartedAt = Date.now()
    let shouldKeepExtractingVisible = false

    const finishExtracting = () => {
      store().finishAssetExtractUiForCurrentScope()
    }

    const hasStoryScript = () =>
      isStoryScriptContentFilledForExtract(store().formData.storyScript.content)

    const ctx = await resolveStoryScriptSaveContext(store(), runtime.getRoute())
    if (!ctx) {
      message.warning('缺少项目或剧集信息，请从「我的作品」进入创作流程后再试')
      finishExtracting()
      return
    }
    if (!hasStoryScript()) {
      message.warning('剧本内容为空，无法提取')
      finishExtracting()
      return
    }

    const extractTypes = scopeToExtractTypes(scope)
    store().setExtractingStage(extractTypes[0] || 'scene')
    store().setExtractingStages({
      scene: extractTypes.includes('scene'),
      character: extractTypes.includes('character'),
      prop: extractTypes.includes('prop')
    })
    store().setExtractingTaskProgress({
      percent: 0,
      stepTitle: '提交提取任务',
      message: '',
      stepIndex: null,
      stepTotal: null
    })

    try {
      const estimate = await userAssetExtractEstimate({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId,
        extractTypes
      })
      if ((estimate?.existingCharacterCount ?? 0) > 0 && extractTypes.includes('character')) {
        message.warning('检测到已有角色，提取完成后将按后端规则更新角色列表')
      }

      if (runtime.extractStopRequested.value) {
        return
      }

      /** @returns 仅 SSE 真正 SUCCEEDED 时为 true；切页中断/停止/部分失败为 false */
      const runExtractOnce = async (types: AssetExtractType[]): Promise<boolean> => {
        if (types.length === 0) return true
        if (runtime.extractStopRequested.value) return false
        if (
          sessionAtStart !== runtime.extractFollowSession.value ||
          getExtractFlowContextKey(runtime) !== flowCtxAtStart
        ) {
          return false
        }
        store().setExtractingStage(types[0] || 'scene')
        store().setExtractingStages({
          scene: types.includes('scene'),
          character: types.includes('character'),
          prop: types.includes('prop')
        })
        store().setExtractingTaskProgress({
          percent: 0,
          stepTitle: '提交提取任务',
          message: '',
          stepIndex: null,
          stepTotal: null
        })

        if (runtime.extractStopRequested.value) return false

        const submitBody = await buildAssetExtractSubmitBody({
          store: store(),
          route: runtime.getRoute(),
          extractTypes: types,
          modelCodes,
          manualModelPickByKind,
          overwrite
        })
        if (!submitBody) throw new Error('缺少项目或剧集信息，无法提交提取任务')
        const task = await userAssetExtractParallel(submitBody)
        const taskId = parseTaskId((task as { taskId?: number }).taskId ?? task.id)
        if (!taskId) throw new Error('提取任务提交失败：未返回任务ID')
        if (runtime.extractStopRequested.value) {
          try {
            await requestCancelUserTaskById(taskId)
          } catch {
            /* ignore */
          }
          return false
        }
        shouldKeepExtractingVisible = true
        runtime.extractActiveTaskId.value = taskId
        runtime.extractStreamScopeKey.value = store().step3GenVisualScopeKey()
        store().setAssetExtractFollowTask(runtime.extractStreamScopeKey.value, taskId)
        store().setAssetExtractShellLiveTaskId(taskId)
        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
        }

        // 优先 SSE：实时进度；完成以 SSE 为准，不再请求 task/detail 轮询兜底
        let statusRes: { status: UserTaskStatus; errorMessage?: string | null } | null = null
        let streamConnected = false
        try {
          const stream = createTaskStream(taskId)
          runtime.extractStreamCloser.value = () => {
            try {
              stream.close()
            } catch {
              /* ignore */
            }
          }
          const stopWatch = stream.subscribeProgress((p) => {
            if (sessionAtStart !== runtime.extractFollowSession.value) return
            if (getExtractFlowContextKey(runtime) !== flowCtxAtStart) return
            if (!p) return
            store().setExtractingAssets(true)
            const msgText = String(p.message || '').trim()
            const titleText = String(p.stepTitle || '').trim()
            const { stepIndex, stepTotal } = resolveStepIndexTotalFromSse(p)
            store().setExtractingTaskProgress({
              percent:
                typeof p.progress === 'number'
                  ? p.progress
                  : store().extractingTaskProgress.percent,
              stepTitle: titleText || msgText || store().extractingTaskProgress.stepTitle,
              message: msgText || titleText,
              stepIndex,
              stepTotal
            })
            store().syncExtractUiToCurrentScope()
            const tab = inferExtractAssetTabFromSse({
              stage: p.stage,
              stepTitle: p.stepTitle,
              message: p.message
            })
            if (tab) store().setExtractingStage(tab)
          })
          try {
            const res = await stream.done
            if (res.type === 'error') {
              statusRes = { status: 'FAILED', errorMessage: res.errorMessage || '任务失败' }
            } else if (res.type === 'cancelled') {
              statusRes = { status: 'CANCELLED', errorMessage: res.message || '任务已取消' }
            } else if (res.type === 'partial_failed') {
              statusRes = {
                status: 'PARTIAL_FAILED',
                errorMessage: formatPartialFailedMessage(res.data, '部分提取失败，可在任务中心续生')
              }
            } else {
              statusRes = { status: 'SUCCEEDED', errorMessage: null }
            }
          } finally {
            streamConnected = stream.isConnected()
            stopWatch()
            runtime.extractStreamCloser.value = null
            runtime.extractActiveTaskId.value = null
            releaseExtractStreamFollow(runtime)
            try {
              stream.close()
            } catch {
              /* ignore */
            }
          }
        } catch (e: unknown) {
          runtime.extractStreamCloser.value = null
          runtime.extractActiveTaskId.value = null
          releaseExtractStreamFollow(runtime)
          if (
            sessionAtStart !== runtime.extractFollowSession.value ||
            getExtractFlowContextKey(runtime) !== flowCtxAtStart
          ) {
            return false
          }
          // 切页/主动 close 会 abort：不能当成功；交由外层会话门槛抑制误报 toast
          if (isBenignExtractStreamAbortError(e)) return false
          if (!streamConnected) throw e
          statusRes = {
            status: 'FAILED',
            errorMessage: String((e as { message?: string })?.message || '任务连接异常')
          }
        }

        if (runtime.extractStopRequested.value) {
          return false
        }
        if (
          sessionAtStart !== runtime.extractFollowSession.value ||
          getExtractFlowContextKey(runtime) !== flowCtxAtStart
        ) {
          return false
        }

        if (!statusRes) {
          throw new Error('AI 提取失败，请稍后重试')
        }

        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
        }

        store().setExtractingTaskProgress({
          percent: 100,
          stepTitle: '同步结果',
          message: ''
        })
        const refreshTypes = extractResultRefreshTypes(scope, types, runtime.getRoute())
        if (refreshTypes.length > 0) {
          const pending = await syncExtractedAssetsFromServer(ctx, refreshTypes)
          store().mergePendingExtractFormAssets(pending)
        }

        if (statusRes.status === 'PARTIAL_FAILED') {
          message.warning(statusRes.errorMessage || '部分提取失败，可在任务中心续生')
          return false
        }

        if (statusRes.status !== 'SUCCEEDED') {
          throw new Error(statusRes.errorMessage || 'AI 提取失败，请稍后重试')
        }
        return true
      }

      // 剧集模式互斥：character 不能和 scene/prop 同时提交（按文档拆成两次任务）
      const isSeries = String(estimate?.projectType || '').toLowerCase() === 'series'
      const hasCharacter = extractTypes.includes('character')
      const hasSceneOrProp = extractTypes.includes('scene') || extractTypes.includes('prop')
      let extractOk = false
      if (isSeries && hasCharacter && hasSceneOrProp) {
        message.info('剧集模式：角色与场景/道具需分两次提取，已为您自动拆分执行')
        const charOk = await runExtractOnce(['character'])
        if (runtime.extractStopRequested.value) {
          message.info('已取消提取')
          runtime.extractStopRequested.value = false
          return
        }
        const rest: AssetExtractType[] = []
        if (extractTypes.includes('scene')) rest.push('scene')
        if (extractTypes.includes('prop')) rest.push('prop')
        const restOk = await runExtractOnce(rest)
        extractOk = charOk && restOk
      } else {
        extractOk = await runExtractOnce(extractTypes)
      }

      if (runtime.extractStopRequested.value) {
        message.info('已取消提取')
        runtime.extractStopRequested.value = false
      } else if (
        shouldAnnounceExtractSuccess({
          extractOk,
          stopRequested: runtime.extractStopRequested.value,
          sessionAtStart,
          sessionNow: runtime.extractFollowSession.value,
          flowCtxAtStart,
          flowCtxNow: getExtractFlowContextKey(runtime)
        })
      ) {
        if (scope === 'all') message.success('场景、角色、道具提取已完成')
        else if (scope === 'scene') message.success('场景提取已完成')
        else if (scope === 'character') message.success('角色提取已完成')
        else message.success('道具提取已完成')
        scriptExtractBaselineStore.recordExtractSuccessBaseline({
          projectId: ctx.projectId,
          episodeId: ctx.episodeId,
          comicVersion: Number(store().scriptComicVersion) || 0,
          scriptHtml: store().formData.storyScript.content || ''
        })
        store().setExtractModalActionMode('start')
        store().setScriptChangeLightBannerVisible(false)
      }
    } catch (e: unknown) {
      if (
        sessionAtStart !== runtime.extractFollowSession.value ||
        getExtractFlowContextKey(runtime) !== flowCtxAtStart
      ) {
        return
      }
      if (runtime.extractStopRequested.value) {
        message.info('已取消提取')
        runtime.extractStopRequested.value = false
      } else if (!isBenignExtractStreamAbortError(e)) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || 'AI 提取失败，请稍后重试')
      }
    } finally {
      if (
        sessionAtStart === runtime.extractFollowSession.value &&
        getExtractFlowContextKey(runtime) === flowCtxAtStart
      ) {
        if (shouldKeepExtractingVisible) {
          const elapsed = Date.now() - extractingVisibleStartedAt
          const remain = MIN_EXTRACTING_VISIBLE_MS - elapsed
          if (remain > 0) {
            await sleep(remain)
          }
        }
        finishExtracting()
      }
    }
  })()
}
