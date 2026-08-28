import { useCreationStore } from '~/stores/creation'

/** 用于分镜脚本 / 分镜视频 / 配音批量等异步流程：切换作品后禁止写当前 Pinia 或误 toast */
export type CreationLiveGenScopeCtx = {
  scopeKey: string
  projectId: number | null
  episodeId: number | null
}

export function captureCreationLiveGenScope(): CreationLiveGenScopeCtx {
  const store = useCreationStore.getState()
  return {
    scopeKey: store.step3GenVisualScopeKey(),
    projectId: store.currentProjectId,
    episodeId: store.currentEpisodeId
  }
}

export function matchesCreationLiveGenScope(ctx: CreationLiveGenScopeCtx): boolean {
  const store = useCreationStore.getState()
  return (
    store.step3GenVisualScopeKey() === ctx.scopeKey &&
    store.currentProjectId === ctx.projectId &&
    store.currentEpisodeId === ctx.episodeId
  )
}
