'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { CheckOutlined, InfoCircleOutlined, PlusOutlined, RightOutlined } from '@ant-design/icons'
import { message } from 'antd'
import dialogSelectSelIconMod from '@/assets/img/icon/dialog-select-sel.svg'
import type { GlobalSettingData } from '~/types'
import {
  usePromptDictionary,
  buildStyleLibraryCardId,
  type StyleLibraryCard
} from '~/composables/usePromptDictionary'
import { EllipsisTooltip } from '~/components/common/EllipsisTooltip'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { emptyImageIconUrl as emptyImageIconMod } from '~/utils/emptyImageIcon'
import { buildRetinaDisplayImageUrl } from '~/utils/displayImageUrl'
import { assetUrl } from '~/utils/assetUrl'
import { useGlobalSettingStyleLibrary } from './global-setting/useGlobalSettingStyleLibrary'
import GlobalSettingStyleFormModal, {
  type GlobalSettingStyleFormModalHandle
} from './global-setting/GlobalSettingStyleFormModal'
import GlobalSettingStyleDialog from './global-setting/GlobalSettingStyleDialog'
import './global-setting/global-setting.css'

const dialogSelectSelIcon = assetUrl(dialogSelectSelIconMod)
const emptyImageIconUrl = assetUrl(emptyImageIconMod)

export interface GlobalSettingProps {
  modelValue: GlobalSettingData
  onModelValueChange: (value: GlobalSettingData) => void
  description?: string
  styleLibraryOnly?: boolean
  /** 外部业务锁：仅影响风格选择，不改写接口返回的 styleLocked。 */
  forceStyleLocked?: boolean
  styleLockHint?: string
  /** 风格卡片 CSS 宽度，用于请求 Retina 清晰图 */
  styleThumbSizePx?: number
}

