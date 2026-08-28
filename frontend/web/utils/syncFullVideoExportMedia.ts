import type { useCreationStore } from '~/stores/creation'
import { userEpisodeExportStatus } from '~/utils/businessApi'
type CreationStore = ReturnType<typeof useCreationStore>

/**
 * 打开「导出/发布」前同步成片导出态（export/status）。
 * status=0 时接口可能不回历史 finalVideoUrl，保留 store 中已有地址供「旧版」判断。
 */
export async function syncFullVideoExportMediaToStore(store: CreationStore): Promise<void> {
  const projectId = Number(store.currentProjectId)
  const episodeId = store.currentProjectType === 'movie' ? 0 : Number(store.currentEpisodeId)
  const editorId = Number(store.currentEpisodeEditorId)
  const hasEditor = Number.isFinite(editorId) && editorId > 0
  const hasProjectEpisode =
    Number.isFinite(projectId) &&
    projectId > 0 &&
    Number.isFinite(episodeId) &&
    episodeId >= 0
  if (!hasEditor && !hasProjectEpisode) return

  const status = await userEpisodeExportStatus({
    episodeEditorId: hasEditor ? editorId : null,
    projectId: hasProjectEpisode ? projectId : undefined,
    episodeId: hasProjectEpisode ? episodeId : undefined
  })

  const nextFinal = String(status.finalVideoUrl || '').trim()
  const nextPending = String(status.pendingVideoUrl || '').trim()
  const prevFinal = String(store.currentFinalVideoUrl || '').trim()

  store.setCurrentMediaContext({
    episodeEditorId: status.episodeEditorId,
    exportStatus: status.exportStatus,
    pendingVideoUrl: nextPending || null,
    // 导出成功/待审新片时更新；工程已改(status=0)接口常不带旧片 URL，沿用本地历史地址
    finalVideoUrl: nextFinal || prevFinal || null
  })
}
