import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { RouteLikeLocation } from '~/types/routeLike'
import { userStoryboardList } from '~/utils/businessApi'
import { mapStoryboardListRowToPanel } from '~/utils/storyboardPanelMap'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

/**
 * 批量弹窗列表刷新约定（与 BatchGenerate*Modal 打开时机对齐）：
 * - 分镜图 / 分镜视频：本文件 `refreshStoryboardPanelsFromApiForBatchModal` → POST /api/user/storyboard/list
 * - 场景/角色/道具图与设定卡：父组件 `refreshBatchGenerateAssetItems` → POST /api/user/asset/rps/list
 *   （经 loadPersonalAssetsForTab 写回本地态后再渲染弹窗列表，不直接使用打开前的缓存封面）
 */

/**
 * 批量分镜弹窗打开前刷新：以 /api/user/storyboard/list 为唯一数据源，
 * 同步脚本 / 分镜视频 / 配音三处列表，避免外层列表操作后主图/主视频状态陈旧。
 */
export async function refreshStoryboardPanelsFromApiForBatchModal(
  route: RouteLikeLocation
): Promise<StoryboardPanel[] | null> {
  // 非组件上下文，必须走 getState() 而不是 hook 调用
  const creationStore = useCreationStore.getState()
  const ctx = await resolveStoryScriptSaveContext(creationStore, route)
  if (!ctx) return null

  const list = await userStoryboardList({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId
  })
  const sorted = [...list].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  const panels = sorted.map((row, index) => mapStoryboardListRowToPanel(row, index))
  applyStoryboardScriptPanelsFromApi(panels)
  return creationStore.formData.storyboardScript.panels as StoryboardPanel[]
}
