'use client'

import { message } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
resolveSelectedStyle,
type StyleLibraryCard
} from '~/composables/usePromptDictionary'
import { useStyleLibraryMergedPagination } from '~/composables/useStyleLibraryMergedPagination'
import type { GlobalSettingData } from '~/types'
import type { UserAssetStyleCategoryItem } from '~/types/business-api'
import { userAssetStyleCategoryList } from '~/utils/businessApi'
import { resolveProjectStyleReference } from '~/utils/buildProjectVideoStyleFields'
import {
  isSameProjectStyleSelection,
  isSameResolvedStyleSnapshot
} from '~/utils/projectStyleSelection'
import {
  resolveActiveStyleCategoryCode,
  visibleStyleCategories
} from '~/utils/styleCategoryTabs'

type SelectedStyleValue = NonNullable<GlobalSettingData['selectedStyle']>

const COMMON_STYLE_COUNT = 30

/** DOM 更新后再量取（原 await nextTick） */
function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

export interface UseGlobalSettingStyleLibraryOptions {
  modelValue: GlobalSettingData
  onModelValueChange: (value: GlobalSettingData) => void
  styleLocked: boolean
  styleLockActionMessage: string
}

/**
 * GlobalSetting 风格库逻辑（自 GlobalSetting.vue script 原样拆出）：
 * 合并分页加载、当前风格定位/补页、选择与锁定、我的风格库同步。
 */
