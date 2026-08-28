'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type {
  CSSProperties,
  ChangeEvent,
  MouseEvent as ReactMouseEvent,
  UIEvent as ReactUIEvent
} from 'react'
import { Button, Form, Input, Modal, Select, message } from 'antd'
import { PlusOutlined, SearchOutlined } from '@ant-design/icons'
import type { MergedAssetVO, UserAssetCustomTypeItem } from '~/types/business-api'
import {
  userAssetCustomCreate,
  userAssetCustomDelete,
  userAssetCustomDetail,
  userAssetMergedPage,
  userAssetCustomTypeList,
  userAssetCustomUpdate
} from '~/utils/businessApi'
import deleteWhiteSvg from '~/assets/img/home/delete-white.svg'
import editWhiteSvg from '~/assets/img/home/edit-white.svg'
import sceneNorSvg from '~/assets/img/icon/scene-nor.svg'
import sceneSelSvg from '~/assets/img/icon/scene-sel.svg'
import characterNorSvg from '~/assets/img/icon/character-nor.svg'
import characterSelSvg from '~/assets/img/icon/prop-sel.svg'
import propNorSvg from '~/assets/img/icon/prop-nor.svg'
import propSelSvg from '~/assets/img/icon/character-sel.svg'
import recordingNorSvg from '~/assets/img/icon/recording-nor.svg'
import recordingSelSvg from '~/assets/img/icon/recording-sel.svg'
import { emptyImageIconUrl as emptyImageIconSvg } from '~/utils/emptyImageIcon'
import assetsEmptyIconSvg from '~/assets/img/icon/assest-empty.svg'
// 全部图标
import assetAllNorSvg from '~/assets/img/icon/assets-all-nor.svg'
import assetAllSelSvg from '~/assets/img/icon/assets-all-sel.svg'
// 文件
import fileNorSvg from '~/assets/img/icon/file-nor.svg'
import fileSelSvg from '~/assets/img/icon/flie-white-sel.svg'
// 参数
import parameterNorSvg from '~/assets/img/icon/parameters-nor.svg'
import parameterSelSvg from '~/assets/img/icon/parameters-sel.svg'
//姿势
import postureNorSvg from '~/assets/img/icon/posture-nor.svg'
import postureSelSvg from '~/assets/img/icon/posture-sel.svg'
//特效
import effectNorSvg from '~/assets/img/icon/effects-nor.svg'
import effectSelSvg from '~/assets/img/icon/effects-sel.svg'
//风格
import styleNorSvg from '~/assets/img/icon/style-nor.svg'
import styleSelSvg from '~/assets/img/icon/style-sel.svg'
// 情绪
import emotionNorSvg from '~/assets/img/icon/emotion-nor.svg'
import emotionSelSvg from '~/assets/img/icon/emotion-sel.svg'
// 表情
import emojiNorSvg from '~/assets/img/icon/emoj-nor.svg'
import emojiSelSvg from '~/assets/img/icon/emoj-sel.svg'
import WorksLibraryAddCard from '~/components/home/WorksLibraryAddCard'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import { assetUrl } from '~/utils/assetUrl'
import { waitInfiniteScrollAppendDelay } from '~/utils/infiniteScrollDelay'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { isMergedAssetUserOwned } from '~/utils/mergedAssetSource'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import './AssetsLibraryPanel.css'

