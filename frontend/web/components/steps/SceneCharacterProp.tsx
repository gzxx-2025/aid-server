'use client'

/**
 * 创作流程第三步「场景/角色/道具」素材准备面板（原 aid-pc/components/steps/SceneCharacterProp.vue，12499 行）。
 *
 * 对外 props 契约（原 defineProps / defineEmits，一一对应）：
 * - modelValue: SceneCharacterData（{ scenes, characters, props } 名称数组）
 * - storyScriptContent: string（第二步剧本 HTML，空则「自动提取」按钮提示先写剧本）
 * - isExtracting?: boolean（壳层智能提取全屏 loading）
 * - extractingStage?: 'scene' | 'character' | 'prop'（提取进行到的阶段，Tab 跟随）
 * - extractingStages?: { scene; character; prop }（并行提取勾选的类型；单类型时锁定 Tab）
 * - onModelValueChange(value)（原 emit('update:modelValue')）
 * - onStopExtract()（原 emit('stop-extract')）
 * - onOpenExtractModal(scope)（原 emit('open-extract-modal')：打开 ExtractAgentModal 单列提取）
 *
 * 原调用点：pages/create.vue（创作壳七步页面第三步）。本批次暂不接线，
 * 由后续「创作壳」批次在 app/(home)/create 中挂载并传入以上 props。
 * defineExpose：无。
 */

import { useContext, useEffect, useRef } from 'react'
import { LoadingOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import { useCreationStore } from '~/stores/creation'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useCreateFlowScopeChangedResume } from '~/hooks/useCreateFlowLiveGenResume'
import { createFlowShellContext } from '~/utils/createFlowInjection'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import type { RouteLikeLocation } from '~/types/routeLike'
import { useScpState } from './scene-character-prop/useScpState'
import { useScpDerived } from './scene-character-prop/useScpDerived'
import { useScpRpsOps } from './scene-character-prop/useScpRpsOps'
import { useScpAssetLoad } from './scene-character-prop/useScpAssetLoad'
import { useScpGenStatus } from './scene-character-prop/useScpGenStatus'
import { useScpGenStatusSync } from './scene-character-prop/useScpGenStatusSync'
import { useScpTaskProgress } from './scene-character-prop/useScpTaskProgress'
import { useScpTaskHydrate } from './scene-character-prop/useScpTaskHydrate'
import { useScpSubmitFields } from './scene-character-prop/useScpSubmitFields'
import { useScpSettingCard } from './scene-character-prop/useScpSettingCard'
import { useScpFinalizers } from './scene-character-prop/useScpFinalizers'
import { useScpTaskFollow } from './scene-character-prop/useScpTaskFollow'
import { useScpTaskRestore } from './scene-character-prop/useScpTaskRestore'
import { useScpModalSseSync } from './scene-character-prop/useScpModalSseSync'
import { useScpBootstrap } from './scene-character-prop/useScpBootstrap'
import { useScpGenerateActions } from './scene-character-prop/useScpGenerateActions'
import { useScpSceneCrud } from './scene-character-prop/useScpSceneCrud'
import { useScpCharacterCrud } from './scene-character-prop/useScpCharacterCrud'
import { useScpCharacterImages } from './scene-character-prop/useScpCharacterImages'
import { useScpPropCrud } from './scene-character-prop/useScpPropCrud'
import { useScpPropImages } from './scene-character-prop/useScpPropImages'
import { preloadEditSceneImageModalWhenIdle } from './scene-character-prop/editSceneImageModalLoader'
import {
  FORM_CARD_BATCH_SETTLED_EVENT,
  FORM_IMAGE_TASK_SETTLED_EVENT,
  SCP_ACTIVE_TAB_SESSION_PREFIX,
  type ResolvedSceneCharacterPropProps,
  type SceneCharacterPropProps,
  type ScpCtx
} from './scene-character-prop/types'
import { scpEmptyAssetIconUrl } from './scene-character-prop/scpIcons'
import { ScpTopbarView } from './scene-character-prop/ScpTopbarView'
import { ScpSceneListView } from './scene-character-prop/ScpSceneListView'
import { ScpCharacterListView } from './scene-character-prop/ScpCharacterListView'
import { ScpPropListView } from './scene-character-prop/ScpPropListView'
import { ScpModalsView } from './scene-character-prop/ScpModalsView'
import './scene-character-prop/scene-character-prop.css'
import './scene-character-prop/scene-character-prop-cards.css'
import './scene-character-prop/scene-character-prop-forms.css'

