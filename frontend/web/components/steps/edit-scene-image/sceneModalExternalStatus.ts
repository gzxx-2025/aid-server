import {
  buildFormSlotKey,
  isLegacyBareFormSlotScopeKey,
  parseBareFormSlotKey,
  parseFormEditorScopeKey
} from '~/utils/step3FormEditorScopeKey'
import type { EditSceneImageModalCtx } from './types'

function resolveFormParentTab(
  ctx: EditSceneImageModalCtx
): 'character' | 'prop' | null {
  if (ctx.props().imageType === 'form' && ctx.props().formParentAssetType === 'prop') {
    return 'prop'
  }
  if (
    ctx.props().imageType === 'form' &&
    (ctx.props().formParentAssetType === 'character' || !ctx.props().formParentAssetType)
  ) {
    return 'character'
  }
  if (ctx.props().imageType === 'prop') return 'prop'
  if (ctx.props().imageType === 'character') return 'character'
  return null
}

/** 弹窗内发起任务时同步列表卡片 generating，便于刷新后列表/流程条恢复 loading */
export function syncExternalGeneratingForModalScopeImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
) {
  const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!editorScopeKey) return

  const formTyped = parseFormEditorScopeKey(editorScopeKey)
  if (formTyped) {
    const slotKey = buildFormSlotKey(formTyped.assetIndex, formTyped.formIndex)
    if (formTyped.tab === 'prop') {
      ctx.store().setPropFormGenerationStatus(slotKey, 'generating')
    } else {
      ctx.store().setCharacterFormGenerationStatus(slotKey, 'generating')
    }
    return
  }

  const sceneMatch = editorScopeKey.match(/^scene-(\d+)$/i)
  if (sceneMatch) {
    ctx.store().setSceneGenerationStatus(Number(sceneMatch[1]), 'generating')
    return
  }

  const charMatch = editorScopeKey.match(/^character-(\d+)$/i)
  if (charMatch) {
    const ci = Number(charMatch[1])
    ctx.store().setCharacterFormGenerationStatus(`${ci}-0`, 'generating')
    return
  }

  const propMatch = editorScopeKey.match(/^prop-(\d+)$/i)
  if (propMatch) {
    const pi = Number(propMatch[1])
    ctx.store().setPropFormGenerationStatus(`${pi}-0`, 'generating')
    return
  }

  if (isLegacyBareFormSlotScopeKey(editorScopeKey)) {
    if (resolveFormParentTab(ctx) === 'prop') {
      ctx.store().setPropFormGenerationStatus(editorScopeKey, 'generating')
    } else {
      ctx.store().setCharacterFormGenerationStatus(editorScopeKey, 'generating')
    }
  }
}

export function slotHasLoadedImagesForModalImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): boolean {
  const parentImages = ctx.props().scenes[sceneIdx]?.images
  if (Array.isArray(parentImages) && parentImages.some((img) => String(img?.url ?? '').trim())) {
    return true
  }
  if (sceneIdx === ctx.currentSceneIndex.get()) {
    return ctx.localSceneImages.get().some((img) => String(img?.url ?? '').trim())
  }
  return false
}

/** 形态图已就绪时，将外层 Pinia generating 回落为 success，避免弹窗 Tab/记录卡误显 loading */
export function markExternalGeneratingCompleteForModalScopeImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
) {
  const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!editorScopeKey) return
  const hasImages = slotHasLoadedImagesForModalImpl(ctx, sceneIdx)
  const nextStatus = hasImages ? 'success' : 'idle'

  const formTyped = parseFormEditorScopeKey(editorScopeKey)
  if (formTyped) {
    const slotKey = buildFormSlotKey(formTyped.assetIndex, formTyped.formIndex)
    if (formTyped.tab === 'prop') {
      ctx.store().setPropFormGenerationStatus(slotKey, nextStatus)
    } else {
      ctx.store().setCharacterFormGenerationStatus(slotKey, nextStatus)
    }
    return
  }

  const sceneMatch = editorScopeKey.match(/^scene-(\d+)$/i)
  if (sceneMatch) {
    ctx.store().setSceneGenerationStatus(Number(sceneMatch[1]), nextStatus)
    return
  }

  const charMatch = editorScopeKey.match(/^character-(\d+)$/i)
  if (charMatch) {
    const ci = Number(charMatch[1])
    // 角色主资产弹窗：与 syncExternal 一致，只同步默认形态槽 `${ci}-0`
    ctx.store().setCharacterFormGenerationStatus(`${ci}-0`, nextStatus)
    return
  }

  const propMatch = editorScopeKey.match(/^prop-(\d+)$/i)
  if (propMatch) {
    const pi = Number(propMatch[1])
    ctx.store().setPropFormGenerationStatus(`${pi}-0`, nextStatus)
    return
  }

  if (isLegacyBareFormSlotScopeKey(editorScopeKey)) {
    if (resolveFormParentTab(ctx) === 'prop') {
      ctx.store().setPropFormGenerationStatus(editorScopeKey, nextStatus)
    } else {
      ctx.store().setCharacterFormGenerationStatus(editorScopeKey, nextStatus)
    }
  }
}