const deleteWhite = assetUrl(deleteWhiteSvg)
const editWhite = assetUrl(editWhiteSvg)
const sceneNor = assetUrl(sceneNorSvg)
const sceneSel = assetUrl(sceneSelSvg)
const characterNor = assetUrl(characterNorSvg)
const characterSel = assetUrl(characterSelSvg)
const propNor = assetUrl(propNorSvg)
const propSel = assetUrl(propSelSvg)
const recordingNor = assetUrl(recordingNorSvg)
const recordingSel = assetUrl(recordingSelSvg)
const emptyImageIconUrl = assetUrl(emptyImageIconSvg)
const assetsEmptyIconUrl = assetUrl(assetsEmptyIconSvg)
const assetAllNor = assetUrl(assetAllNorSvg)
const assetAllSel = assetUrl(assetAllSelSvg)
const fileNor = assetUrl(fileNorSvg)
const fileSel = assetUrl(fileSelSvg)
const parameterNor = assetUrl(parameterNorSvg)
const parameterSel = assetUrl(parameterSelSvg)
const postureNor = assetUrl(postureNorSvg)
const postureSel = assetUrl(postureSelSvg)
const effectNor = assetUrl(effectNorSvg)
const effectSel = assetUrl(effectSelSvg)
const styleNor = assetUrl(styleNorSvg)
const styleSel = assetUrl(styleSelSvg)
const emotionNor = assetUrl(emotionNorSvg)
const emotionSel = assetUrl(emotionSelSvg)
const emojiNor = assetUrl(emojiNorSvg)
const emojiSel = assetUrl(emojiSelSvg)

const ASSET_PAGE_SIZE = 30
const ASSET_SCROLL_LOAD_THRESHOLD_PX = 180
const ASSET_APPEND_ANIMATION_DELAY_MS = 650

type AssetItem = {
  id: string
  name: string
  type: string
  typeName: string
  thumbnail: string
  hasCover: boolean
  createdAt: string
  sourceFlag: string
  raw: MergedAssetVO
}

type AssetTab = {
  label: string
  value: string
  iconNor: string
  iconSel: string
}

type AssetFormState = {
  assetType: string
  assetName: string
  personalityDesc: string
  promptText: string
  imageUrl: string
  remark: string
}

const PLACEHOLDER_THUMB = emptyImageIconUrl

function iconPairForType(code: string): { iconNor: string; iconSel: string } {
  const normalizedCode = String(code || '').toLowerCase()
  if (code === 'reference_scene') return { iconNor: sceneNor, iconSel: sceneSel }
  if (code === 'reference_character') return { iconNor: characterNor, iconSel: characterSel }
  if (code === 'reference_prop') return { iconNor: propNor, iconSel: propSel }
  if (normalizedCode.includes('mood')) return { iconNor: emotionNor, iconSel: emotionSel }
  // 后端表情资产类型一般是 expression（表情），兼容 emoji/emoj 命名
  if (
    normalizedCode.includes('expression') ||
    normalizedCode.includes('emoji') ||
    normalizedCode.includes('emoj')
  )
    return { iconNor: emojiNor, iconSel: emojiSel }
  if (normalizedCode.includes('posture') || normalizedCode.includes('pose')) return { iconNor: postureNor, iconSel: postureSel }
  if (normalizedCode.includes('parameter') || normalizedCode.includes('camera')) return { iconNor: parameterNor, iconSel: parameterSel }
  if (normalizedCode.includes('file')) return { iconNor: fileNor, iconSel: fileSel }
  if (code === 'style') return { iconNor: styleNor, iconSel: styleSel }
  if (code === 'effect' || normalizedCode.includes('effect')) return { iconNor: effectNor, iconSel: effectSel }
  return { iconNor: recordingNor, iconSel: recordingSel }
}

function mapRowToAssetItem(row: MergedAssetVO, typeNameMap: Map<string, string>): AssetItem {
  const thumb = row.imageUrl?.trim() || ''
  const hasCover = !!thumb

  return {
    id: String(row.id),
    name: row.assetName || '未命名资产',
    type: row.assetType || '',
    typeName: typeNameMap.get(row.assetType || '') || row.assetType || '未知类型',
    thumbnail: hasCover ? thumb : PLACEHOLDER_THUMB,
    hasCover,
    createdAt: row.createTime || '',
    sourceFlag: row.sourceFlag || 'custom',
    raw: row
  }
}

function isUserOwnedAsset(asset: AssetItem): boolean {
  return isMergedAssetUserOwned(asset.sourceFlag)
}