export function SceneCharacterProp(rawProps: SceneCharacterPropProps) {
  /** withDefaults：isExtracting=false、extractingStage='scene' */
  const props: ResolvedSceneCharacterPropProps = {
    ...rawProps,
    isExtracting: rawProps.isExtracting ?? false,
    extractingStage: rawProps.extractingStage ?? 'scene'
  }

  const route = useRouteLike()
  const shell = useContext(createFlowShellContext)

  /** 事件回调 / 异步流程内读最新 props / route / shell */
  const propsRef = useRef(props)
  propsRef.current = props
  const routeRef = useRef<RouteLikeLocation>(route)
  routeRef.current = route
  const shellRef = useRef(shell)
  shellRef.current = shell

  /** 共享运行时上下文：base 状态 + 各子 hook API 延迟绑定（解循环依赖） */
  const ctx = useRef({} as ScpCtx).current
  const { mirroredState, instance } = useScpState()
  const baseInstalledRef = useRef(false)
  if (!baseInstalledRef.current) {
    baseInstalledRef.current = true
    // 实例级可变量只灌一次，后续读写都在 ctx 上（避免每次渲染重置计数器/Map）
    Object.assign(ctx, instance)
    ctx.props = () => propsRef.current
    ctx.route = () => routeRef.current
    ctx.store = () => useCreationStore.getState()
    ctx.createFlowShell = () => shellRef.current
    ctx.patchStore = (partial) => useCreationStore.setState(partial)
    ctx.emitUpdateModelValue = (v) => propsRef.current.onModelValueChange(v)
    ctx.emitStopExtract = () => propsRef.current.onStopExtract()
    ctx.emitOpenExtractModal = (scope) => propsRef.current.onOpenExtractModal(scope)
  }
  // Mirrored 状态每次渲染刷新 .value 快照
  Object.assign(ctx, mirroredState)
  // 各子 hook API（新闭包覆盖旧闭包，跨模块一律经 ctx.* 调用）
  Object.assign(ctx, useScpDerived(ctx))
  Object.assign(ctx, useScpRpsOps(ctx))
  Object.assign(ctx, useScpAssetLoad(ctx))
  Object.assign(ctx, useScpGenStatus(ctx))
  Object.assign(ctx, useScpGenStatusSync(ctx))
  Object.assign(ctx, useScpTaskProgress(ctx))
  Object.assign(ctx, useScpTaskHydrate(ctx))
  Object.assign(ctx, useScpSubmitFields(ctx))
  Object.assign(ctx, useScpSettingCard(ctx))
  Object.assign(ctx, useScpFinalizers(ctx))
  Object.assign(ctx, useScpTaskFollow(ctx))
  Object.assign(ctx, useScpTaskRestore(ctx))
  Object.assign(ctx, useScpModalSseSync(ctx))
  Object.assign(ctx, useScpBootstrap(ctx))
  Object.assign(ctx, useScpGenerateActions(ctx))
  Object.assign(ctx, useScpSceneCrud(ctx))
  Object.assign(ctx, useScpCharacterCrud(ctx))
  Object.assign(ctx, useScpCharacterImages(ctx))
  Object.assign(ctx, useScpPropCrud(ctx))
  Object.assign(ctx, useScpPropImages(ctx))

  // ===== 渲染期订阅的 store 切片（原 Pinia 响应式依赖） =====
  const scriptChangeLightBannerVisible = useCreationStore((s) => s.scriptChangeLightBannerVisible)
  const pendingExtractFormAssets = useCreationStore((s) => s.pendingExtractFormAssets)
  const extractingTaskProgress = useCreationStore((s) => s.extractingTaskProgress)
  const isExtractingAssetsStore = useCreationStore((s) => s.isExtractingAssets)
  const isGeneratingStep3VisualStore = useCreationStore((s) => s.isGeneratingStep3Visual)
  const storeSceneGenStatus = useCreationStore((s) => s.sceneGenerationStatus)
  const storeCharacterFormGenStatus = useCreationStore((s) => s.characterFormGenerationStatus)
  const storePropFormGenStatus = useCreationStore((s) => s.propFormGenerationStatus)
  const extractImageModelCodes = useCreationStore((s) => s.extractImageModelCodes)
  const step3GenVisualByScope = useCreationStore((s) => s.step3GenVisualByScope)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  void pendingExtractFormAssets
  void extractingTaskProgress
  void isExtractingAssetsStore
  void isGeneratingStep3VisualStore
  void extractImageModelCodes
  void step3GenVisualByScope

  // ===== watch 平移（成对清理；非 immediate 的用 mounted-skip） =====
  const mountedRef = useRef(false)

  // 提取进行中：多类型提取时 Tab 与 SSE 阶段对齐；单类型提取保持当前 Tab（原 watch immediate）
  useEffect(() => {
    const extracting = props.isExtracting
    const stage = props.extractingStage
    if (!extracting || !stage) return
    if (!ctx.activeTabBeforeExtractStart) {
      ctx.activeTabBeforeExtractStart = ctx.activeTab.get()
    }
    if (ctx.singleExtractTabLock) {
      ctx.activeTab.set(ctx.singleExtractTabLock)
      return
    }
    ctx.activeTab.set(stage)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.isExtracting, props.extractingStage])

  useEffect(() => {
    if (!mountedRef.current) return
    const extracting = props.isExtracting
    const stages = props.extractingStages
    if (!extracting) return
    const lock = ctx.resolveSingleExtractTabLock(stages)
    if (lock) {
      ctx.singleExtractTabLock = lock
      ctx.activeTab.set(lock)
    } else {
      ctx.singleExtractTabLock = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.isExtracting, props.extractingStages])

  // 原 watch(props.modelValue, immediate, deep)：同步本地列表并从 store 恢复手动索引
  const modelValueFingerprint = JSON.stringify(props.modelValue ?? null)
  useEffect(() => {
    const val = propsRef.current.modelValue
    ctx.localValue.set({
      characters: [...(val?.characters || [])],
      scenes: [...(val?.scenes || [])],
      props: [...(val?.props || [])]
    })
    const newScenesLength = ctx.localValue.get().scenes.length
    const newCharactersLength = ctx.localValue.get().characters.length
    const newPropsLength = ctx.localValue.get().props.length
    // 从 store 恢复手动添加的索引（解决切换流程后返回时“添加的”变成“自动生成”的 bug）
    if (newScenesLength > 0) {
      const validManualScenes = new Set<number>(
        (ctx.store().manualScenes || []).filter((i) => i < newScenesLength)
      )
      ctx.manualScenes.set(validManualScenes)
    } else {
      ctx.manualScenes.set(new Set())
      ctx.sceneAssetIds.set({})
      ctx.sceneFormIdsByIndex.set({})
    }
    if (newCharactersLength > 0) {
      const validManualCharacters = new Set<number>(
        (ctx.store().manualCharacters || []).filter((i) => i < newCharactersLength)
      )
      ctx.manualCharacters.set(validManualCharacters)
    } else {
      ctx.manualCharacters.set(new Set())
      ctx.characterAssetIds.set({})
      ctx.characterFormIdsByIndex.set({})
    }
    if (newPropsLength > 0) {
      const validManualProps = new Set<number>(
        (ctx.store().manualProps || []).filter((i) => i < newPropsLength)
      )
      ctx.manualProps.set(validManualProps)
    } else {
      ctx.manualProps.set(new Set())
      ctx.propAssetIds.set({})
      ctx.propFormIdsByIndex.set({})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelValueFingerprint])

  // 项目/剧集切换时清空并拉当前 Tab；不含 isExtracting，避免提取结束误清空（原 projectContextDeps watch）
  useEffect(() => {
    if (!mountedRef.current) return
    ctx.scheduleProjectAssetBootstrap()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    currentProjectId,
    currentEpisodeId,
    currentProjectType,
    route.query.projectId,
    route.query.id,
    route.query.workId,
    route.query.episodeId
  ])

  // 提取结束后仅刷新当前 Tab 列表，与本地提取结果不冲突
  const prevIsExtractingRef = useRef<boolean | undefined>(undefined)
  useEffect(() => {
    const extracting = props.isExtracting
    const wasExtracting = prevIsExtractingRef.current
    prevIsExtractingRef.current = extracting
    if (extracting === true && wasExtracting !== true && !ctx.activeTabBeforeExtractStart) {
      ctx.activeTabBeforeExtractStart = ctx.activeTab.get()
    }
    if (wasExtracting === true && extracting === false) {
      const tabForRefresh =
        ctx.singleExtractTabLock ?? ctx.activeTabBeforeExtractStart ?? ctx.activeTab.get()
      ctx.singleExtractTabLock = null
      ctx.activeTabBeforeExtractStart = null
      if (tabForRefresh !== ctx.activeTab.get()) {
        ctx.suppressActiveTabAssetLoadOnce = true
        ctx.activeTab.set(tabForRefresh)
      }
      ctx.ensureFormsPlaceholdersForActiveTab()
      void ctx.loadPersonalAssetsForTab(tabForRefresh)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.isExtracting])

  // 原 watch(activeTab)：切 Tab 加载列表 + SSE 跟随同步 + 持久化停留 Tab；并收起批量操作下拉
  const activeTabValue = ctx.activeTab.value
  useEffect(() => {
    if (!mountedRef.current) return
    const v = activeTabValue
    void (async () => {
      /** 先给其它 Tab 的 SSE 打上「切 Tab 主动关闭」标记再加载列表，避免断开被误判为任务失败 */
      if (ctx.step3AssetBootstrapReady.get() && !ctx.props().isExtracting) {
        ctx.pauseStep3SseForInactiveTabs(v)
      }
      ctx.ensureFormsPlaceholdersForActiveTab()
      if (ctx.suppressActiveTabAssetLoadOnce) {
        ctx.suppressActiveTabAssetLoadOnce = false
      } else {
        await ctx.loadPersonalAssetsForTab(v)
      }
      if (ctx.step3AssetBootstrapReady.get()) {
        if (v === 'character') {
          await ctx.reconcileSettingCardGeneratingUiForOngoingTasks()
        }
        await ctx.syncStep3SseFollowForActiveTab(v)
      }
      const pid = ctx.store().currentProjectId
      if (pid != null && Number.isFinite(Number(pid)) && typeof window !== 'undefined') {
        try {
          window.sessionStorage.setItem(`${SCP_ACTIVE_TAB_SESSION_PREFIX}${Number(pid)}`, v)
        } catch {
          /* ignore */
        }
      }
    })()
    ctx.batchOpsDropdownOpen.set(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTabValue])

  // 原 watch(Pinia 三张 generating map, deep)：弹窗清掉后同步本地列表 ref
  useEffect(() => {
    if (!mountedRef.current) return
    ctx.syncLocalStep3GeneratingFromPinia()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeSceneGenStatus, storeCharacterFormGenStatus, storePropFormGenStatus])

  // 编辑图弹窗关闭后：外层静默续跟进行中 task（不自动重开弹窗）
  const editModalOpenFlags = [
    ctx.showEditSceneImageModal.value,
    ctx.showEditCharacterImageModal.value,
    ctx.showEditCharacterFormImageModal.value,
    ctx.showEditPropImageModal.value,
    ctx.showEditPropFormImageModal.value
  ] as const
  const prevEditModalOpenFlagsRef = useRef<readonly boolean[] | null>(null)
  useEffect(() => {
    const prev = prevEditModalOpenFlagsRef.current
    prevEditModalOpenFlagsRef.current = editModalOpenFlags
    if (!prev) return
    for (let i = 0; i < editModalOpenFlags.length; i++) {
      const open = editModalOpenFlags[i]
      const wasOpen = prev[i]
      if (!wasOpen || open) continue
      // 等弹窗 open=false 的 suspend / 清锁跑完，再外层续跟，降低双连窗口（原 nextTick）
      setTimeout(() => {
        void ctx.restoreAndTrackOngoingTasks()
      }, 0)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, editModalOpenFlags as unknown as unknown[])

  // 原 watch([characters.length, props.length, characterImages, propImages], deep, immediate)
  useEffect(() => {
    ctx.ensureCharacterPropFormsAndMigrateMainImages()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    ctx.localValue.value.characters.length,
    ctx.localValue.value.props.length,
    ctx.characterImages.value,
    ctx.propImages.value
  ])

  // 原 watch(7 个资产 ref, deep)：第三步图片/形态写入 Pinia，第四/五步「本作品资产」弹窗读取
  useEffect(() => {
    if (!mountedRef.current) return
    if (ctx.syncingStep3ToStore) return
    ctx.syncStep3AssetsToCreationStore()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    ctx.sceneImages.value,
    ctx.characterImages.value,
    ctx.propImages.value,
    ctx.characterForms.value,
    ctx.propForms.value,
    ctx.characterFormImages.value,
    ctx.propFormImages.value
  ])

  // 原 onMounted / onBeforeUnmount
  useEffect(() => {
    mountedRef.current = true
    ctx.assetPageMounted = true
    ctx.scheduleProjectAssetBootstrap()
    ctx.cancelEditSceneImageModalPreload = preloadEditSceneImageModalWhenIdle()
    /** 任务列表由挂载后的 bootstrap 统一拉取，避免 setup 阶段与页面过渡争抢渲染。 */
    const onTrack = (e: Event) => ctx.handleTrackTaskEvent(e)
    const onStop = (e: Event) => ctx.handleStopTaskEvent(e)
    const onResume = (e: Event) => ctx.handleResumeTaskEvent(e)
    const onFormCardSettled = (e: Event) => ctx.handleFormCardBatchSettledEvent(e)
    const onFormImageSettled = (e: Event) => ctx.handleFormImageTaskSettledFromModal(e)
    window.addEventListener('create-flow-track-task', onTrack as EventListener)
    window.addEventListener('create-flow-stop-task', onStop as EventListener)
    window.addEventListener('create-flow-resume-task', onResume as EventListener)
    window.addEventListener(FORM_CARD_BATCH_SETTLED_EVENT, onFormCardSettled as EventListener)
    window.addEventListener(FORM_IMAGE_TASK_SETTLED_EVENT, onFormImageSettled as EventListener)
    ctx.deliverPendingCreateFlowTaskCommands()

    return () => {
      ctx.assetPageMounted = false
      ctx.cancelEditSceneImageModalPreload?.()
      ctx.cancelEditSceneImageModalPreload = null
      if (ctx.projectAssetBootstrapDebounceTimer) {
        clearTimeout(ctx.projectAssetBootstrapDebounceTimer)
        ctx.projectAssetBootstrapDebounceTimer = null
      }
      ctx.projectAssetBootstrapEpoch++
      /** 离开第三步（如切到内嵌作品库）：作废本实例 SSE 回调，避免 finally 误清仍在进行的提取 loading */
      ctx.stopOngoingTaskStreamForRouteContextChange()
      ctx.store().syncExtractUiToCurrentScope()
      ctx.stopVoicePreview()
      window.removeEventListener('create-flow-track-task', onTrack as EventListener)
      window.removeEventListener('create-flow-stop-task', onStop as EventListener)
      window.removeEventListener('create-flow-resume-task', onResume as EventListener)
      window.removeEventListener(FORM_CARD_BATCH_SETTLED_EVENT, onFormCardSettled as EventListener)
      window.removeEventListener(FORM_IMAGE_TASK_SETTLED_EVENT, onFormImageSettled as EventListener)
      /** 切换流程步骤时不重置第三步生成 loading、不中断形态图 SSE，由 Pinia + 壳层流程条持续展示 */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useCreateFlowScopeChangedResume(() => {
    if (routePathToCreationStep(routeRef.current.path) !== 'scene-character') return undefined
    /** bootstrap 未完成时由 runProjectAssetBootstrap 统一恢复，避免与壳层 scope 事件重复打 list */
    if (!ctx.step3AssetBootstrapReady.get()) return undefined
    return ctx.restoreAndTrackOngoingTasks()
  })

  // ===== 渲染 =====
  const isExtracting = ctx.isExtracting()
  const localValue = ctx.localValue.value
  const activeTab = ctx.activeTab.value
  const showMask = ctx.showAssetBootstrapMask() || ctx.showActiveTabAssetLoading()

  return (
    <div className="scp-root create-step-scp">
      {/* 顶部切换：场景 / 角色 / 道具 */}
      <ScpTopbarView ctx={ctx} />

      {!isExtracting && ctx.extractingProgressText() ? (
        <div className="scp-task-restore-banner" role="status" aria-live="polite">
          <span className="scp-task-restore-banner__text">{ctx.extractingProgressText()}</span>
        </div>
      ) : null}

      {!isExtracting && scriptChangeLightBannerVisible ? (
        <div className="scp-script-change-banner" role="status" aria-live="polite">
          <span className="scp-script-change-banner__text">
            剧本有实质性更新，可继续提取或重新提取素材。
          </span>
          <div className="scp-script-change-banner__actions">
            <Button type="link" size="small" onClick={ctx.onScriptChangeGoExtract}>
              去提取
            </Button>
            <Button type="text" size="small" onClick={ctx.onScriptChangeDismissBanner}>
              关闭
            </Button>
          </div>
        </div>
      ) : null}

      {/* 内容 */}
      <div ref={ctx.scpContentRef} className="scp-content">
        {/* 提取中状态 */}
        {isExtracting ? (
          <div className="extracting-view">
            <div className="extracting-title" role="status" aria-live="polite">
              {ctx.extractingLiveTitle()}
            </div>
            <div className="extracting-actions">
              <Button className="scp-stop-extract-btn" danger onClick={ctx.emitStopExtract}>
                停止生成
              </Button>
            </div>
            <div className="extracting-placeholder">
              <img
                src="/media/icon/generating-center.webp"
                alt=""
                className="extracting-placeholder-image"
              />
            </div>
          </div>
        ) : (
          /* 场景 / 角色 / 道具：有数据展示对应列表，无数据共用空状态 */
          <div
            className={`asset-section${showMask ? ' asset-section--bootstrap-pending' : ''}`}
          >
            {showMask ? (
              <div
                className="scp-asset-bootstrap-mask"
                role="status"
                aria-live="polite"
                aria-busy="true"
              >
                <LoadingOutlined spin className="scp-asset-bootstrap-mask__icon" />
                <p className="scp-asset-bootstrap-mask__text">{ctx.assetLoadingMaskText()}</p>
              </div>
            ) : activeTab === 'scene' && localValue.scenes.length > 0 ? (
              <ScpSceneListView ctx={ctx} />
            ) : activeTab === 'character' && localValue.characters.length > 0 ? (
              <ScpCharacterListView ctx={ctx} />
            ) : activeTab === 'prop' && localValue.props.length > 0 ? (
              <ScpPropListView ctx={ctx} />
            ) : (
              /* 场景 / 角色 / 道具 无数据时共用 */
              <div className="empty-asset-view">
                <div
                  className={`scp-asset-empty scp-asset-empty--${activeTab}`}
                  role="status"
                  aria-live="polite"
                >
                  <div className="scp-asset-empty__grid" aria-hidden="true" />
                  <div className="scp-asset-empty__icon-wrap">
                    <img
                      src={scpEmptyAssetIconUrl}
                      alt=""
                      className="empty-image-icon empty-image-icon--xl"
                    />
                  </div>
                  <div className="tips">{ctx.emptyExtractTips()}</div>
                  {!isExtracting ? (
                    <div className="scp-actions">
                      <Button
                        type="primary"
                        className="btn-primary"
                        onClick={(e) => ctx.onClickAutoExtract(activeTab, e)}
                      >
                        <div className="text-gradient">{ctx.autoExtractEmptyButtonLabel()}</div>
                      </Button>
                    </div>
                  ) : null}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      <ScpModalsView ctx={ctx} />
    </div>
  )
}

export default SceneCharacterProp
