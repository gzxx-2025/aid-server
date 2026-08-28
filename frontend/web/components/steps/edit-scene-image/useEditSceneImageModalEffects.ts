'use client'

import { useEffect,useRef } from 'react'
import { useCreateFlowScopeChangedResume } from '~/composables/useCreateFlowLiveGenResume'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { clearModalFollowLocksNotMatchingLiveGenScope } from '~/utils/liveGenScopeIsolation'
import { clearSceneImageModalUserDismissed } from '~/utils/sceneImageModalGenSession'
import {
drainStep3SseQueue,
hasStep3SseSlot,
releaseStep3SseSlot,
requeueStep3SseItemToEnd,
tryAcquireStep3SseSlot
} from '~/utils/step3SseConcurrencyGate'
import type {
EditSceneImageModalCtx,
ResolvedEditSceneImageModalProps
} from './types'

const GLOBAL_TASKS_UPDATED_EVENT = 'create-flow-global-tasks-updated'

export function useEditSceneImageModalEffects(
  ctx: EditSceneImageModalCtx,
  props: ResolvedEditSceneImageModalProps
) {
  const propsRef = useRef(props)
  propsRef.current = props
  const {
    activeRpsAssetId,
    activeRpsFormIds,
    lockLocalSceneImagesFromRps,
    currentSceneIndex,
    currentImageIndex,
    currentImg,
    applyCurrentFormImageEditPrefill,
    switchScene,
    switchImage,
    currentSceneImages,
    resumeSceneModalFollowGen,
    sceneModalTabActivationGen,
    tabSwitchSkeleton,
    buildEditorScopeKeyForSceneIndex,
    addedImageIds,
    pendingImage,
    isSceneSplitting,
    sceneSplitTargetKey,
    sceneSplitProgressText,
    sceneTabBarRef,
    lastInitFormImageListKey
  } = ctx

// —— 原 watch 逐条对应的 effect（声明顺序与原文件一致） ——

// 初始化本地场景图片列表（pending 仅保留父级尚不存在的 id，避免与 scene 重复叠加）
// 原 watch(() => [props.scenes, currentSceneIndex.value], { immediate: true, deep: true })
const scenesFingerprint = props.imageType === 'scene' ? JSON.stringify(props.scenes) : ''
useEffect(() => {
  // 只要弹窗是“从 rps 接口编辑”的上下文（父级传了 rpsAssetId / rpsFormIds），
  // 左侧列表需要完全依赖 `/api/user/asset/rps/form-image/list` 的返回，
  // 否则会被父级回填的（通常仅 isUse=1）数据覆盖。
  const hasRpsContext = activeRpsAssetId() != null || activeRpsFormIds().length > 0
  if (hasRpsContext) return
  if (lockLocalSceneImagesFromRps.current) return
  if (propsRef.current.imageType !== 'scene') return
  ctx.syncLocalSceneImagesFromSceneIndex(currentSceneIndex.get(), { preservePending: true })
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [scenesFingerprint, currentSceneIndex.value])

// 原 watch([open, csi, cii, currentImg 关键字段], { immediate: true })：选图后同步初始化两种作图模式
const prefillImg = currentImg()
const prefillRefsFingerprint = JSON.stringify(prefillImg?.referenceImages ?? [])
useEffect(() => {
  if (propsRef.current.open) applyCurrentFormImageEditPrefill()
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [
  props.open,
  currentSceneIndex.value,
  currentImageIndex.value,
  prefillImg?.id,
  prefillImg?.rpsImageId,
  prefillImg?.promptText,
  prefillRefsFingerprint
])

// 监听场景切换（原 watch(() => props.sceneIndex)）
useEffect(() => {
  if (props.sceneIndex !== currentSceneIndex.get()) {
    switchScene(props.sceneIndex)
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [props.sceneIndex])

// 监听初始图片索引（当弹窗打开时，如果有指定初始图片索引，则选中该图片）
useEffect(() => {
  const isOpen = props.open
  const imageIndex = props.initialImageIndex
  if (isOpen && typeof imageIndex === 'number' && imageIndex >= 0) {
    // 确保场景已切换完成后再设置图片索引
    setTimeout(() => {
      if (currentSceneImages().length > imageIndex) {
        currentImageIndex.set(imageIndex)
        // 滚动到选中的图片
        setTimeout(() => {
          void switchImage(imageIndex)
        }, 0)
      }
    }, 0)
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [props.open, props.initialImageIndex])

// 关闭弹窗时清空会话态；释放 live follow 锁，便于步骤页外层静默续跟同一 taskId
const prevOpenForCleanupRef = useRef<boolean | null>(null)
useEffect(() => {
  const isOpen = props.open
  if (prevOpenForCleanupRef.current === isOpen) return
  const isFirst = prevOpenForCleanupRef.current === null
  prevOpenForCleanupRef.current = isOpen
  if (isFirst) return
  if (!isOpen) {
    resumeSceneModalFollowGen.current++
    sceneModalTabActivationGen.current++
    tabSwitchSkeleton.clear()
    ctx.resetSceneModalDeferredRestoreState()
    // 先挂起弹窗 registry SSE，避免外层 useTaskStream 与弹窗 orphan 双连
    const editorScopeKey = buildEditorScopeKeyForSceneIndex(currentSceneIndex.get())
    const persisted = editorScopeKey
      ? ctx.resolvePersistedSceneModalSseTask(editorScopeKey)
      : null
    if (persisted?.taskId) {
      suspendTaskSseFollow(persisted.taskId)
      if (hasStep3SseSlot(persisted.taskId)) releaseStep3SseSlot(persisted.taskId)
    }
    for (const tid of ctx.store().step3FormImageTaskFollowTaskIds) {
      const n = Number(tid)
      if (Number.isFinite(n) && n > 0) {
        suspendTaskSseFollow(n)
        if (hasStep3SseSlot(n)) releaseStep3SseSlot(n)
      }
    }
    // 关窗后不再由弹窗占坑；外层 restoreAndTrack 可重新 startTrackTask
    ctx.activeSceneModalFollowScopeKeys.clear()
    drainStep3SseQueue((item) => {
      if (item.owner === 'modal') {
        requeueStep3SseItemToEnd(item)
        return { kind: 'skipped' as const }
      }
      const acq = tryAcquireStep3SseSlot({ taskId: item.taskId, owner: 'outer' })
      if (acq.kind !== 'acquired' && acq.kind !== 'already-active') return acq
      window.dispatchEvent(
        new CustomEvent('create-flow-track-task', { detail: { taskId: item.taskId } })
      )
      return acq
    })
    addedImageIds.set(new Set())
    pendingImage.current = null
    lockLocalSceneImagesFromRps.current = false
  } else {
    // 每次打开给予新的续跟预算，避免上一轮 stop 次数残留
    ctx.resetSceneModalDeferredRestoreState()
    // 先清空本实例画布/工具栏 loading，再按当前 editorScope 恢复，避免串到其它弹窗入口
    ctx.clearUpscaleOverlay()
    isSceneSplitting.set(false)
    sceneSplitTargetKey.set('')
    sceneSplitProgressText.set('正在拆分四宫格…')
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [props.open])

// 弹窗打开与顶部 Tab 切换共用同一激活事务，避免同一 sceneIdx 并发恢复多次
// 原 watch(() => props.open, { immediate: true })
const prevOpenForActivateRef = useRef<boolean | null>(null)
useEffect(() => {
  const isOpen = props.open
  if (prevOpenForActivateRef.current === isOpen) return
  prevOpenForActivateRef.current = isOpen
  if (!isOpen) return
  clearSceneImageModalUserDismissed(ctx.sceneModalSessionScope())
  void ctx.initImageModelOptions()
  const si = propsRef.current.sceneIndex
  if (currentSceneIndex.get() !== si) {
    switchScene(si)
  } else {
    void ctx.activateSceneModalTab(si)
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [props.open])

// 原 watch(() => props.scenes.length)
const prevScenesLengthRef = useRef(props.scenes.length)
useEffect(() => {
  if (prevScenesLengthRef.current === props.scenes.length) return
  prevScenesLengthRef.current = props.scenes.length
  if (!propsRef.current.open) return
  requestAnimationFrame(() => sceneTabBarRef.current?.refresh())
   
}, [props.scenes.length])

// currentSceneIndex 是顶部 Tab 激活副作用的唯一 watcher owner
const prevSceneIndexRef = useRef(currentSceneIndex.value)
useEffect(() => {
  if (prevSceneIndexRef.current === currentSceneIndex.value) return
  prevSceneIndexRef.current = currentSceneIndex.value
  if (!propsRef.current.open) return
  void ctx.activateSceneModalTab(currentSceneIndex.value)
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [currentSceneIndex.value])

// 弹窗打开时：如果父级异步回填 rpsFormIds 导致首帧为空，则在 rpsFormIds 变更后再初始化
const rpsFormIdsFingerprint = props.open ? JSON.stringify(props.rpsFormIds ?? []) : '[]'
const prevRpsFormIdsFingerprintRef = useRef(rpsFormIdsFingerprint)
useEffect(() => {
  if (prevRpsFormIdsFingerprintRef.current === rpsFormIdsFingerprint) return
  prevRpsFormIdsFingerprintRef.current = rpsFormIdsFingerprint
  void (async () => {
    if (!propsRef.current.open) return
    const formIds = Array.isArray(propsRef.current.rpsFormIds) ? propsRef.current.rpsFormIds : []
    if (formIds.length === 0) return
    const key = ctx.buildInitFormImageListKey()
    if (key === lastInitFormImageListKey.current) return
    lastInitFormImageListKey.current = key
    await new Promise((r) => setTimeout(r, 0))
    await ctx.initFormImageListOnOpen()
  })()
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [rpsFormIdsFingerprint])

// 弹窗打开期间：父级 scenes 异步回填或刷新后再次同步，避免只依赖 open 瞬间的空数据
const addedIdsFingerprint = props.open
  ? `${currentSceneIndex.value}|${JSON.stringify(props.scenes[currentSceneIndex.value]?.images ?? [])}`
  : null
useEffect(() => {
  if (!propsRef.current.open) return
  ctx.syncAddedImageIdsFromParentScenes()
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [addedIdsFingerprint])

// 原 watch(open)：打开期间监听全局任务更新事件
useEffect(() => {
  if (typeof window === 'undefined') return
  if (!props.open) return
  const handleGlobalTasksUpdatedForModal = () => {
    if (!propsRef.current.open) return
    void ctx.activateSceneModalTab(currentSceneIndex.get(), { forceImageRefresh: true })
  }
  window.addEventListener(GLOBAL_TASKS_UPDATED_EVENT, handleGlobalTasksUpdatedForModal)
  return () => {
    window.removeEventListener(GLOBAL_TASKS_UPDATED_EVENT, handleGlobalTasksUpdatedForModal)
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [props.open])

useCreateFlowScopeChangedResume(() => {
  // 切作品/切集：清掉旧桶跟随锁，避免同名 character-0 挡住新作品 restore
  clearModalFollowLocksNotMatchingLiveGenScope(
    ctx.activeSceneModalFollowScopeKeys,
    ctx.currentModalLiveGenScopeKey()
  )
  if (!propsRef.current.open) return
  void ctx.activateSceneModalTab(currentSceneIndex.get(), { forceImageRefresh: true })
})

}
