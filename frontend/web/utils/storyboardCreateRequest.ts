import type { UserStoryboardCreateRequest } from '~/types/business-api'

export interface StoryboardCreateContext {
  projectId: number
  episodeId: number
}

/** 组装新增/复制分镜请求；复制来源必须随 create 一次提交，避免先建空壳再异步补内容。 */
export function buildStoryboardCreateRequest(
  context: StoryboardCreateContext,
  title?: string,
  sourceStoryboardId?: number
): UserStoryboardCreateRequest {
  const request: UserStoryboardCreateRequest = {
    projectId: context.projectId,
    episodeId: context.episodeId,
    title
  }
  if (sourceStoryboardId != null && Number.isFinite(sourceStoryboardId) && sourceStoryboardId > 0) {
    request.sourceStoryboardId = sourceStoryboardId
  }
  return request
}
