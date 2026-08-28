import { buildModalEditorScopeKey } from '~/composables/useModalTaskScope'
import type { AssetExtractType } from '~/types/business-api'
import type { EditSceneImageModalCtx,ModalScopeSnapshot } from './types'

export function rpsAssetIdForSceneIndexImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): number | null {
  if (ctx.props().imageType === 'form') return ctx.props().rpsAssetId ?? null
  const map = ctx.props().rpsAssetIdsByIndex
  if (map) {
    const v = map[sceneIdx]
    return v != null && Number.isFinite(Number(v)) ? Number(v) : null
  }
  return ctx.props().rpsAssetId ?? null
}

export function rpsFormIdsForSceneIndexImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): number[] {
  if (ctx.props().imageType === 'form') return ctx.props().rpsFormIds ?? []
  const map = ctx.props().rpsFormIdsByIndex
  if (map) {
    const arr = map[sceneIdx]
    return Array.isArray(arr) ? arr : []
  }
  return ctx.props().rpsFormIds ?? []
}

export function resolveFormIdForSceneIndexImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): number | null {
  const formIds = rpsFormIdsForSceneIndexImpl(ctx, sceneIdx) ?? []
  const fid = Number(formIds[sceneIdx] ?? formIds[0] ?? NaN)
  return Number.isFinite(fid) && fid > 0 ? fid : null
}

export function buildEditorScopeKeyForSceneIndexImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): string {
  return buildModalEditorScopeKey(ctx.props().editorScopeKey, sceneIdx)
}

/** 画布 loading 遮罩唯一键：含资产/形态/弹窗实例，避免列表 A/B 同为 `0-0` 时串流 */
export function buildCanvasOverlayKeyImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number,
  imgIdx: number
): string {
  const scope = buildEditorScopeKeyForSceneIndexImpl(ctx, sceneIdx)
  const assetId = rpsAssetIdForSceneIndexImpl(ctx, sceneIdx) ?? ''
  const formId = resolveFormIdForSceneIndexImpl(ctx, sceneIdx) ?? ''
  return `${scope}|${ctx.props().imageType}|${assetId}|${formId}|${sceneIdx}|${imgIdx}`
}

export function captureModalScopeSnapshotImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): ModalScopeSnapshot {
  return {
    editorScopeKey: buildEditorScopeKeyForSceneIndexImpl(ctx, sceneIdx),
    assetId: rpsAssetIdForSceneIndexImpl(ctx, sceneIdx)
  }
}

export function isSameModalScopeImpl(
  ctx: EditSceneImageModalCtx,
  snapshot: ModalScopeSnapshot
): boolean {
  return (
    snapshot.editorScopeKey ===
      buildEditorScopeKeyForSceneIndexImpl(ctx, ctx.currentSceneIndex.get()) &&
    snapshot.assetId === rpsAssetIdForSceneIndexImpl(ctx, ctx.currentSceneIndex.get())
  )
}

export function cloneScenesForTaskImpl(ctx: EditSceneImageModalCtx) {
  return ctx.props().scenes.map((s) => ({
    ...s,
    images: [...(s.images || [])]
  }))
}

export function resolveSceneModalAssetTypeImpl(ctx: EditSceneImageModalCtx): AssetExtractType {
  if (
    ctx.props().imageType === 'character' ||
    (ctx.props().imageType === 'form' && ctx.props().formParentAssetType === 'character')
  ) {
    return 'character'
  }
  if (
    ctx.props().imageType === 'prop' ||
    (ctx.props().imageType === 'form' && ctx.props().formParentAssetType === 'prop')
  ) {
    return 'prop'
  }
  return 'scene'
}
