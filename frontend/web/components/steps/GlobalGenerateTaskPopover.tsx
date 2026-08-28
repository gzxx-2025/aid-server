'use client'

import { useEffect, useRef, useState } from 'react'
import { Popover, Tooltip } from 'antd'
import type { UserTaskRow } from '~/types/business-api'
import type { BillingQuoteRequest } from '~/types/business-api'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import {
  USER_TASK_LIST_RESTORE_PAGE_SIZE,
  userTaskListPage
} from '~/utils/businessApi'
import {
  FLOW_USER_TASK_LIST_READY_EVENT,
  fetchFlowUserTaskList,
  getCachedFlowUserTaskList,
  scheduleFlowUserTaskListRefresh,
  type FlowUserTaskListReadyDetail
} from '~/utils/userTaskListFlowOnce'
import {
  isTaskOngoingStatus,
  markUserTaskLocallyTerminal
} from '~/composables/useTaskOngoing'
import {
  isCancelledResumableTask,
  isPartialFailedResumableTaskType,
  isStoryboardVideoGenerateTaskType,
  isUserTaskStatusCancelled,
  normUserTaskType
} from '~/utils/taskPartialFailed'
import { isStoryboardScriptFlowStepGenerating } from '~/utils/storyboardFlowStepLoading'
import { waitInfiniteScrollAppendDelay } from '~/utils/infiniteScrollDelay'
import { useCreationStore } from '~/stores/creation'
import { useRouteLike } from '~/composables/useRouteLike'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import iconStopRaw from '~/assets/img/icon/icon-stop.svg'
import iconStarRaw from '~/assets/img/icon/icon-star.svg'
import { assetUrl } from '~/utils/assetUrl'
import './GlobalGenerateTaskPopover.css'

const iconStop = assetUrl(iconStopRaw)
const iconStar = assetUrl(iconStarRaw)

const GLOBAL_TASKS_EVENT = 'create-flow-global-tasks-updated'

export interface GlobalGenerateTaskPopoverProps {
  projectId: number | null
  onStop: (task: UserTaskRow) => void
  onRestart: (task: UserTaskRow) => void
  resolveRestartBillingRequest: (task: UserTaskRow) => Promise<BillingQuoteRequest | null>
  onResume: (task: UserTaskRow) => void
  resolveResumeBillingRequest: (task: UserTaskRow) => BillingQuoteRequest | null
}

function isTrackedTaskType(ty: unknown): boolean {
  const n = normUserTaskType(ty)
  return (
    n === 'asset_extract' ||
    n === 'form_generate' ||
    n === 'form_generate_batch' ||
    n === 'form_image' ||
    n === 'form_image_batch' ||
    n === 'image_upscale' ||
    n === 'storyboard_script_batch' ||
    n === 'storyboard_image_prompt_batch' ||
    n === 'storyboard_video_prompt_batch' ||
    n === 'storyboard_video_generate' ||
    n === 'storyboard_audio_generate'
  )
}

function taskTypeLabel(type?: string | null) {
  const n = normUserTaskType(type)
  if (n === 'asset_extract') return '智能提取'
  if (n === 'form_generate') return '形态生成'
  if (n === 'form_generate_batch') return '形态生成（批量）'
  if (n === 'form_image') return '形态图生成'
  if (n === 'form_image_batch') return '形态图生成（批量）'
  if (n === 'image_upscale') return '图片高清'
  if (n === 'storyboard_script_batch') return '分镜脚本生成'
  if (n === 'storyboard_image_prompt_batch') return '分镜图提示词生成'
  if (n === 'storyboard_video_prompt_batch') return '分镜视频提示词生成'
  if (n === 'storyboard_video_generate') return '分镜视频出片'
  if (n === 'storyboard_audio_generate') return '批量分镜配音'
  return type || '任务'
}

function taskStatusUpper(task: UserTaskRow): string {
  return String(task?.status ?? '').toUpperCase()
}

function mergeTaskRows(existing: UserTaskRow[], incoming: UserTaskRow[]): UserTaskRow[] {
  const seen = new Set<number>()
  const merged: UserTaskRow[] = []
  for (const row of [...existing, ...incoming]) {
    const id = Number(row.id)
    if (!Number.isFinite(id) || id <= 0 || seen.has(id)) continue
    seen.add(id)
    merged.push(row)
  }
  return merged.sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
}