/** 与 syncExternalGeneratingForModalScope 成对：SSE 失败/取消后清除外层 Tab/列表 generating */
export function clearExternalGeneratingForModalScopeImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
) {
  const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!editorScopeKey) return

  const formTyped = parseFormEditorScopeKey(editorScopeKey)
  if (formTyped) {
    const slotKey = buildFormSlotKey(formTyped.assetIndex, formTyped.formIndex)
    if (formTyped.tab === 'prop') {
      ctx.store().setPropFormGenerationStatus(slotKey, 'idle')
    } else {
      ctx.store().setCharacterFormGenerationStatus(slotKey, 'idle')
    }
    return
  }

  const sceneMatch = editorScopeKey.match(/^scene-(\d+)$/i)
  if (sceneMatch) {
    ctx.store().setSceneGenerationStatus(Number(sceneMatch[1]), 'idle')
    return
  }

  const charMatch = editorScopeKey.match(/^character-(\d+)$/i)
  if (charMatch) {
    const ci = Number(charMatch[1])
    ctx.store().setCharacterFormGenerationStatus(`${ci}-0`, 'idle')
    return
  }

  const propMatch = editorScopeKey.match(/^prop-(\d+)$/i)
  if (propMatch) {
    const pi = Number(propMatch[1])
    ctx.store().setPropFormGenerationStatus(`${pi}-0`, 'idle')
    return
  }

  if (isLegacyBareFormSlotScopeKey(editorScopeKey)) {
    if (resolveFormParentTab(ctx) === 'prop') {
      ctx.store().setPropFormGenerationStatus(editorScopeKey, 'idle')
    } else {
      ctx.store().setCharacterFormGenerationStatus(editorScopeKey, 'idle')
    }
  }
}

export function isEditorScopeGeneratingExternallyImpl(
  ctx: EditSceneImageModalCtx,
  sceneIdx: number
): boolean {
  const scope = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!scope) return false

  const formTyped = parseFormEditorScopeKey(scope)
  if (formTyped) {
    const slotKey = buildFormSlotKey(formTyped.assetIndex, formTyped.formIndex)
    if (formTyped.tab === 'prop') {
      return ctx.store().propFormGenerationStatus[slotKey] === 'generating'
    }
    return ctx.store().characterFormGenerationStatus[slotKey] === 'generating'
  }

  const sceneMatch = scope.match(/^scene-(\d+)$/i)
  if (sceneMatch) {
    return ctx.store().sceneGenerationStatus[Number(sceneMatch[1])] === 'generating'
  }
  const charMatch = scope.match(/^character-(\d+)$/i)
  if (charMatch) {
    const ci = Number(charMatch[1])
    // 勿用 startsWith(`${ci}-`)：同角色其它形态 generating 不应点亮当前角色 Tab
    return ctx.store().characterFormGenerationStatus[`${ci}-0`] === 'generating'
  }
  const propMatch = scope.match(/^prop-(\d+)$/i)
  if (propMatch) {
    const pi = Number(propMatch[1])
    return ctx.store().propFormGenerationStatus[`${pi}-0`] === 'generating'
  }
  const bare = parseBareFormSlotKey(scope)
  if (bare) {
    // 历史裸键：只查当前弹窗所属 Tab，禁止 OR 两边（否则道具 loading 会点亮角色弹窗）
    if (resolveFormParentTab(ctx) === 'prop') {
      return ctx.store().propFormGenerationStatus[scope] === 'generating'
    }
    return ctx.store().characterFormGenerationStatus[scope] === 'generating'
  }
  return ctx.store().sceneGenerationStatus[sceneIdx] === 'generating'
}
