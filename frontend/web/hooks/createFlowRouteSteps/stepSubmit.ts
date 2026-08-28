'use client'

/**
 * 提交审核 / 发布至案例广场（原 useCreateFlowRouteAndSteps.handleSubmit 拆分）。
 */

import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import {
userEpisodeList,
userEpisodeSubmitAudit,
userProjectPublish,
userProjectSubmitAudit
} from '~/utils/businessApi'
import { applyEpisodeRowToCreationStore } from '~/utils/hydrateCreationStoreFromProjectDetail'
import {
auditSubmitBlockedReason,
canSubmitAudit,
hasPendingReauditVideo,
isProjectPublicLockError,
needsSubmitAuditBeforePublish,
projectPublicLockUserHint
} from '~/utils/projectAudit'
import { fetchUserProjectDetailOnce } from '~/utils/userProjectDetailOnce'

export interface FlowSubmitDeps {
  getCanSubmit(): boolean
  navigateToWorks(): void
}

/**
 * 发布链路（严格顺序，任一步失败即终止）：
 * 1. 更新项目封面/描述（已在弹窗内完成，成功才会回调到这里）
 * 2. 提交审核 / 重新提交审核（按需）
 * 3. 发布
 * 不再调用合成完整视频 / 下载接口（仅「导出完整视频」可触发）
 */
export async function runFlowSubmit(
  deps: FlowSubmitDeps,
  opts?: {
    alsoPublish?: boolean
    /** 发布弹窗已确认的封面/描述；优先于 detail 回落 */
    coverUrl?: string
    projectDesc?: string
  }
): Promise<boolean> {
  const creationStore = useCreationStore.getState()
  const hasExportedVideo =
    creationStore.currentExportStatus === 2 ||
    Boolean(String(creationStore.currentFinalVideoUrl || '').trim()) ||
    Boolean(String(creationStore.currentPendingVideoUrl || '').trim())
  // 已成功导出成片后的「发布至案例广场」：不因本地步骤表单未回填而拦截
  if (!deps.getCanSubmit() && !(opts?.alsoPublish && hasExportedVideo)) {
    message.warning('请完成所有步骤后再提交审核')
    return false
  }
  const projectId = creationStore.currentProjectId
  if (!projectId || projectId <= 0) {
    message.error('缺少项目信息，无法提交审核')
    return false
  }
  const projectType = creationStore.currentProjectType ?? 'movie'
  try {
    const projectDetail = await fetchUserProjectDetailOnce(projectId)
    const resolvePublishBody = () => {
      const coverUrl = String(opts?.coverUrl || projectDetail.coverUrl || '').trim()
      const projectDesc = String(opts?.projectDesc || projectDetail.projectDesc || '').trim()
      if (!coverUrl) {
        message.warning('请上传作品封面')
        return null
      }
      if (!projectDesc) {
        message.warning('请填写作品描述')
        return null
      }
      return { id: projectId, coverUrl, projectDesc }
    }
    if (projectType === 'series') {
      const episodeId = creationStore.currentEpisodeId
      if (episodeId == null || episodeId <= 0) {
        message.error('请先选择要提交审核的剧集')
        return false
      }
      const episodes = await userEpisodeList({ projectId })
      const episode = episodes.find((row) => row.id === episodeId)
      if (!episode) {
        message.error('剧集不存在，请刷新后重试')
        return false
      }
      applyEpisodeRowToCreationStore(useCreationStore.getState(), episode)

      if (episode.status === 3) {
        message.warning('作品审核中，请耐心等待')
        return false
      }

      // 发布链路：仅成片变更（待审新片）或未过审时才提审
      const shouldAudit = needsSubmitAuditBeforePublish(episode)
      if (opts?.alsoPublish) {
        const publishBody = resolvePublishBody()
        if (!publishBody) return false
        if (shouldAudit) {
          await userEpisodeSubmitAudit({ id: episodeId })
        }
        try {
          await userProjectPublish(publishBody)
          message.success(
            shouldAudit
              ? hasPendingReauditVideo(episode)
                ? '已重新提交审核并发布，请等待审核通过后展示在案例广场'
                : '已提交审核并发布，请等待审核通过后展示在案例广场'
              : '作品已发布至案例广场'
          )
        } catch (publishErr: unknown) {
          if (shouldAudit) {
            message.success('已提交审核，通过后将展示在案例广场')
          } else {
            throw publishErr
          }
        }
        // 发布至案例广场成功后停留在成品预览，不跳转「我的作品」
        return true
      }

      const blocked = auditSubmitBlockedReason(episode)
      if (blocked) {
        message.warning(blocked)
        return false
      }
      await userEpisodeSubmitAudit({ id: episodeId })
      message.success(hasPendingReauditVideo(episode) ? '新片已重新提交审核' : '剧集已提交审核')
    } else {
      useCreationStore.getState().setCurrentMediaContext({
        projectStatus: projectDetail.status ?? null,
        projectIsPublic: projectDetail.isPublic ?? null,
        episodeStatus: projectDetail.status ?? null,
        episodeEditorId: projectDetail.episodeEditorId ?? null,
        finalVideoUrl: projectDetail.finalVideoUrl ?? null,
        pendingVideoUrl: projectDetail.pendingVideoUrl ?? null,
        exportStatus: projectDetail.exportStatus ?? null
      })

      if (projectDetail.status === 3) {
        message.warning('作品审核中，请耐心等待')
        return false
      }

      const shouldAudit = needsSubmitAuditBeforePublish(projectDetail)
      if (opts?.alsoPublish) {
        const publishBody = resolvePublishBody()
        if (!publishBody) return false
        if (shouldAudit) {
          await userProjectSubmitAudit({ id: projectId })
        }
        try {
          await userProjectPublish(publishBody)
          message.success(
            shouldAudit
              ? hasPendingReauditVideo(projectDetail)
                ? '已重新提交审核并发布，请等待审核通过后展示在案例广场'
                : '已提交审核并发布，请等待审核通过后展示在案例广场'
              : '作品已发布至案例广场'
          )
        } catch (publishErr: unknown) {
          if (shouldAudit) {
            message.success('已提交审核，通过后将展示在案例广场')
          } else {
            throw publishErr
          }
        }
        // 发布至案例广场成功后停留在成品预览，不跳转「我的作品」
        return true
      }

      const blocked = auditSubmitBlockedReason(projectDetail)
      if (blocked) {
        message.warning(blocked)
        return false
      }
      if (!canSubmitAudit(projectDetail)) {
        message.warning('当前状态无法提交审核')
        return false
      }
      await userProjectSubmitAudit({ id: projectId })
      message.success(
        hasPendingReauditVideo(projectDetail) ? '新片已重新提交审核' : '项目已提交审核'
      )
    }
    deps.navigateToWorks()
    return true
  } catch (e: unknown) {
    if (isProjectPublicLockError(e)) {
      message.error(projectPublicLockUserHint())
      return false
    }
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || (opts?.alsoPublish ? '发布失败' : '提交审核失败'))
    return false
  }
}