export function useGlobalSettingStyleLibrary(options: UseGlobalSettingStyleLibraryOptions) {
  // 事件回调 / async 流程读最新 props（等价于 Vue props 响应式引用）
  const modelValueRef = useRef(options.modelValue)
  modelValueRef.current = options.modelValue
  const emitRef = useRef(options.onModelValueChange)
  emitRef.current = options.onModelValueChange
  const styleLockedRef = useRef(options.styleLocked)
  styleLockedRef.current = options.styleLocked

  const [styleCategories, setStyleCategories] = useState<UserAssetStyleCategoryItem[]>([])
  const [activeStyleCategoryCode, setActiveStyleCategoryCode] = useState('all')
  const [styleCategoriesLoading, setStyleCategoriesLoading] = useState(false)
  const [styleCategoriesError, setStyleCategoriesError] = useState(false)
  const styleCategoriesRequestedRef = useRef(false)
  const styleLibraryMountedRef = useRef(false)

  useEffect(() => {
    styleLibraryMountedRef.current = true
    return () => {
      styleLibraryMountedRef.current = false
    }
  }, [])

  const styleScrollRootRef = useRef<HTMLElement | null>(null)
  const {
    customStyles,
    officialStyles,
    mergedStyleList,
    loading: styleListLoading,
    loadingMore: styleLoadingMore,
    initialLoaded: styleInitialLoaded,
    loadError: styleLoadError,
    hasMore: styleHasMore,
    appendTick: styleAppendTick,
    reload: reloadStyleLibrary,
    loadNextPage: loadNextStylePage,
    moveCustomStyleToFront,
    onScrollWhenExpanded,
    getCustomStylesNow,
    getOfficialStylesNow,
    getMergedStyleListNow,
    getFlagsNow
  } = useStyleLibraryMergedPagination(styleScrollRootRef, activeStyleCategoryCode)

  const stylesLoaded = styleInitialLoaded && !styleListLoading
  const [stylesLoadRevision, setStylesLoadRevision] = useState(0)

  const [isStylePanelOpen, setIsStylePanelOpen] = useState(false)
  const isStylePanelOpenRef = useRef(isStylePanelOpen)
  isStylePanelOpenRef.current = isStylePanelOpen
  const [commonStyles, setCommonStyles] = useState<StyleLibraryCard[]>([])
  const [commonStylesLoaded, setCommonStylesLoaded] = useState(false)
  const [commonStylesLoadError, setCommonStylesLoadError] = useState(false)

  const selectStyleCategory = useCallback((categoryCode: string) => {
    const next = String(categoryCode || '').trim()
    if (!next) return
    setActiveStyleCategoryCode(next)
  }, [])

  const ensureStyleCategories = useCallback(async () => {
    if (styleCategoriesRequestedRef.current) return
    styleCategoriesRequestedRef.current = true
    setStyleCategoriesLoading(true)
    setStyleCategoriesError(false)
    try {
      const rows = await userAssetStyleCategoryList()
      if (!styleLibraryMountedRef.current) return
      const visible = visibleStyleCategories(rows)
      setStyleCategories(visible)
      const nextCode = resolveActiveStyleCategoryCode(visible, activeStyleCategoryCode)
      if (nextCode && nextCode !== activeStyleCategoryCode) {
        setActiveStyleCategoryCode(nextCode)
      }
    } catch {
      if (!styleLibraryMountedRef.current) return
      styleCategoriesRequestedRef.current = false
      setStyleCategories([])
      setStyleCategoriesError(true)
    } finally {
      if (styleLibraryMountedRef.current) setStyleCategoriesLoading(false)
    }
  }, [activeStyleCategoryCode])

  const findStyleSelectionInList = useCallback(
    (selection: SelectedStyleValue, list: StyleLibraryCard[]): StyleLibraryCard | undefined => {
      const resolved = resolveSelectedStyle(selection, list)
      if (!resolved) return undefined
      return list.find((style) => style.id === resolved.id)
    },
    []
  )

  const loadedSelectedStyle = useMemo(() => {
    const current = options.modelValue.selectedStyle
    return current ? findStyleSelectionInList(current, mergedStyleList) : undefined
  }, [options.modelValue.selectedStyle, mergedStyleList, findStyleSelectionInList])

  const selectedStyleShortcut = useMemo(() => {
    const current = options.modelValue.selectedStyle
    if (!current) return null
    return loadedSelectedStyle ?? current
  }, [options.modelValue.selectedStyle, loadedSelectedStyle])

  const [selectedStyleImageHydrating, setSelectedStyleImageHydrating] = useState(false)
  const selectedStyleImageLoading = Boolean(
    options.modelValue.selectedStyle &&
    !loadedSelectedStyle &&
    (!stylesLoaded ||
      selectedStyleImageHydrating ||
      (styleHasMore && !styleLoadError))
  )
  const selectedStyleThumbnail = useMemo(() => {
    const loaded = loadedSelectedStyle
    if (loaded) return String(loaded.thumbnail || '').trim()

    const current = options.modelValue.selectedStyle
    if (!current) return ''
    // 项目详情只携带公开风格快照时，thumbnail 可能是项目封面；必须等风格库真实记录补齐。
    if (!resolveProjectStyleReference(current) && String(current.promptText || '').trim()) return ''
    return String(current.thumbnail || '').trim()
  }, [loadedSelectedStyle, options.modelValue.selectedStyle])

  const [locatedStyleId, setLocatedStyleId] = useState<string | null>(null)
  const locatedStyleIdRef = useRef(locatedStyleId)
  locatedStyleIdRef.current = locatedStyleId
  const locateRequestIdRef = useRef(0)
  const selectedStyleLoadRequestIdRef = useRef(0)
  const locatedStyleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const onFeaturedStylesScroll = useCallback(() => {
    onScrollWhenExpanded(isStylePanelOpenRef.current)
  }, [onScrollWhenExpanded])

  const changeStylePanelOpen = useCallback(
    (next: boolean) => {
      if (isStylePanelOpenRef.current === next) return
      setIsStylePanelOpen(next)
      isStylePanelOpenRef.current = next
      if (next) {
        void ensureStyleCategories()
      } else {
        locateRequestIdRef.current += 1
      }
    },
    [ensureStyleCategories]
  )

  const findCurrentStyleInLoadedList = useCallback((): StyleLibraryCard | undefined => {
    const current = modelValueRef.current.selectedStyle
    return current ? findStyleSelectionInList(current, getMergedStyleListNow()) : undefined
  }, [findStyleSelectionInList, getMergedStyleListNow])

  // 原 watch([styleListLoading, styleLoadingMore]) 等待空闲：提交后统一唤醒等待者
  const pageBusyRef = useRef(false)
  pageBusyRef.current = styleListLoading || styleLoadingMore
  const idleWaitersRef = useRef<Array<() => void>>([])
  useEffect(() => {
    if (styleListLoading || styleLoadingMore) return
    if (!idleWaitersRef.current.length) return
    const waiters = idleWaitersRef.current
    idleWaitersRef.current = []
    waiters.forEach((resolve) => resolve())
  }, [styleListLoading, styleLoadingMore])

  const waitForStylePageIdle = useCallback(async (): Promise<void> => {
    if (!pageBusyRef.current) return
    await new Promise<void>((resolve) => {
      idleWaitersRef.current.push(resolve)
    })
  }, [])

  const scrollStyleCardIntoView = useCallback((styleId: string): boolean => {
    const root = styleScrollRootRef.current
    if (!root) return false
    const card = Array.from(root.querySelectorAll<HTMLElement>('[data-style-card-id]')).find(
      (element) => element.dataset.styleCardId === styleId
    )
    if (!card) return false

    const rootRect = root.getBoundingClientRect()
    const cardRect = card.getBoundingClientRect()
    const centeredTop =
      root.scrollTop + cardRect.top - rootRect.top - (root.clientHeight - cardRect.height) / 2
    root.scrollTo({ top: Math.max(0, centeredTop), behavior: 'smooth' })

    setLocatedStyleId(styleId)
    locatedStyleIdRef.current = styleId
    if (locatedStyleTimerRef.current) clearTimeout(locatedStyleTimerRef.current)
    locatedStyleTimerRef.current = setTimeout(() => {
      if (locatedStyleIdRef.current === styleId) setLocatedStyleId(null)
    }, 2400)
    return true
  }, [])

  /** 当前风格不在首屏时继续读取后续页，用真实风格记录补齐名称、图片和稳定资产身份。 */
  const ensureCurrentStyleLoaded = useCallback(async (): Promise<StyleLibraryCard | undefined> => {
    const selection = modelValueRef.current.selectedStyle
    if (!selection) return undefined

    const requestId = ++selectedStyleLoadRequestIdRef.current
    setSelectedStyleImageHydrating(true)
    try {
      await waitForStylePageIdle()
      if (requestId !== selectedStyleLoadRequestIdRef.current) return undefined

      let target = findStyleSelectionInList(selection, getMergedStyleListNow())
      while (!target && getFlagsNow().hasMore && !getFlagsNow().loadError) {
        await loadNextStylePage()
        await waitForStylePageIdle()
        if (requestId !== selectedStyleLoadRequestIdRef.current) return undefined
        target = findStyleSelectionInList(selection, getMergedStyleListNow())
      }
      return target
    } finally {
      if (requestId === selectedStyleLoadRequestIdRef.current) {
        setSelectedStyleImageHydrating(false)
      }
    }
  }, [waitForStylePageIdle, findStyleSelectionInList, getMergedStyleListNow, getFlagsNow, loadNextStylePage])

  /** 打开风格面板；目标尚未加载时继续翻页，加载到后定位同一条真实资产卡片。 */
  async function locateSelectedStyle() {
    if (!modelValueRef.current.selectedStyle) return
    const requestId = ++locateRequestIdRef.current
    changeStylePanelOpen(true)
    await nextFrame()
    const target = findCurrentStyleInLoadedList() ?? (await ensureCurrentStyleLoaded())

    if (requestId !== locateRequestIdRef.current) return
    if (!target) {
      message.warning(getFlagsNow().loadError ? '风格加载失败，请稍后重试' : '未找到当前风格')
      return
    }

    await nextFrame()
    for (let attempt = 0; attempt < 3 && !styleScrollRootRef.current; attempt += 1) {
      await nextFrame()
    }
    if (!scrollStyleCardIntoView(target.id)) {
      message.warning('未找到当前风格')
    }
  }

  // 选择风格：官方精选带 assetName + promptText，供创建作品写入 videoStyleType / videoStyleValue
  const selectStyle = (style: {
    id: string
    name: string
    thumbnail: string
    assetId?: number
    sourceFlag?: 'official' | 'custom'
    assetName?: string
    promptText?: string | null
  }) => {
    if (styleLockedRef.current && !isCurrentStyle(style)) {
      message.warning(options.styleLockActionMessage)
      return
    }
    if (isCurrentStyle(style)) {
      changeStylePanelOpen(false)
      return
    }
    selectedStyleLoadRequestIdRef.current += 1
    setSelectedStyleImageHydrating(false)
    emitRef.current({
      ...modelValueRef.current,
      selectedStyle: {
        id: style.id,
        name: style.name,
        thumbnail: style.thumbnail,
        ...(style.assetId != null ? { assetId: style.assetId } : {}),
        ...(style.sourceFlag ? { sourceFlag: style.sourceFlag } : {}),
        ...(style.assetName != null && style.assetName !== '' ? { assetName: style.assetName } : {}),
        ...(style.promptText != null ? { promptText: style.promptText } : {})
      },
      style: style.name,
      styleSelectionTouched: true
    })
    changeStylePanelOpen(false)
  }

  function isCurrentStyle(
    style: Pick<
      StyleLibraryCard,
      'id' | 'assetId' | 'sourceFlag' | 'name' | 'assetName' | 'promptText'
    >
  ): boolean {
    const current = modelValueRef.current.selectedStyle
    if (!current) return false
    if (resolveProjectStyleReference(current)) {
      return isSameProjectStyleSelection(current, style)
    }
    // 旧项目没有持久化来源/ID；同名同公开文案命中多条时不猜来源，也不把多张卡同时判为当前风格。
    const legacyMatches = getMergedStyleListNow().filter((item) =>
      isSameProjectStyleSelection(current, item)
    )
    return legacyMatches.length === 1 && legacyMatches[0]?.id === style.id
  }

  // 打开/关闭“更多风格”浮层
  const toggleStylePanel = () => {
    changeStylePanelOpen(!isStylePanelOpenRef.current)
  }

  const syncMyStylesFromCustom = useCallback(() => {
    if (activeStyleCategoryCode !== 'all') return
    const mapped = getCustomStylesNow().map((s) => ({
      id: s.id,
      name: s.name,
      thumbnail: s.thumbnail
    }))
    const currentModelValue = modelValueRef.current
    if (
      mapped.length === currentModelValue.myStyles.length &&
      mapped.every((s, i) => s.id === currentModelValue.myStyles[i]?.id)
    ) {
      return
    }
    emitRef.current({
      ...currentModelValue,
      myStyles: mapped
    })
  }, [activeStyleCategoryCode, getCustomStylesNow])

  const loadAllStyles = useCallback(
    async (preferredCustomId?: string) => {
      await reloadStyleLibrary()
      setStylesLoadRevision((v) => v + 1)
      if (activeStyleCategoryCode === 'all') {
        if (getFlagsNow().loadError) {
          setCommonStylesLoadError(true)
        } else {
          setCommonStyles(getMergedStyleListNow().slice(0, COMMON_STYLE_COUNT))
          setCommonStylesLoaded(true)
          setCommonStylesLoadError(false)
        }
      }
      if (preferredCustomId) {
        moveCustomStyleToFront(preferredCustomId)
      }
      syncMyStylesFromCustom()
      // 新建/未选时默认精选风格库第一项；无精选时回退合并列表第一项
      const first = getOfficialStylesNow()[0] ?? getMergedStyleListNow()[0]
      if (activeStyleCategoryCode === 'all' && !modelValueRef.current.selectedStyle && first) {
        emitRef.current({
          ...modelValueRef.current,
          selectedStyle: {
            id: first.id,
            name: first.name,
            thumbnail: first.thumbnail,
            ...(first.assetId != null ? { assetId: first.assetId } : {}),
            ...(first.sourceFlag ? { sourceFlag: first.sourceFlag } : {}),
            ...(first.assetName != null && first.assetName !== ''
              ? { assetName: first.assetName }
              : {}),
            ...(first.promptText != null ? { promptText: first.promptText } : {})
          }
        })
      }
    },
    [
      reloadStyleLibrary,
      moveCustomStyleToFront,
      syncMyStylesFromCustom,
      getOfficialStylesNow,
      getMergedStyleListNow,
      getFlagsNow,
      activeStyleCategoryCode
    ]
  )

  const categoryChangeMountedRef = useRef(false)
  useEffect(() => {
    if (!categoryChangeMountedRef.current) {
      categoryChangeMountedRef.current = true
      return
    }
    locateRequestIdRef.current += 1
    selectedStyleLoadRequestIdRef.current += 1
    setSelectedStyleImageHydrating(false)
    let cancelled = false
    void (async () => {
      await waitForStylePageIdle()
      if (!cancelled) await loadAllStyles()
    })()
    return () => {
      cancelled = true
    }
  }, [activeStyleCategoryCode, loadAllStyles, waitForStylePageIdle])

  // 原 watch([selectedStyle, mergedStyleList], flush: 'post')：风格 id 迁移后回写稳定 id
  useEffect(() => {
    const sel = options.modelValue.selectedStyle
    const list = mergedStyleList
    if (!sel || !list.length) return
    const n = resolveSelectedStyle(sel, list)
    if (n && !isSameResolvedStyleSnapshot(sel, n)) {
      emitRef.current({ ...modelValueRef.current, selectedStyle: n })
    }
  }, [options.modelValue.selectedStyle, mergedStyleList])

  // 原 watch(customStyles, deep)：自定义风格变化后同步我的风格库
  useEffect(() => {
    syncMyStylesFromCustom()
  }, [customStyles, syncMyStylesFromCustom])

  // 首屏仅加载 30 个常用风格；分类在“更多”面板首次打开时再读取。
  useEffect(() => {
    void loadAllStyles()
    return () => {
      locateRequestIdRef.current += 1
      selectedStyleLoadRequestIdRef.current += 1
      setSelectedStyleImageHydrating(false)
      if (locatedStyleTimerRef.current) clearTimeout(locatedStyleTimerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return {
    styleScrollRootRef,
    customStyles,
    officialStyles,
    mergedStyleList,
    styleLoadingMore,
    styleLoadError,
    styleHasMore,
    styleAppendTick,
    stylesLoaded,
    stylesLoadRevision,
    isStylePanelOpen,
    commonStylesLoaded,
    commonStylesLoadError,
    commonStyles,
    styleCategories,
    activeStyleCategoryCode,
    styleCategoriesLoading,
    styleCategoriesError,
    selectStyleCategory,
    selectedStyleShortcut,
    selectedStyleImageLoading,
    selectedStyleThumbnail,
    locatedStyleId,
    onFeaturedStylesScroll,
    locateSelectedStyle,
    selectStyle,
    isCurrentStyle,
    changeStylePanelOpen,
    toggleStylePanel,
    syncMyStylesFromCustom,
    loadAllStyles,
    getCustomStylesNow
  }
}
