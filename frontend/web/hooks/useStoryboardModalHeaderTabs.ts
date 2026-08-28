'use client'

import { useEffect,useRef,useState } from 'react'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardRecordListType,StoryboardRecordRow } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
buildStoryboardModalHeaderTabs,
type StoryboardModalHeaderTab,
type StoryboardModalSceneMeta
} from '~/utils/storyboardModalHeaderTabs'
import { fetchProjectStoryboardRecords } from '~/utils/storyboardRecordBatch'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

type HeaderTabOptions = {
  resolveFallbackThumbnailUrl?: (sceneIndex: number) => string
  resolveDubbingConfigured?: (sceneIndex: number, composeRows: StoryboardRecordRow[]) => boolean
}

/**
 * 原 Vue 版返回可读 ref（headerTabs / projectRecordRows）；React 版以 ref+state 镜像实现，
 * refreshHeaderTabs 供打开/切 Tab 时手动 force 拉取（编辑弹窗 autoRefreshOnOpen=false 时由外层统一调度）。
 */
export function useStoryboardModalHeaderTabs(options: {
  open: boolean
  recordType: StoryboardRecordListType
  scenes: () => StoryboardModalSceneMeta[]
  route: RouteLikeLocation
  headerOptions?: HeaderTabOptions | (() => HeaderTabOptions | undefined)
  /**
   * 打开时是否自动 force 拉 list-by-storyboard。
   * 编辑分镜图/视频弹窗应设为 false：由 syncSceneDetailAndRestore 统一拉一次，再喂给顶部 Tab + 画布，避免双打。
   */
  autoRefreshOnOpen?: boolean
}): {
  headerTabs: StoryboardModalHeaderTab[]
  headerLoading: boolean
  projectRecordRows: StoryboardRecordRow[]
  refreshHeaderTabs: (force?: boolean) => Promise<void>
} {
  const [headerTabs, setHeaderTabsState] = useState<StoryboardModalHeaderTab[]>([])
  const headerTabsRef = useRef<StoryboardModalHeaderTab[]>(headerTabs)
  const setHeaderTabs = (v: StoryboardModalHeaderTab[]) => {
    headerTabsRef.current = v
    setHeaderTabsState(v)
  }

  const [headerLoading, setHeaderLoading] = useState(false)
  const [projectRecordRows, setProjectRecordRowsState] = useState<StoryboardRecordRow[]>([])
  const projectRecordRowsRef = useRef<StoryboardRecordRow[]>(projectRecordRows)
  const setProjectRecordRows = (v: StoryboardRecordRow[]) => {
    projectRecordRowsRef.current = v
    setProjectRecordRowsState(v)
  }

  const autoRefreshOnOpen = options.autoRefreshOnOpen !== false
  const loadGenRef = useRef(0)

  /** 事件回调 / 异步流程内一律读最新 options，避免闭包捕获旧值 */
  const optionsRef = useRef(options)
  optionsRef.current = options

  async function refreshHeaderTabs(force?: boolean) {
    const gen = ++loadGenRef.current
    const sceneList = optionsRef.current.scenes()
    if (!sceneList.length) {
      setHeaderTabs([])
      setProjectRecordRows([])
      return
    }

    setHeaderLoading(true)
    try {
      const ctx = await resolveStoryScriptSaveContext(
        useCreationStore.getState(),
        optionsRef.current.route
      )
      if (!ctx || gen !== loadGenRef.current) return

      const rows = await fetchProjectStoryboardRecords(ctx, optionsRef.current.recordType, { force })
      if (gen !== loadGenRef.current) return

      setProjectRecordRows(rows)
      const headerOptions = optionsRef.current.headerOptions
      const extra = typeof headerOptions === 'function' ? headerOptions() : headerOptions
      setHeaderTabs(
        buildStoryboardModalHeaderTabs(sceneList, rows, optionsRef.current.recordType, extra)
      )
    } catch {
      if (gen !== loadGenRef.current) return
      setProjectRecordRows([])
      const headerOptions = optionsRef.current.headerOptions
      const extra = typeof headerOptions === 'function' ? headerOptions() : headerOptions
      setHeaderTabs(
        buildStoryboardModalHeaderTabs(sceneList, [], optionsRef.current.recordType, extra)
      )
    } finally {
      if (gen === loadGenRef.current) setHeaderLoading(false)
    }
  }

  const refreshRef = useRef(refreshHeaderTabs)
  refreshRef.current = refreshHeaderTabs

  /** 原 watch(open, immediate)：打开按需 force 拉取；关闭清空并递增 gen 使在途请求失效 */
  useEffect(() => {
    if (options.open) {
      if (autoRefreshOnOpen) void refreshRef.current(true)
    } else {
      loadGenRef.current += 1
      setHeaderTabs([])
      setProjectRecordRows([])
      setHeaderLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [options.open])

  return {
    headerTabs,
    headerLoading,
    projectRecordRows,
    refreshHeaderTabs
  }
}