/**
 * 剧集隔离：任务列表接口按 projectId 返回同作品全部集的任务。
 * 明确归属其它集（episodeId > 0 且 ≠ 当前集）的任务不进本集面板/角标；
 * episodeId 缺失或 ≤ 0（项目级/历史任务）保留。
 */
function matchesCurrentEpisode(task: UserTaskRow): boolean {
  const rowEp = Number(task?.episodeId)
  if (!Number.isFinite(rowEp) || rowEp <= 0) return true
  const currentEp = Number(useCreationStore.getState().currentEpisodeId)
  if (!Number.isFinite(currentEp) || currentEp <= 0) return true
  return rowEp === currentEp
}

export function GlobalGenerateTaskPopover({
  projectId,
  onStop,
  onRestart,
  resolveRestartBillingRequest,
  onResume,
  resolveResumeBillingRequest
}: GlobalGenerateTaskPopoverProps) {
  const route = useRouteLike()
  const taskIdsWithLocalFollowPaused = useCreationStore((s) => s.taskIdsWithLocalFollowPaused)
  const isGeneratingStep3Visual = useCreationStore((s) => s.isGeneratingStep3Visual)
  const isExtractingAssets = useCreationStore((s) => s.isExtractingAssets)
  const isGeneratingStoryboardVideo = useCreationStore((s) => s.isGeneratingStoryboardVideo)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)

  /** 与第三步卡片/流程条一致：Pinia 内仍有 generating 卡片时展示角标 */
  const step3HasPersistedGenerating = useCreationStore((s) => {
    const sc = s.sceneGenerationStatus
    const ch = s.characterFormGenerationStatus
    const pr = s.propFormGenerationStatus
    return (
      Object.values(sc ?? {}).some((x) => x === 'generating') ||
      Object.values(ch ?? {}).some((x) => x === 'generating') ||
      Object.values(pr ?? {}).some((x) => x === 'generating')
    )
  })

  const isStoryboardScriptStepGenerating = useCreationStore((s) =>
    isStoryboardScriptFlowStepGenerating(s, route)
  )

  const [panelOpen, setPanelOpen] = useState(false)
  const panelScrollRef = useRef<HTMLDivElement | null>(null)
  const [loading, setLoadingState] = useState(false)
  const loadingRef = useRef(false)
  const [loadingMore, setLoadingMoreState] = useState(false)
  const loadingMoreRef = useRef(false)
  const tasksPageNumRef = useRef(0)
  const [tasksHasMore, setTasksHasMoreState] = useState(true)
  const tasksHasMoreRef = useRef(true)
  const [loadedTaskRows, setLoadedTaskRowsState] = useState<UserTaskRow[]>([])
  const loadedTaskRowsRef = useRef<UserTaskRow[]>([])
  const [ongoingTaskList, setOngoingTaskList] = useState<UserTaskRow[]>([])
  const [partialTaskList, setPartialTaskList] = useState<UserTaskRow[]>([])
  const [cancelledTaskList, setCancelledTaskList] = useState<UserTaskRow[]>([])
  const [failedTaskList, setFailedTaskList] = useState<UserTaskRow[]>([])

  const suppressScrollPaginationRef = useRef(false)
  const projectIdRef = useRef(projectId)
  projectIdRef.current = projectId

  function setLoading(v: boolean) {
    loadingRef.current = v
    setLoadingState(v)
  }

  function setLoadingMore(v: boolean) {
    loadingMoreRef.current = v
    setLoadingMoreState(v)
  }

  function setTasksHasMore(v: boolean) {
    tasksHasMoreRef.current = v
    setTasksHasMoreState(v)
  }

  function setLoadedTaskRows(rows: UserTaskRow[]) {
    loadedTaskRowsRef.current = rows
    setLoadedTaskRowsState(rows)
  }

  const hasAnyPanelTask =
    ongoingTaskList.length > 0 ||
    partialTaskList.length > 0 ||
    cancelledTaskList.length > 0 ||
    failedTaskList.length > 0 ||
    isGeneratingStep3Visual ||
    isExtractingAssets ||
    step3HasPersistedGenerating ||
    isStoryboardScriptStepGenerating ||
    isGeneratingStoryboardVideo

  const badgeNumber = (() => {
    if (ongoingTaskList.length > 0) return ongoingTaskList.length
    if (partialTaskList.length > 0) return partialTaskList.length
    if (cancelledTaskList.length > 0) return cancelledTaskList.length
    if (failedTaskList.length > 0) return failedTaskList.length
    if (
      isGeneratingStep3Visual ||
      isExtractingAssets ||
      step3HasPersistedGenerating ||
      isStoryboardScriptStepGenerating ||
      isGeneratingStoryboardVideo
    ) {
      return 1
    }
    return 0
  })()

  const badgeStyle: 'ongoing' | 'partial-only' | 'cancelled-only' | 'failed-only' = (() => {
    if (ongoingTaskList.length > 0) return 'ongoing'
    if (partialTaskList.length > 0) return 'partial-only'
    if (cancelledTaskList.length > 0) return 'cancelled-only'
    return 'failed-only'
  })()

  const circleAriaLabel = (() => {
    if (ongoingTaskList.length > 0) return `进行中任务 ${ongoingTaskList.length}`
    if (partialTaskList.length > 0) return `部分成功任务 ${partialTaskList.length}`
    if (cancelledTaskList.length > 0) return `已取消任务 ${cancelledTaskList.length}`
    if (failedTaskList.length > 0) return `失败任务 ${failedTaskList.length}`
    return '任务'
  })()

  const circleTitle = (() => {
    if (ongoingTaskList.length > 0) return '进行中任务'
    if (partialTaskList.length > 0) return '部分成功任务（可续生）'
    if (cancelledTaskList.length > 0) return '已取消任务（可重新生成）'
    if (failedTaskList.length > 0) return '失败任务（点击查看）'
    return '任务'
  })()

  function isLocalFollowPaused(task: UserTaskRow): boolean {
    const id = Number(task.id)
    return Number.isFinite(id) && id > 0 && taskIdsWithLocalFollowPaused.includes(id)
  }

  /** 失败任务，或用户已点「停止」但列表仍显示进行中的任务：展示「继续跟进」 */
  function showTaskRestart(task: UserTaskRow): boolean {
    return taskStatusUpper(task) === 'FAILED' || isLocalFollowPaused(task)
  }

  function showTaskStop(task: UserTaskRow): boolean {
    if (taskStatusUpper(task) === 'FAILED') return false
    if (isLocalFollowPaused(task)) return false
    return true
  }

  function showCancelledRegenerate(task: UserTaskRow): boolean {
    return isCancelledResumableTask(task)
  }

  function restartButtonTitle(task: UserTaskRow): string {
    return isLocalFollowPaused(task) ? '继续跟进进度' : '重新开始生成'
  }

  function clearAllTaskLists() {
    setOngoingTaskList([])
    setPartialTaskList([])
    setCancelledTaskList([])
    setFailedTaskList([])
  }

  function applyCategorizedTasks(rows: UserTaskRow[]) {
    const tracked = rows.filter(
      (t) => t && isTrackedTaskType(t.taskType) && matchesCurrentEpisode(t)
    )
    const ongoing: UserTaskRow[] = []
    const partialRows: UserTaskRow[] = []
    const cancelledRows: UserTaskRow[] = []
    const failedRows: UserTaskRow[] = []
    for (const t of tracked) {
      const s = taskStatusUpper(t)
      if (isUserTaskStatusCancelled(s)) {
        if (isTrackedTaskType(t.taskType)) {
          cancelledRows.push(t)
        }
        continue
      }
      if (
        isPartialFailedResumableTaskType(t.taskType) &&
        (s === 'PARTIAL_FAILED' ||
          (s === 'FAILED' && isStoryboardVideoGenerateTaskType(t.taskType)))
      ) {
        partialRows.push(t)
      } else if (s === 'FAILED') {
        failedRows.push(t)
      } else if (isTaskOngoingStatus(s)) {
        ongoing.push(t)
      }
    }
    setOngoingTaskList(ongoing)
    setPartialTaskList(partialRows)
    setCancelledTaskList(cancelledRows)
    setFailedTaskList(failedRows)
    const listed = new Set(
      [...ongoing, ...partialRows, ...cancelledRows, ...failedRows]
        .map((t) => Number(t.id))
        .filter((n) => Number.isFinite(n) && n > 0)
    )
    useCreationStore.getState().prunePausedTaskFollowKeepOnlyListed(listed)
  }

  function applyFlowTaskListRows(rows: UserTaskRow[]) {
    setLoadedTaskRows(rows)
    tasksPageNumRef.current = 1
    setTasksHasMore(rows.length >= USER_TASK_LIST_RESTORE_PAGE_SIZE)
    applyCategorizedTasks(rows)
  }

  function applyFlowSessionTaskListIfCached(pid: number | null) {
    if (!pid) return
    const cached = getCachedFlowUserTaskList(pid)
    if (!cached?.length) return
    /** 缓存可能滞后：应用本地终态标记，不打 N 次 detail */
    applyFlowTaskListRows(cached)
  }

  async function loadTasksPage(
    reset = false,
    options?: { allowFillScroll?: boolean; forceNetwork?: boolean }
  ) {
    if (!projectIdRef.current) {
      setLoadedTaskRows([])
      clearAllTaskLists()
      setTasksHasMore(false)
      return
    }
    if (!reset && (!tasksHasMoreRef.current || loadingMoreRef.current || loadingRef.current)) return

    const startedAt = Date.now()
    const blockScrollLoad = reset && !options?.allowFillScroll
    if (blockScrollLoad) suppressScrollPaginationRef.current = true

    if (reset) {
      setLoading(true)
      tasksPageNumRef.current = 0
      setTasksHasMore(true)
      setLoadedTaskRows([])
      if (panelScrollRef.current) {
        panelScrollRef.current.scrollTop = 0
      }
    } else {
      setLoadingMore(true)
    }

    const nextPage = reset ? 1 : tasksPageNumRef.current + 1
    try {
      let pageRows: UserTaskRow[]
      let pageHasMore: boolean
      if (reset) {
        pageRows = await fetchFlowUserTaskList(projectIdRef.current, {
          intent: options?.forceNetwork === true ? 'mutate' : 'read'
        })
        pageHasMore = pageRows.length >= USER_TASK_LIST_RESTORE_PAGE_SIZE
      } else {
        const page = await userTaskListPage({
          projectId: projectIdRef.current,
          pageNum: nextPage,
          pageSize: USER_TASK_LIST_RESTORE_PAGE_SIZE
        })
        await waitInfiniteScrollAppendDelay(startedAt)
        pageRows = page.rows
        pageHasMore = page.hasMore
      }
      setLoadedTaskRows(reset ? pageRows : mergeTaskRows(loadedTaskRowsRef.current, pageRows))
      tasksPageNumRef.current = nextPage
      setTasksHasMore(pageHasMore)
      applyCategorizedTasks(loadedTaskRowsRef.current)
    } catch {
      if (reset) {
        setLoadedTaskRows([])
        clearAllTaskLists()
      }
    } finally {
      setLoading(false)
      setLoadingMore(false)
      if (blockScrollLoad) {
        await new Promise<void>((resolve) => setTimeout(resolve, 0))
        suppressScrollPaginationRef.current = false
      }
    }
  }

  function onPanelScroll() {
    if (suppressScrollPaginationRef.current) return
    const el = panelScrollRef.current
    if (!el || loadingRef.current || loadingMoreRef.current || !tasksHasMoreRef.current) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    if (distance <= 120) {
      void loadTasksPage(false)
    }
  }

  function handleStop(task: UserTaskRow) {
    onStop(task)
  }

  function handleRestart(task: UserTaskRow) {
    onRestart(task)
  }

  function handleResume(task: UserTaskRow) {
    onResume(task)
  }

  useEffect(() => {
    if (!panelOpen) return
    void (async () => {
      await loadTasksPage(true, { allowFillScroll: true, forceNetwork: true })
      await new Promise<void>((resolve) => setTimeout(resolve, 0))
      if (panelScrollRef.current && tasksHasMoreRef.current) onPanelScroll()
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panelOpen])

  const prevProjectIdRef = useRef<number | null | undefined>(undefined)
  useEffect(() => {
    const prevPid = prevProjectIdRef.current
    prevProjectIdRef.current = projectId
    if (!projectId) {
      clearAllTaskLists()
      setLoadedTaskRows([])
      return
    }
    if (projectId !== prevPid) {
      clearAllTaskLists()
      setLoadedTaskRows([])
    }
    void applyFlowSessionTaskListIfCached(projectId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  /** 剧集隔离：切集后按新集重算面板分类与角标（列表数据是同 project 全集的） */
  const prevEpisodeIdRef = useRef(currentEpisodeId)
  useEffect(() => {
    const prevEp = prevEpisodeIdRef.current
    if (currentEpisodeId === prevEp) return
    prevEpisodeIdRef.current = currentEpisodeId
    if (loadedTaskRowsRef.current.length) {
      applyCategorizedTasks(loadedTaskRowsRef.current)
    } else {
      void applyFlowSessionTaskListIfCached(projectIdRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentEpisodeId])

  useEffect(() => {
    if (typeof window === 'undefined') return

    function onGlobalTasksUpdated(event: Event) {
      if (!projectIdRef.current) return
      const tid = Number((event as CustomEvent<{ taskId?: number }>).detail?.taskId)
      if (Number.isFinite(tid) && tid > 0) markUserTaskLocallyTerminal(tid)
      /** SSE 终态等：合并防抖后最多刷新一次 list，由 ready 事件更新角标 */
      scheduleFlowUserTaskListRefresh(projectIdRef.current, { force: true })
    }

    function onFlowUserTaskListReady(event: Event) {
      const detail = (event as CustomEvent<FlowUserTaskListReadyDetail>).detail
      if (!detail || detail.projectId !== projectIdRef.current) return
      applyFlowTaskListRows(detail.rows)
    }

    window.addEventListener(GLOBAL_TASKS_EVENT, onGlobalTasksUpdated)
    window.addEventListener(FLOW_USER_TASK_LIST_READY_EVENT, onFlowUserTaskListReady as EventListener)
    void applyFlowSessionTaskListIfCached(projectIdRef.current)
    return () => {
      window.removeEventListener(GLOBAL_TASKS_EVENT, onGlobalTasksUpdated)
      window.removeEventListener(
        FLOW_USER_TASK_LIST_READY_EVENT,
        onFlowUserTaskListReady as EventListener
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!hasAnyPanelTask) return null

  const panelContent = (
    <div ref={panelScrollRef} className="global-task-panel" onScroll={onPanelScroll}>
      <div className="global-task-panel__head">
        <div className="global-task-panel__title">任务中心</div>
      </div>

      {loading && !loadedTaskRows.length ? (
        <div className="global-task-panel__loading">加载中…</div>
      ) : (
        <>
          {ongoingTaskList.length > 0 ? (
            <>
              <div className="global-task-panel__subtitle">进行中</div>
              <div className="global-task-panel__list">
                {ongoingTaskList.map((task) => (
                  <div key={`o-${task.id}`} className="global-task-panel__item">
                    <div className="global-task-panel__item-main">
                      <div className="global-task-panel__name">{taskTypeLabel(task.taskType)}</div>
                      <div className="global-task-panel__model">模型：{task.modelCode || '-'}</div>
                    </div>
                    <div className="global-task-panel__ops">
                      {showTaskStop(task) ? (
                        <button
                          type="button"
                          className="global-task-panel__icon-btn"
                          title="停止生成"
                          onClick={() => handleStop(task)}
                        >
                          <img src={iconStop} alt="停止生成" />
                        </button>
                      ) : null}
                      {showTaskRestart(task) ? (
                        <button
                          type="button"
                          className="global-task-panel__icon-btn global-task-panel__icon-btn--restart"
                          title={restartButtonTitle(task)}
                          onClick={() => handleRestart(task)}
                        >
                          <img src={iconStar} alt={restartButtonTitle(task)} />
                        </button>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            </>
          ) : null}

          {partialTaskList.length > 0 ? (
            <>
              <div className="global-task-panel__subtitle global-task-panel__subtitle--partial">
                部分成功（可续生）
              </div>
              <div className="global-task-panel__list">
                {partialTaskList.map((task) => (
                  <div key={`p-${task.id}`} className="global-task-panel__item">
                    <div className="global-task-panel__item-main">
                      <div className="global-task-panel__name">{taskTypeLabel(task.taskType)}</div>
                      <div className="global-task-panel__model">模型：{task.modelCode || '-'}</div>
                    </div>
                    <div className="global-task-panel__ops">
                      <BillingQuoteConfirm
                        resolveRequest={() => resolveResumeBillingRequest(task)}
                        onConfirm={() => handleResume(task)}
                        title="确认续生失败项？"
                        okText="续生"
                      >
                        <button
                          type="button"
                          className="global-task-panel__icon-btn global-task-panel__icon-btn--restart"
                          title="续生失败项"
                        >
                          <img src={iconStar} alt="续生失败项" />
                        </button>
                      </BillingQuoteConfirm>
                    </div>
                  </div>
                ))}
              </div>
            </>
          ) : null}

          {cancelledTaskList.length > 0 ? (
            <>
              <div className="global-task-panel__subtitle global-task-panel__subtitle--cancelled">
                已取消（可重新生成）
              </div>
              <div className="global-task-panel__list">
                {cancelledTaskList.map((task) => (
                  <div key={`c-${task.id}`} className="global-task-panel__item">
                    <div className="global-task-panel__item-main">
                      <div className="global-task-panel__name">{taskTypeLabel(task.taskType)}</div>
                      <div className="global-task-panel__model">模型：{task.modelCode || '-'}</div>
                    </div>
                    <div className="global-task-panel__ops">
                      {showCancelledRegenerate(task) ? (
                        <BillingQuoteConfirm
                          resolveRequest={() => resolveResumeBillingRequest(task)}
                          onConfirm={() => handleResume(task)}
                          title="确认重新生成该任务？"
                          okText="重新生成"
                        >
                          <button
                            type="button"
                            className="global-task-panel__icon-btn global-task-panel__icon-btn--restart"
                            title="重新生成"
                          >
                            <img src={iconStar} alt="重新生成" />
                          </button>
                        </BillingQuoteConfirm>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            </>
          ) : null}

          {failedTaskList.length > 0 ? (
            <>
              <div className="global-task-panel__subtitle global-task-panel__subtitle--failed">
                失败（可重试）
              </div>
              <div className="global-task-panel__list">
                {failedTaskList.map((task) => (
                  <div key={`f-${task.id}`} className="global-task-panel__item">
                    <div className="global-task-panel__item-main">
                      <div className="global-task-panel__name">{taskTypeLabel(task.taskType)}</div>
                      <div className="global-task-panel__model">模型：{task.modelCode || '-'}</div>
                    </div>
                    <div className="global-task-panel__ops">
                      {showTaskRestart(task) ? (
                        normUserTaskType(task.taskType) === 'storyboard_script_batch' ? (
                          <BillingQuoteConfirm
                            resolveRequest={() => resolveRestartBillingRequest(task)}
                            onConfirm={() => handleRestart(task)}
                            title="确认重新生成失败场次？"
                          >
                            <button
                              type="button"
                              className="global-task-panel__icon-btn global-task-panel__icon-btn--restart"
                              title={restartButtonTitle(task)}
                            >
                              <img src={iconStar} alt={restartButtonTitle(task)} />
                            </button>
                          </BillingQuoteConfirm>
                        ) : (
                          <button
                            type="button"
                            className="global-task-panel__icon-btn global-task-panel__icon-btn--restart"
                            title={restartButtonTitle(task)}
                            onClick={() => handleRestart(task)}
                          >
                            <img src={iconStar} alt={restartButtonTitle(task)} />
                          </button>
                        )
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            </>
          ) : null}

          <InfiniteScrollLoadFooter
            loading={loadingMore}
            hasMore={tasksHasMore}
            hasItems={loadedTaskRows.length > 0}
            endText="已加载全部任务"
          />
          {!loading &&
          !ongoingTaskList.length &&
          !partialTaskList.length &&
          !cancelledTaskList.length &&
          !failedTaskList.length ? (
            <div className="global-task-panel__empty">暂无任务记录</div>
          ) : null}
        </>
      )}
    </div>
  )

  const circleClass = [
    'global-task-circle',
    panelOpen ? 'is-open' : '',
    badgeStyle === 'partial-only' ? 'global-task-circle--partial-only' : '',
    badgeStyle === 'cancelled-only' ? 'global-task-circle--cancelled-only' : '',
    badgeStyle === 'failed-only' ? 'global-task-circle--failed-only' : ''
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <Popover
      open={panelOpen}
      onOpenChange={(open) => setPanelOpen(open)}
      trigger="click"
      placement="bottomRight"
      classNames={{ root: 'global-generate-task-popover' }}
      content={panelContent}
    >
      <Tooltip
        title={panelOpen ? undefined : circleTitle}
        placement="bottom"
        mouseEnterDelay={0.25}
      >
        <button type="button" className={circleClass} aria-label={circleAriaLabel}>
          <span className="global-task-circle__count">{badgeNumber}</span>
        </button>
      </Tooltip>
    </Popover>
  )
}

export default GlobalGenerateTaskPopover