const formatDate = (dateString: string) => {
  if (!dateString) return '--'
  const date = new Date(dateString)
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

export function AssetsLibraryPanel() {
  const [activeTab, setActiveTab] = useState<string>('all')
  const [hoveredAssetTab, setHoveredAssetTab] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')

  const [assetFormOpen, setAssetFormOpen] = useState(false)
  const [assetFormMode, setAssetFormMode] = useState<'create' | 'edit'>('create')
  const editingAssetIdRef = useRef<number | null>(null)
  const [assetSubmitting, setAssetSubmitting] = useState(false)
  const [assetCoverUploading, setAssetCoverUploading] = useState(false)
  const assetCoverInputRef = useRef<HTMLInputElement | null>(null)

  const [assetForm, setAssetForm] = useState<AssetFormState>({
    assetType: '',
    assetName: '',
    personalityDesc: '',
    promptText: '',
    imageUrl: '',
    remark: ''
  })

  const [assetTabs, setAssetTabs] = useState<AssetTab[]>([
    { label: '全部', value: 'all', iconNor: sceneNor, iconSel: sceneSel }
  ])

  const [assetTypes, setAssetTypes] = useState<UserAssetCustomTypeItem[]>([])
  /** 事件回调内同步读取的类型镜像（loadAssetTypesAndList 设置后立刻用于列表映射/表单默认值） */
  const assetTypesRef = useRef(assetTypes)

  const assetTypeSelectOptions = useMemo(
    () => assetTypes.map((t) => ({ value: t.code, label: t.name || t.code })),
    [assetTypes]
  )

  const [myAssets, setMyAssets] = useState<AssetItem[]>([])
  const [assetListLoading, setAssetListLoading] = useState(false)
  const [assetListLoadingMore, setAssetListLoadingMore] = useState(false)
  const [assetListHasMore, setAssetListHasMore] = useState(true)
  const [assetListInitialLoaded, setAssetListInitialLoaded] = useState(false)
  const [appendedFromIndex, setAppendedFromIndex] = useState<number | null>(null)
  const assetListScrollRef = useRef<HTMLDivElement | null>(null)
  const assetListPageRef = useRef(0)
  const assetListHasMoreRef = useRef(true)
  const assetListLoadingRef = useRef(false)
  const assetListLoadingMoreRef = useRef(false)
  const myAssetsRef = useRef<AssetItem[]>([])

  const latestFetchTokenRef = useRef(0)
  const activeTabRef = useRef(activeTab)
  const searchQueryRef = useRef(searchQuery)

  useEffect(() => {
    assetTypesRef.current = assetTypes
  }, [assetTypes])

  useEffect(() => {
    activeTabRef.current = activeTab
  }, [activeTab])

  useEffect(() => {
    searchQueryRef.current = searchQuery
  }, [searchQuery])

  const fetchAssetList = useCallback(async (options?: { preserveItems?: boolean }) => {
    const fetchToken = ++latestFetchTokenRef.current
    const preserveItems = options?.preserveItems === true
    assetListLoadingRef.current = true
    assetListLoadingMoreRef.current = false
    assetListHasMoreRef.current = true
    setAssetListLoading(true)
    setAssetListLoadingMore(false)
    setAssetListHasMore(true)
    setAppendedFromIndex(null)
    if (!preserveItems) {
      myAssetsRef.current = []
      setMyAssets([])
    }
    try {
      const { total, list } = await userAssetMergedPage({
        assetType: activeTabRef.current === 'all' ? undefined : activeTabRef.current,
        keyword: searchQueryRef.current.trim() || undefined,
        pageNum: 1,
        pageSize: ASSET_PAGE_SIZE
      })
      if (fetchToken !== latestFetchTokenRef.current) return
      const typeNameMap = new Map<string, string>()
      for (const t of assetTypesRef.current) typeNameMap.set(t.code, t.name || t.code)
      const mapped = list.map((row) => mapRowToAssetItem(row, typeNameMap))
      myAssetsRef.current = mapped
      setMyAssets(mapped)
      assetListPageRef.current = 1
      const hasMore = mapped.length > 0 && ASSET_PAGE_SIZE < total
      assetListHasMoreRef.current = hasMore
      setAssetListHasMore(hasMore)
      setAssetListInitialLoaded(true)
    } catch {
      if (fetchToken !== latestFetchTokenRef.current) return
      if (!preserveItems) {
        myAssetsRef.current = []
        setMyAssets([])
      }
      assetListHasMoreRef.current = false
      setAssetListHasMore(false)
      setAssetListInitialLoaded(true)
      message.error('查询资产列表失败，请稍后重试')
    } finally {
      if (fetchToken === latestFetchTokenRef.current) {
        assetListLoadingRef.current = false
        setAssetListLoading(false)
      }
    }
  }, [])

  const fetchMoreAssets = useCallback(async () => {
    if (
      assetListLoadingRef.current ||
      assetListLoadingMoreRef.current ||
      !assetListHasMoreRef.current
    ) {
      return
    }

    const fetchToken = latestFetchTokenRef.current
    const pageNum = assetListPageRef.current + 1
    const startedAt = Date.now()
    const appendStartIndex = myAssetsRef.current.length
    assetListLoadingMoreRef.current = true
    setAssetListLoadingMore(true)
    try {
      const { total, list } = await userAssetMergedPage({
        assetType: activeTabRef.current === 'all' ? undefined : activeTabRef.current,
        keyword: searchQueryRef.current.trim() || undefined,
        pageNum,
        pageSize: ASSET_PAGE_SIZE
      })
      await waitInfiniteScrollAppendDelay(startedAt, ASSET_APPEND_ANIMATION_DELAY_MS)
      if (fetchToken !== latestFetchTokenRef.current) return

      const typeNameMap = new Map<string, string>()
      for (const t of assetTypesRef.current) typeNameMap.set(t.code, t.name || t.code)
      const existingIds = new Set(myAssetsRef.current.map((asset) => asset.id))
      const appended = list
        .map((row) => mapRowToAssetItem(row, typeNameMap))
        .filter((asset) => !existingIds.has(asset.id))
      const nextAssets = [...myAssetsRef.current, ...appended]
      myAssetsRef.current = nextAssets
      setMyAssets(nextAssets)
      setAppendedFromIndex(appended.length > 0 ? appendStartIndex : null)
      assetListPageRef.current = pageNum
      const hasMore = list.length > 0 && pageNum * ASSET_PAGE_SIZE < total
      assetListHasMoreRef.current = hasMore
      setAssetListHasMore(hasMore)
    } catch {
      if (fetchToken !== latestFetchTokenRef.current) return
      message.error('加载更多资产失败，请稍后重试')
    } finally {
      if (fetchToken === latestFetchTokenRef.current) {
        assetListLoadingMoreRef.current = false
        setAssetListLoadingMore(false)
      }
    }
  }, [])

  const onAssetListScroll = useCallback(
    (event: ReactUIEvent<HTMLDivElement>) => {
      const target = event.currentTarget
      const distanceToBottom = target.scrollHeight - target.scrollTop - target.clientHeight
      if (distanceToBottom <= ASSET_SCROLL_LOAD_THRESHOLD_PX) {
        void fetchMoreAssets()
      }
    },
    [fetchMoreAssets]
  )

  const assetsEmpty = assetListInitialLoaded && !assetListLoading && myAssets.length === 0

  /** 首屏由 loadAssetTypesAndList 拉取；Tab/搜索变化再请求，避免与挂载叠成双请求 */
  const assetsBootstrappedRef = useRef(false)

  const loadAssetTypesAndList = useCallback(async () => {
    try {
      const types = await userAssetCustomTypeList()
      assetTypesRef.current = types
      setAssetTypes(types)
      const tabs = [{ label: '全部', value: 'all', iconNor: assetAllNor, iconSel: assetAllSel }]
      for (const t of types) {
        const ico = iconPairForType(t.code)
        tabs.push({ label: t.name || t.code, value: t.code, iconNor: ico.iconNor, iconSel: ico.iconSel })
      }
      setAssetTabs(tabs)
      if (activeTabRef.current !== 'all' && !tabs.some((t) => t.value === activeTabRef.current)) {
        activeTabRef.current = 'all'
        setActiveTab('all')
      }
    } catch {
      message.error('查询资产类型失败，请稍后重试')
    } finally {
      await fetchAssetList()
      assetsBootstrappedRef.current = true
    }
  }, [fetchAssetList])

  useEffect(() => {
    void loadAssetTypesAndList()
  }, [loadAssetTypesAndList])

  const prevActiveTabRef = useRef(activeTab)
  useEffect(() => {
    if (prevActiveTabRef.current === activeTab) return
    prevActiveTabRef.current = activeTab
    if (!assetsBootstrappedRef.current) return
    void fetchAssetList()
  }, [activeTab, fetchAssetList])

  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const prevSearchQueryRef = useRef(searchQuery)
  useEffect(() => {
    if (prevSearchQueryRef.current === searchQuery) return
    prevSearchQueryRef.current = searchQuery
    if (!assetsBootstrappedRef.current) return
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    searchTimerRef.current = setTimeout(() => {
      void fetchAssetList()
    }, 300)
  }, [searchQuery, fetchAssetList])

  useEffect(() => {
    return () => {
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current)
        searchTimerRef.current = null
      }
    }
  }, [])

  function openAssetPreview(asset: AssetItem) {
    const imageUrl = asset.raw.imageUrl?.trim() || (asset.hasCover ? asset.thumbnail : '')
    if (!imageUrl) {
      message.info('该资产暂无图片可预览')
      return
    }
    openImagePreviewModal({
      url: imageUrl,
      title: asset.name
    })
  }

  function resetAssetForm() {
    setAssetForm({
      assetType: assetTypesRef.current[0]?.code || '',
      assetName: '',
      personalityDesc: '',
      promptText: '',
      imageUrl: '',
      remark: ''
    })
  }

  function openCreateModal() {
    setAssetFormMode('create')
    editingAssetIdRef.current = null
    setAssetCoverUploading(false)
    resetAssetForm()
    setAssetFormOpen(true)
  }

  async function openEditModal(asset: AssetItem) {
    if (!isUserOwnedAsset(asset)) {
      message.info('官方素材不可编辑')
      return
    }
    const idNum = Number(asset.id)
    if (!Number.isFinite(idNum) || idNum <= 0) {
      message.error('资产ID无效，无法编辑')
      return
    }
    try {
      const detail = await userAssetCustomDetail({ id: idNum })
      setAssetFormMode('edit')
      editingAssetIdRef.current = idNum
      setAssetForm({
        assetType: detail.assetType || '',
        assetName: detail.assetName || '',
        personalityDesc: detail.personalityDesc || '',
        promptText: detail.promptText || '',
        imageUrl: detail.imageUrl || '',
        remark: detail.remark || ''
      })
      setAssetCoverUploading(false)
      if (assetCoverInputRef.current) assetCoverInputRef.current.value = ''
      setAssetFormOpen(true)
    } catch (err: any) {
      message.error(err?.msg || err?.message || '加载资产详情失败')
    }
  }

  function closeAssetFormModal() {
    setAssetFormOpen(false)
    setAssetCoverUploading(false)
    if (assetCoverInputRef.current) assetCoverInputRef.current.value = ''
  }

  function triggerAssetCoverUpload() {
    if (assetCoverUploading) return
    assetCoverInputRef.current?.click()
  }

  function clearAssetCover() {
    setAssetForm((f) => ({ ...f, imageUrl: '' }))
    if (assetCoverInputRef.current) assetCoverInputRef.current.value = ''
  }

  async function onAssetCoverFileChange(event: ChangeEvent<HTMLInputElement>) {
    const input = event.target
    const file = input.files?.[0]
    input.value = ''
    if (!file) return

    if (!file.type.startsWith('image/')) {
      message.warning('只能上传图片文件')
      return
    }
    if (file.size / 1024 / 1024 >= 10) {
      message.warning('图片大小不能超过 10MB')
      return
    }

    setAssetCoverUploading(true)
    try {
      const url = await uploadImageToOssWithToast(file)
      if (url) setAssetForm((f) => ({ ...f, imageUrl: url }))
    } finally {
      setAssetCoverUploading(false)
    }
  }

  function validateAssetForm(): string | null {
    if (assetCoverUploading) return '图片正在上传，请稍候'
    if (assetFormMode === 'create' && !assetForm.imageUrl.trim()) return '请上传资产图片'
    if (!assetForm.assetType.trim()) return '请选择资产类型'
    if (!assetForm.assetName.trim()) return '请填写资产名称'
    return null
  }

  async function submitAssetForm() {
    const errText = validateAssetForm()
    if (errText) {
      message.warning(errText)
      return
    }
    setAssetSubmitting(true)
    try {
      const payload = {
        assetType: assetForm.assetType.trim(),
        assetName: assetForm.assetName.trim(),
        personalityDesc: assetForm.personalityDesc.trim() || undefined,
        promptText: assetForm.promptText.trim() || undefined,
        imageUrl: assetForm.imageUrl.trim() || undefined,
        remark: assetForm.remark.trim() || undefined
      }
      if (assetFormMode === 'create') {
        await userAssetCustomCreate(payload)
        message.success('创建成功')
      } else {
        if (!editingAssetIdRef.current) throw new Error('缺少资产ID')
        await userAssetCustomUpdate({
          id: editingAssetIdRef.current,
          assetName: payload.assetName,
          personalityDesc: payload.personalityDesc,
          promptText: payload.promptText,
          imageUrl: payload.imageUrl,
          remark: payload.remark
        })
        message.success('修改成功')
      }
      setAssetFormOpen(false)
      await fetchAssetList({ preserveItems: true })
    } catch (err: any) {
      message.error(err?.msg || err?.message || (assetFormMode === 'create' ? '创建失败' : '修改失败'))
    } finally {
      setAssetSubmitting(false)
    }
  }

  function removeAsset(asset: AssetItem) {
    if (!isUserOwnedAsset(asset)) {
      message.info('官方素材不可删除')
      return
    }
    Modal.confirm({
      className: 'home-confirm-modal',
      wrapClassName: 'create-flow-modal home-confirm-wrap',
      centered: true,
      title: '删除资产',
      content: `确认删除「${asset.name}」吗？`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      async onOk() {
        try {
          await userAssetCustomDelete({ id: Number(asset.id) })
          message.success('删除成功')
          await fetchAssetList({ preserveItems: true })
        } catch (err: any) {
          message.error(err?.msg || err?.message || '删除失败')
        }
      }
    })
  }

  return (
    <div className="assets-page home-new-sub-page assets-library-figma">
      <div className="page-content assets-library-figma__inner">
        <header className="works-lib-header">
          <h1 className="works-lib-header__title">资产库</h1>
        </header>
        <section className="works-lib-toolbar assets-lib-toolbar" aria-label="分类与搜索">
          <div className="works-lib-type-tabs" role="tablist">
            {assetTabs.map((tab) => (
              <button
                key={tab.value}
                type="button"
                role="tab"
                aria-selected={activeTab === tab.value}
                className={`works-lib-type-tabs__btn assets-lib-tab${
                  activeTab === tab.value ? ' is-active' : ''
                }`}
                onClick={() => setActiveTab(tab.value)}
                onMouseEnter={() => setHoveredAssetTab(tab.value)}
                onMouseLeave={() => setHoveredAssetTab(null)}
              >
                <img
                  className="assets-lib-tab__ico"
                  src={
                    activeTab === tab.value || hoveredAssetTab === tab.value
                      ? tab.iconSel
                      : tab.iconNor
                  }
                  alt=""
                  width={20}
                  height={20}
                  draggable={false}
                />
                {tab.label}
              </button>
            ))}
          </div>
          <div className="works-lib-search">
            <SearchOutlined className="works-lib-search__ico" />
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              type="search"
              className="works-lib-search__input"
              placeholder="搜索资产..."
              autoComplete="off"
            />
          </div>
        </section>

        <div
          ref={assetListScrollRef}
          className="works-lib-grid"
          onScroll={onAssetListScroll}
        >
          {myAssets.length > 0 ? (
            <WorksLibraryAddCard
              label="新增资产"
              hint="创建用户自定义参考资产"
              onClick={openCreateModal}
            />
          ) : null}
          {myAssets.map((asset, index) => (
            <article
              key={asset.id}
              className={`works-lib-card${
                appendedFromIndex !== null && index >= appendedFromIndex
                  ? ' assets-lib-card--appended'
                  : ''
              }`}
              style={
                appendedFromIndex !== null && index >= appendedFromIndex
                  ? ({
                      '--asset-append-order': Math.min(index - appendedFromIndex, 8)
                    } as CSSProperties)
                  : undefined
              }
              onClick={() => openAssetPreview(asset)}
            >
              <div
                className={`works-lib-card__cover${
                  !asset.hasCover ? ' works-lib-card__cover--placeholder' : ''
                }`}
              >
                {asset.hasCover ? (
                  <ShimmerImage
                    src={asset.thumbnail}
                    alt={asset.name}
                    imgClass="works-lib-card__cover-img"
                    wrapperClass="works-lib-card__cover-shimmer"
                    objectFit="cover"
                    revealDirection="fade"
                    minShimmerMs={280}
                  />
                ) : (
                  <img
                    className="card-cover-placeholder-icon"
                    src={emptyImageIconUrl}
                    alt=""
                    width={88}
                    height={88}
                    draggable={false}
                  />
                )}
                <div className="works-lib-card__cover-actions">
                  {isUserOwnedAsset(asset) ? (
                    <button
                      type="button"
                      className="works-lib-card__cover-btn"
                      aria-label="删除"
                      onClick={(e) => {
                        e.stopPropagation()
                        removeAsset(asset)
                      }}
                    >
                      <img src={deleteWhite} alt="" />
                    </button>
                  ) : null}
                  {isUserOwnedAsset(asset) ? (
                    <button
                      type="button"
                      className="works-lib-card__cover-btn"
                      aria-label="编辑"
                      onClick={(e) => {
                        e.stopPropagation()
                        void openEditModal(asset)
                      }}
                    >
                      <img src={editWhite} alt="" />
                    </button>
                  ) : null}
                </div>
              </div>
              <div className="works-lib-card__body">
                <h3 className="works-lib-card__title">
                  <span>{asset.name}</span>
                </h3>
                <div className="works-lib-card__row works-lib-card__row--asset">
                  <span className="works-lib-card__from">类型: {asset.typeName}</span>
                  <div className="works-lib-card__row-trailing">
                    <span className="works-lib-card__updated">{formatDate(asset.createdAt)}</span>
                  </div>
                </div>
              </div>
            </article>
          ))}

          {assetsEmpty ? (
            <div className="works-lib-empty works-lib-empty--full">
              <img
                className="works-lib-empty__icon-img empty-image-icon empty-image-icon--lg"
                src={assetsEmptyIconUrl}
                alt=""
              />
              <h3 className="works-lib-empty__title">暂无资产</h3>
              <button type="button" className="works-lib-empty__btn" onClick={openCreateModal}>
                <div className="text-gradient">新增资产</div>
              </button>
            </div>
          ) : null}

          {!assetsEmpty ? (
            <div className="assets-lib-list-footer">
              <InfiniteScrollLoadFooter
                loading={(assetListLoading && myAssets.length === 0) || assetListLoadingMore}
                hasMore={assetListHasMore}
                hasItems={myAssets.length > 0}
                loadingText={assetListLoadingMore ? '正在加载更多资产…' : '正在加载资产…'}
              />
            </div>
          ) : null}
        </div>
      </div>

      <Modal
        open={assetFormOpen}
        footer={null}
        width="50%"
        wrapClassName="create-flow-modal asset-form-modal-wrap"
        onCancel={closeAssetFormModal}
        title={
          <ModalTitleWatermark
            title={assetFormMode === 'create' ? '新增资产' : '编辑资产'}
            watermark="ASSET"
          />
        }
      >
        <div className="asset-form-modal">
          <Form layout="vertical" className="asset-form-modal__grid">
            <Form.Item
              required={assetFormMode === 'create'}
              className="asset-form-item asset-form-item--full asset-form-item--upload"
            >
              <div className="style-cover-upload style-cover-upload--header">
                <input
                  ref={assetCoverInputRef}
                  type="file"
                  accept="image/*"
                  hidden
                  onChange={onAssetCoverFileChange}
                />
                <button
                  type="button"
                  className={`style-cover-upload__box${
                    assetForm.imageUrl ? ' style-cover-upload__box--filled' : ''
                  }`}
                  disabled={assetCoverUploading}
                  onClick={triggerAssetCoverUpload}
                >
                  {assetForm.imageUrl ? (
                    <ShimmerImage
                      src={assetForm.imageUrl}
                      alt="资产图片"
                      wrapperClass="style-cover-upload__preview"
                      imgClass="style-cover-upload__img"
                      objectFit="cover"
                      revealDirection="fade"
                      minShimmerMs={280}
                    />
                  ) : assetCoverUploading ? (
                    <span className="style-cover-upload__hint">上传中…</span>
                  ) : (
                    <span className="style-cover-upload__placeholder">
                      <PlusOutlined className="style-cover-upload__icon" />
                      <span>
                        {assetFormMode === 'create' ? (
                          <span className="style-cover-upload__required" aria-hidden="true">
                            *
                          </span>
                        ) : null}
                        上传图片
                      </span>
                    </span>
                  )}
                </button>
                <div className="style-cover-upload__meta">
                  <p className="style-cover-upload__tip">
                    {assetFormMode === 'create' ? '资产图片为必填项，' : ''}
                    支持 JPG、PNG，最大 10MB
                  </p>
                  {assetForm.imageUrl ? (
                    <a
                      className="style-cover-upload__remove"
                      onClick={(e: ReactMouseEvent<HTMLAnchorElement>) => {
                        e.preventDefault()
                        clearAssetCover()
                      }}
                    >
                      移除图片
                    </a>
                  ) : null}
                </div>
              </div>
            </Form.Item>
            <Form.Item label="资产类型" required className="asset-form-item asset-form-item--half">
              <Select
                value={assetForm.assetType || undefined}
                disabled={assetFormMode === 'edit'}
                placeholder="类型"
                options={assetTypeSelectOptions}
                classNames={{ popup: { root: 'asset-form-select-dropdown' } }}
                onChange={(v: string) => setAssetForm((f) => ({ ...f, assetType: v }))}
              />
            </Form.Item>
            <Form.Item label="资产名称" required className="asset-form-item asset-form-item--half">
              <Input
                value={assetForm.assetName}
                onChange={(e) => {
                  const v = e.target.value
                  setAssetForm((f) => ({ ...f, assetName: v }))
                }}
                maxLength={100}
                placeholder="请输入"
              />
            </Form.Item>
            <Form.Item label="特征描述" className="asset-form-item asset-form-item--full">
              <Input.TextArea
                value={assetForm.personalityDesc}
                onChange={(e) => {
                  const v = e.target.value
                  setAssetForm((f) => ({ ...f, personalityDesc: v }))
                }}
                rows={4}
                maxLength={500}
                placeholder="请输入"
              />
            </Form.Item>
            <Form.Item label="提示词" className="asset-form-item asset-form-item--full">
              <Input.TextArea
                value={assetForm.promptText}
                onChange={(e) => {
                  const v = e.target.value
                  setAssetForm((f) => ({ ...f, promptText: v }))
                }}
                rows={4}
                maxLength={3000}
                placeholder="请输入"
              />
            </Form.Item>
            <Form.Item label="备注" className="asset-form-item asset-form-item--full">
              <Input
                value={assetForm.remark}
                onChange={(e) => {
                  const v = e.target.value
                  setAssetForm((f) => ({ ...f, remark: v }))
                }}
                maxLength={500}
                placeholder="请输入"
              />
            </Form.Item>
          </Form>

          <div className="asset-form-modal__footer">
            <Button onClick={closeAssetFormModal}>
              <div className="text-gradient">取消</div>
            </Button>
            <Button
              type="primary"
              loading={assetSubmitting}
              disabled={assetCoverUploading}
              onClick={() => void submitAssetForm()}
            >
              {assetFormMode === 'create' ? '确定' : '保存修改'}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}

export default AssetsLibraryPanel