/** 全局设置（画面比例 / 剧本类型 / 模型策略 / 创作模式 / 画面风格库），原 GlobalSetting.vue */
export function GlobalSetting({
  modelValue,
  onModelValueChange,
  // description 仅保留 props 契约（原模板中展示段已注释）
  description = '设定作品类型、画幅与创作策略',
  styleLibraryOnly = false,
  forceStyleLocked = false,
  styleLockHint = '',
  styleThumbSizePx = 160
}: GlobalSettingProps) {
  void description

  const styleLocked = modelValue.styleLocked === true || forceStyleLocked === true
  const styleLockHintText = styleLockHint.trim()
    ? styleLockHint.trim()
    : '当前项目已生成角色、场景或道具，风格已锁定；其它项目配置仍可正常保存。'
  const styleLockActionMessage = forceStyleLocked
    ? '已生成分镜脚本，画面风格仅可查看'
    : '已有资产，无法切换风格'

  // 事件回调读最新 props（新增风格回填等 async 流程）
  const modelValueRef = useRef(modelValue)
  modelValueRef.current = modelValue
  const emitRef = useRef(onModelValueChange)
  emitRef.current = onModelValueChange
  const styleLockedRef = useRef(styleLocked)
  styleLockedRef.current = styleLocked

  const VALID_ASPECT = useMemo(
    () => new Set<string>(['16:9', '9:16', '4:3', '3:4', '1:1', '21:9']),
    []
  )

  const {
    ensureLoaded,
    loaded: dictLoaded,
    aspectRatioEnumOptions,
    scriptTypeEnumOptions,
    creationModeEnumOptions,
    genModeEnumOptions
  } = usePromptDictionary()

  const aspectRatios = useMemo(
    () =>
      aspectRatioEnumOptions
        .filter((r) => VALID_ASPECT.has(r.value))
        .map((r) => ({
          value: r.value as GlobalSettingData['aspectRatio'],
          label: r.label
        })),
    [aspectRatioEnumOptions, VALID_ASPECT]
  )

  const scriptTypes = useMemo(
    () =>
      scriptTypeEnumOptions.map((r) => ({
        value: r.value as GlobalSettingData['scriptType'],
        label: r.label
      })),
    [scriptTypeEnumOptions]
  )

  const creationModes = useMemo(
    () =>
      creationModeEnumOptions.map((r) => ({
        value: r.value as GlobalSettingData['creationMode'],
        label: r.label
      })),
    [creationModeEnumOptions]
  )

  const modelStrategies = useMemo(
    () =>
      genModeEnumOptions.map((r) => ({
        value: r.value as GlobalSettingData['modelStrategy'],
        label: r.label
      })),
    [genModeEnumOptions]
  )

  const {
    styleScrollRootRef,
    mergedStyleList,
    styleLoadingMore,
    styleLoadError,
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
  } = useGlobalSettingStyleLibrary({
    modelValue,
    onModelValueChange,
    styleLocked,
    styleLockActionMessage
  })

  // 浮层首次挂载、分类切换或翻页完成后补一次触底检测，避免短列表无法产生滚动事件。
  useEffect(() => {
    if (!isStylePanelOpen || !stylesLoaded) return undefined
    const frame = requestAnimationFrame(onFeaturedStylesScroll)
    return () => cancelAnimationFrame(frame)
  }, [isStylePanelOpen, stylesLoaded, mergedStyleList.length, onFeaturedStylesScroll])

  // 原 onMounted：确保字典已加载（风格列表加载在 useGlobalSettingStyleLibrary 内）
  useEffect(() => {
    void ensureLoaded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 TransitionGroup(name="style-card-append")：仅上拉追加的新卡片做淡入上移，首屏/重载不动画
  const prevStyleIdsRef = useRef<Set<string>>(new Set())
  const lastStyleAppendTickRef = useRef(0)
  const [appendedStyleIds, setAppendedStyleIds] = useState<ReadonlySet<string>>(new Set())
  useEffect(() => {
    const currentIds = new Set(mergedStyleList.map((s) => s.id))
    if (styleAppendTick > 0 && styleAppendTick !== lastStyleAppendTickRef.current) {
      const fresh = new Set<string>()
      currentIds.forEach((id) => {
        if (!prevStyleIdsRef.current.has(id)) fresh.add(id)
      })
      if (fresh.size) setAppendedStyleIds(fresh)
    }
    lastStyleAppendTickRef.current = styleAppendTick
    prevStyleIdsRef.current = currentIds
  }, [styleAppendTick, mergedStyleList])

  function resolveStyleThumbSrc(url: string): string {
    return buildRetinaDisplayImageUrl(url, styleThumbSizePx)
  }

  // 更新值
  const updateValue = (key: keyof GlobalSettingData, value: any) => {
    onModelValueChange({
      ...modelValue,
      [key]: value
    })
  }

  const styleFormModalRef = useRef<GlobalSettingStyleFormModalHandle | null>(null)

  function openCreateStyleModal() {
    if (styleLockedRef.current) {
      message.warning(styleLockActionMessage)
      return
    }
    if (styleFormModalRef.current?.isCreatingStyle()) return
    if (activeStyleCategoryCode !== 'all') selectStyleCategory('all')
    changeStylePanelOpen(false)
    styleFormModalRef.current?.openCreateStyleModal()
  }

  /** 新增风格创建成功后：刷新列表置顶新风格，并（未锁定时）选中它 */
  async function handleStyleCreated(createdId: number) {
    await loadAllStyles(buildStyleLibraryCardId('custom', createdId))

    const newStyleId = buildStyleLibraryCardId('custom', createdId)
    const newStyle = getCustomStylesNow().find((s) => s.id === newStyleId)
    if (newStyle && !styleLockedRef.current) {
      emitRef.current({
        ...modelValueRef.current,
        selectedStyle: {
          id: newStyle.id,
          name: newStyle.name,
          thumbnail: newStyle.thumbnail,
          assetId: newStyle.assetId,
          sourceFlag: newStyle.sourceFlag,
          assetName: newStyle.assetName,
          promptText: newStyle.promptText
        },
        style: newStyle.name,
        styleSelectionTouched: true,
        myStyles: getCustomStylesNow().map((s) => ({
          id: s.id,
          name: s.name,
          thumbnail: s.thumbnail
        }))
      })
    } else {
      syncMyStylesFromCustom()
    }
  }

  /** 原 @keydown.enter.prevent / @keydown.space.prevent */
  function onCardActivateKeyDown(event: ReactKeyboardEvent, action: () => void) {
    if (event.key !== 'Enter' && event.key !== ' ' && event.key !== 'Spacebar') return
    event.preventDefault()
    action()
  }

  function renderAddStyleCard() {
    return (
      <div
        key="add-style"
        className={`style-card add-style${styleLocked ? ' is-style-locked' : ''}`}
        role="button"
        tabIndex={styleLocked ? -1 : 0}
        aria-disabled={styleLocked}
        aria-label={styleLocked ? '风格已锁定，无法添加风格' : '添加风格'}
        onClick={openCreateStyleModal}
        onKeyDown={(event) => onCardActivateKeyDown(event, openCreateStyleModal)}
      >
        <PlusOutlined className="add-icon" />
        <span className="add-text">添加风格</span>
      </div>
    )
  }

  function renderStyleCard(style: StyleLibraryCard) {
    const isActive = isCurrentStyle(style)
    const cardClass = [
      'style-card',
      isActive ? 'active' : '',
      style.featured ? 'featured' : '',
      styleLocked && !isActive ? 'is-style-locked' : '',
      locatedStyleId === style.id ? 'style-card--located' : '',
      appendedStyleIds.has(style.id) ? 'style-card-append-enter' : ''
    ]
      .filter(Boolean)
      .join(' ')

    return (
      <div
        key={style.id}
        data-style-card-id={style.id}
        className={cardClass}
        aria-disabled={styleLocked && !isActive}
        onClick={() => selectStyle(style)}
      >
        {style.featured && <span className="featured-badge">精选</span>}
        <span
          className={`style-selected-mark${isActive ? ' style-selected-mark--visible' : ''}`}
          aria-hidden="true"
        >
          <img src={dialogSelectSelIcon} alt="" className="style-selected-mark__icon" />
        </span>
        <span className="style-active-ring" aria-hidden="true" />

        <div className="style-thumb">
          {style.thumbnail ? (
            <ShimmerImage
              key={`${stylesLoadRevision}-${style.id}`}
              src={resolveStyleThumbSrc(style.thumbnail)}
              alt={style.name}
              wrapperClass="style-thumb-shimmer"
              imgClass="style-thumb-img"
              objectFit="cover"
              revealDirection="fade"
              minShimmerMs={280}
            />
          ) : (
            <div className="style-thumb-placeholder">
              <img
                src={emptyImageIconUrl}
                alt=""
                className="empty-image-icon empty-image-icon--md"
              />
            </div>
          )}
        </div>
        <div className="style-overlay">
          <EllipsisTooltip title={style.name} />
        </div>
      </div>
    )
  }

  return (
    <div className="global-setting create-step-global-setting">
      {/* <div class="content-header">
        <p class="step-description-text">{{ description }}</p>
      </div> */}

      <div className="setting-sections">
        {/* 选择画面比例 */}
        {!styleLibraryOnly && (
          <div className="setting-section">
            <h3 className="section-title">选择画面比例</h3>
            {!dictLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !aspectRatios.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="option-group">
                {aspectRatios.map((ratio) => (
                  <div
                    key={ratio.value}
                    className={`option-card${modelValue.aspectRatio === ratio.value ? ' active' : ''}`}
                    onClick={() => updateValue('aspectRatio', ratio.value)}
                  >
                    <div className="option-checkbox">
                      {modelValue.aspectRatio === ratio.value && (
                        <CheckOutlined className="check-icon" />
                      )}
                    </div>
                    <span className="option-label">{ratio.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 选择剧本类型 */}
        {!styleLibraryOnly && (
          <div className="setting-section">
            <h3 className="section-title">选择剧本类型</h3>
            {!dictLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !scriptTypes.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="option-group">
                {scriptTypes.map((type) => (
                  <div
                    key={type.value}
                    className={`option-card${modelValue.scriptType === type.value ? ' active' : ''}`}
                    onClick={() => updateValue('scriptType', type.value)}
                  >
                    <span className="option-label">{type.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 选择模型策略 */}
        {!styleLibraryOnly && (
          <div className="setting-section">
            <h3 className="section-title">选择模型策略</h3>
            {!dictLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !modelStrategies.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="option-group">
                {modelStrategies.map((strategy) => (
                  <div
                    key={strategy.value}
                    className={`option-card${modelValue.modelStrategy === strategy.value ? ' active' : ''}`}
                    onClick={() => updateValue('modelStrategy', strategy.value)}
                  >
                    <span className="option-label">{strategy.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 选择创作模式 */}
        {!styleLibraryOnly && (
          <div className="setting-section">
            <h3 className="section-title">
              选择创作模式
              <InfoCircleOutlined className="info-icon" />
            </h3>
            {!dictLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !creationModes.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="option-group">
                {creationModes.map((mode) => (
                  <div
                    key={mode.value}
                    className={`option-card${modelValue.creationMode === mode.value ? ' active' : ''}`}
                    onClick={() => updateValue('creationMode', mode.value)}
                  >
                    <span className="option-label">{mode.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 选择画面风格 */}
        <div className="setting-section">
          {/* <h3 class="section-title">
            选择画面风格
            <InfoCircleOutlined class="info-icon" />
          </h3> */}

          {styleLocked && <p className="style-lock-hint">{styleLockHintText}</p>}
          {/* 我的风格库 */}
          <div className="my-styles">
            <h4 className="subsection-title title-one">我的风格库</h4>
            <div className="styles-grid">
              {selectedStyleShortcut && (
                <div
                  className="style-card active selected-style-shortcut"
                  role="button"
                  tabIndex={0}
                  aria-label="定位当前选择的风格"
                  onClick={() => void locateSelectedStyle()}
                  onKeyDown={(e) => onCardActivateKeyDown(e, () => void locateSelectedStyle())}
                >
                  <span className="current-style-badge">当前风格</span>
                  <span
                    className="style-selected-mark style-selected-mark--visible"
                    aria-hidden="true"
                  >
                    <img src={dialogSelectSelIcon} alt="" className="style-selected-mark__icon" />
                  </span>
                  <span className="style-active-ring" aria-hidden="true" />

                  <div className="style-thumb">
                    {selectedStyleThumbnail || selectedStyleImageLoading ? (
                      <ShimmerImage
                        src={selectedStyleThumbnail ? resolveStyleThumbSrc(selectedStyleThumbnail) : ''}
                        alt={selectedStyleShortcut.name}
                        wrapperClass="style-thumb-shimmer"
                        imgClass="style-thumb-img"
                        objectFit="cover"
                        revealDirection="fade"
                        minShimmerMs={280}
                      />
                    ) : (
                      <div className="style-thumb-placeholder">
                        <img
                          src={emptyImageIconUrl}
                          alt=""
                          className="empty-image-icon empty-image-icon--md"
                        />
                      </div>
                    )}
                  </div>
                  <div className="style-overlay">
                    <EllipsisTooltip title={selectedStyleShortcut.name} />
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* 精选风格库 */}
          <div className="featured-styles">
            <div className="subsection-header">
              <h4 className="subsection-title">精选风格库</h4>
              <button
                type="button"
                className="style-more-button"
                aria-expanded={isStylePanelOpen}
                aria-haspopup="dialog"
                onClick={toggleStylePanel}
              >
                <span>更多</span>
                <RightOutlined className="style-more-button__arrow" aria-hidden="true" />
              </button>
            </div>
            {commonStylesLoadError ? (
              <p className="dict-placeholder">加载失败，请稍后重试</p>
            ) : !commonStylesLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !commonStyles.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : null}
            {commonStylesLoaded && !commonStylesLoadError && (
              <div className="styles-grid">
                {renderAddStyleCard()}
                {commonStyles.map(renderStyleCard)}
              </div>
            )}
          </div>

          <GlobalSettingStyleDialog
            open={isStylePanelOpen}
            onOpenChange={changeStylePanelOpen}
          >
            <div className="style-browser-shell">
              <div
                className="style-browser-popover__header"
              >
                <div
                  className="style-category-filter"
                  role="tablist"
                  aria-label="精选风格分类"
                  aria-orientation="horizontal"
                >
                  {styleCategoriesLoading ? (
                    <span className="style-category-filter__hint">分类加载中…</span>
                  ) : styleCategoriesError ? (
                    <span className="style-category-filter__hint">分类加载失败，请重新打开</span>
                  ) : (
                    styleCategories.map((category) => (
                      <button
                        key={category.code}
                        type="button"
                        role="tab"
                        className={`style-category-filter__item${activeStyleCategoryCode === category.code ? ' is-active' : ''}`}
                        aria-selected={activeStyleCategoryCode === category.code}
                        disabled={!stylesLoaded || styleLoadingMore}
                        onClick={() => selectStyleCategory(category.code)}
                      >
                        {category.label}
                      </button>
                    ))
                  )}
                </div>
              </div>
              <div
                ref={(element) => {
                  styleScrollRootRef.current = element
                }}
                className="style-browser-popover__body"
                onScroll={onFeaturedStylesScroll}
              >
                {styleLoadError ? (
                  <p className="dict-placeholder">加载失败，请稍后重试</p>
                ) : !stylesLoaded ? (
                  <p className="dict-placeholder">加载中…</p>
                ) : !mergedStyleList.length ? (
                  <p className="dict-placeholder">暂无数据</p>
                ) : null}
                {stylesLoaded && !styleLoadError && (
                  <div className={`styles-grid${styleLoadingMore ? ' styles-grid--appending' : ''}`}>
                    {mergedStyleList.map(renderStyleCard)}
                  </div>
                )}
                {styleLoadingMore && (
                  <InfiniteScrollLoadFooter loading />
                )}
              </div>
            </div>
          </GlobalSettingStyleDialog>
        </div>
      </div>

      <GlobalSettingStyleFormModal
        ref={styleFormModalRef}
        styleLocked={styleLocked}
        styleLockActionMessage={styleLockActionMessage}
        onCreated={handleStyleCreated}
      />
    </div>
  )
}

export default GlobalSetting
