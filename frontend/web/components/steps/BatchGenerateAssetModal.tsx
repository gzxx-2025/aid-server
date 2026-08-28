'use client'

import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Modal, Button, Tooltip, message } from 'antd'
import { CloseOutlined } from '@ant-design/icons'
import { assetUrl } from '~/utils/assetUrl'
import dialogSelectNorRaw from '@/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelRaw from '@/assets/img/icon/dialog-select-sel.svg'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import { useCreationStore } from '~/stores/creation'
import {
  CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY,
  FORM_GENERATE_AGENT_BIZ_CATEGORY,
  FORM_IMAGE_AGENT_BIZ_CATEGORY
} from '~/utils/extractAgentBiz'
import { getProjectGenConfigBySceneCode } from '~/utils/projectGenConfig'
import './BatchGenerateAssetModal.css'

const dialogSelectNorIcon = assetUrl(dialogSelectNorRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelRaw)
const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export type BatchAssetType = 'scene' | 'character' | 'prop'
export type BatchAssetMode = 'image' | 'setting-card' | 'form' | 'delete'

export interface BatchAssetItem {
  id?: string | number
  name?: string
  /** 已有场景/角色/道具设定（富文本非空），无预览图时仍可勾选批量生成 */
  hasSetting?: boolean
  /** 设定卡模式：是否已有可生成的白底角色主图 */
  settingCardReady?: boolean
  /** 未传时沿用既有生图/设定卡推导；表单生成与删除由调用方明确给出。 */
  selectable?: boolean
  disabledTooltip?: string
  /** 对应真实提交体里的 assetId/formId/imageId；与展示用 id 分离。 */
  billingIds?: number[]
  images?: Array<{
    url?: string
    thumbnail?: string
    importDate?: string
    updatedAt?: string
    createdAt?: string
  }>
}

export interface BatchGenerateAssetConfirmPayload {
  type: BatchAssetType
  mode: BatchAssetMode
  agent: string
  model: string
  resolution: string
  selectedItemIds: Array<string | number>
}

interface Props {
  open: boolean
  type: BatchAssetType
  /** image：批量生图；setting-card：批量生成设定卡（仅角色） */
  mode?: BatchAssetMode
  items?: BatchAssetItem[]
  /** 智能提取弹窗已选 modelCode，打开时优先回显 */
  defaultModelCode?: string
  /** 打开弹窗时刷新资产列表（拉取 /api/user/asset/rps/list） */
  onRefreshItems?: () => Promise<void>
  onOpenChange: (value: boolean) => void
  onConfirm?: (payload: BatchGenerateAssetConfirmPayload) => void | Promise<void>
}

function formatDate(str: string) {
  if (!str) return '--'
  const d = new Date(str)
  if (Number.isNaN(d.getTime())) return '--'
  return d.toLocaleDateString('zh-CN')
}

export function BatchGenerateAssetModal({
  open,
  type,
  mode = 'image',
  items = [],
  defaultModelCode = '',
  onRefreshItems,
  onOpenChange,
  onConfirm
}: Props) {
  const isSettingCardMode = mode === 'setting-card'
  const needsGenerationConfig = mode === 'image' || mode === 'setting-card'

  const [selectedIds, setSelectedIds] = useState<Array<string | number>>([])
  const selectedIdsRef = useRef<Array<string | number>>([])
  const updateSelectedIds = (next: Array<string | number>) => {
    selectedIdsRef.current = next
    setSelectedIds(next)
  }
  const [listLoading, setListLoadingState] = useState(false)
  const listLoadingRef = useRef(false)
  const setListLoading = (v: boolean) => {
    listLoadingRef.current = v
    setListLoadingState(v)
  }
  const [confirmLoading, setConfirmLoadingState] = useState(false)
  const confirmLoadingRef = useRef(false)
  const setConfirmLoading = (v: boolean) => {
    confirmLoadingRef.current = v
    setConfirmLoadingState(v)
  }
  /** 打开弹窗期间的刷新代数，避免快速开关时旧请求回写 */
  const modalOpenInitGenRef = useRef(0)
  /** 本次打开是否已完成接口刷新；刷新前不展示本地缓存封面 */
  const [listSynced, setListSyncedState] = useState(false)
  const [billingDefaults, setBillingDefaults] = useState({
    agent: '',
    model: '',
    resolution: '',
    aspectRatio: ''
  })
  const listSyncedRef = useRef(false)
  const setListSynced = (v: boolean) => {
    listSyncedRef.current = v
    setListSyncedState(v)
  }

  const propsRef = useRef({ open, type, mode, defaultModelCode, onRefreshItems })
  propsRef.current = { open, type, mode, defaultModelCode, onRefreshItems }

  function resolveBizCategoryCode(): string {
    if (propsRef.current.mode === 'setting-card') return CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY
    if (propsRef.current.mode === 'form') {
      return FORM_GENERATE_AGENT_BIZ_CATEGORY[propsRef.current.type]
    }
    return FORM_IMAGE_AGENT_BIZ_CATEGORY[propsRef.current.type]
  }

  useEffect(() => {
    let cancelled = false
    setBillingDefaults({ agent: '', model: '', resolution: '', aspectRatio: '' })
    if (!open || mode === 'delete') return
    const projectId = Number(useCreationStore.getState().currentProjectId)
    if (!Number.isFinite(projectId) || projectId <= 0) return
    const sceneCode = resolveBizCategoryCode()
    void getProjectGenConfigBySceneCode(projectId, sceneCode)
      .then((cfg) => {
        if (cancelled) return
        setBillingDefaults({
          agent: String(cfg?.agentCode || '').trim(),
          model:
            String(cfg?.modelCode || '').trim() ||
            String(defaultModelCode || '').trim(),
          resolution: String(cfg?.resolution || '').trim(),
          aspectRatio: String(cfg?.aspectRatio || '').trim()
        })
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
    // resolveBizCategoryCode reads the same primitive props listed here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultModelCode, mode, open, type])

  async function loadModalListData() {
    const refresh = propsRef.current.onRefreshItems
    if (!refresh) {
      setListSynced(true)
      return
    }
    setListLoading(true)
    setListSynced(false)
    try {
      await refresh()
      setListSynced(true)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '刷新资产列表失败')
      // 刷新失败时退回当前父组件数据，避免空白不可用
      setListSynced(true)
    } finally {
      setListLoading(false)
    }
  }

  /** 打开只刷资产列表；智能体/模型走「生成配置」，不再拉 agent/list、listByFunc */
  async function handleModalContentInit() {
    if (!propsRef.current.open) return
    const gen = ++modalOpenInitGenRef.current
    updateSelectedIds([])
    await loadModalListData()
    if (gen !== modalOpenInitGenRef.current) return
  }

  function resetModalTransientState() {
    modalOpenInitGenRef.current++
    setListSynced(false)
    setListLoading(false)
    setConfirmLoading(false)
    updateSelectedIds([])
  }

  // 原 watch(() => props.open)
  const prevOpenRef = useRef(false)
  useEffect(() => {
    if (prevOpenRef.current === open) return
    prevOpenRef.current = open
    if (!open) {
      resetModalTransientState()
      return
    }
    void handleModalContentInit()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 原 watch(() => [props.type, props.mode])
  const prevTypeModeRef = useRef<readonly [BatchAssetType, BatchAssetMode]>([type, mode])
  useEffect(() => {
    const [pt, pm] = prevTypeModeRef.current
    if (pt === type && pm === mode) return
    prevTypeModeRef.current = [type, mode] as const
    if (!propsRef.current.open) return
    void handleModalContentInit()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, mode])

  /** 确认时读一次 gen-config（有缓存），不再拉智能体/模型列表 */
  async function resolveSubmitDefaultsFromGenConfig(): Promise<{
    agent: string
    model: string
    resolution: string
  } | null> {
    const projectId = Number(useCreationStore.getState().currentProjectId)
    const sceneCode = resolveBizCategoryCode()
    let agent = ''
    /** 生成配置优先；defaultModelCode 仅作无配置时的兜底（避免旧 extractImageModelCodes 盖住新配置） */
    let model = ''
    /** 透传生成配置清晰度；勿写死 4k，也不要只认 1k/2k/4k（Image2 等为 1024x1024） */
    let resolution = ''
    if (Number.isFinite(projectId) && projectId > 0) {
      try {
        const cfg = await getProjectGenConfigBySceneCode(projectId, sceneCode)
        agent = String(cfg?.agentCode || '').trim()
        model =
          String(cfg?.modelCode || '').trim() ||
          String(propsRef.current.defaultModelCode || '').trim()
        resolution = String(cfg?.resolution || '').trim()
      } catch {
        /* ignore：交给下方空 agent 提示 */
      }
    }
    if (!model) model = String(propsRef.current.defaultModelCode || '').trim()
    if (!agent) {
      message.warning(
        propsRef.current.mode === 'setting-card'
          ? '请先在「生成配置」中为「角色设定卡」配置智能体'
          : '请先在「生成配置」中配置形态图智能体'
      )
      return null
    }
    return { agent, model, resolution }
  }

  const modalTitle = (() => {
    const typeLabel = type === 'scene' ? '场景' : type === 'character' ? '角色' : '道具'
    if (mode === 'form') return `批量生成${typeLabel}形态`
    if (mode === 'delete') return `批量删除${typeLabel}`
    if (isSettingCardMode) return '批量生成设定卡'
    if (type === 'scene') return '批量生成场景图'
    if (type === 'character') return '批量生成角色图'
    return '批量生成道具图'
  })()

  const modalSubtitle = (() => {
    const typeLabel = type === 'scene' ? '场景' : type === 'character' ? '角色' : '道具'
    if (mode === 'form') return `请选择需要生成形态的${typeLabel}`
    if (mode === 'delete') return `请选择需要删除的${typeLabel}，删除后不可恢复`
    if (isSettingCardMode) {
      return '生成结果将更新到角色设定卡，历史记录可在生图历史中查看'
    }
    if (type === 'scene') return '生成结果将更新到场景图，历史记录可在生图历史中查看'
    if (type === 'character') return '生成结果将更新到角色图，历史记录可在生图历史中查看'
    return '生成结果将更新到道具图，历史记录可在生图历史中查看'
  })()

  const defaultName = (() => {
    if (type === 'scene') return '未命名场景'
    if (type === 'character') return '未命名角色'
    return '未命名道具'
  })()

  const normalizedItems = useMemo(
    () =>
      (items || []).map((it, idx) => {
        const first = (it.images || [])[0] || {}
        const cover = first.url || first.thumbnail || ''
        const dateRaw = first.importDate || first.updatedAt || first.createdAt || ''
        const hasSetting = Boolean(it.hasSetting)
        const settingCardReady = Boolean(it.settingCardReady)
        const selectable =
          typeof it.selectable === 'boolean'
            ? it.selectable
            : isSettingCardMode
              ? settingCardReady
              : Boolean(cover) || hasSetting
        const disabledTooltip =
          it.disabledTooltip || (isSettingCardMode && !selectable ? '请先生成角色图' : '')
        return {
          id: it.id ?? `${type}-${idx}`,
          name: it.name || '',
          cover,
          date: formatDate(dateRaw),
          selectable,
          disabledTooltip,
          billingIds: (it.billingIds || []).filter((id) => Number.isFinite(id) && id > 0)
        }
      }),
    [isSettingCardMode, items, type]
  )

  /** 接口同步完成前不展示缓存列表，与分镜批量弹窗「打开先拉 list」对齐 */
  const displayItems = listSynced ? normalizedItems : []

  const withImageCount = displayItems.filter((i) => !!i.cover).length

  const selectableCount = displayItems.filter((i) => i.selectable).length

  const isAllSelectableChecked = (() => {
    const selectable = displayItems.filter((i) => i.selectable)
    if (selectable.length === 0) return false
    return selectable.every((i) => selectedIds.includes(i.id))
  })()

  const quoteRequest = useMemo(() => {
    if (!open || mode === 'delete' || !billingDefaults.agent) return null
    const selected = new Set(selectedIds)
    const billingIds = [
      ...new Set(
        normalizedItems
          .filter((item) => selected.has(item.id))
          .flatMap((item) => item.billingIds)
      )
    ]
    if (!billingIds.length) return null
    if (mode === 'form') {
      return {
        quoteType: 'FORM_GENERATE',
        payload: {
          assetIds: billingIds,
          agentCode: billingDefaults.agent,
          ...(billingDefaults.model ? { modelCode: billingDefaults.model } : {})
        }
      }
    }
    if (mode === 'setting-card') {
      return {
        quoteType: 'FORM_CARD_IMAGE',
        payload: {
          imageIds: billingIds,
          agentCode: billingDefaults.agent,
          ...(billingDefaults.model ? { modelCode: billingDefaults.model } : {}),
          ...(billingDefaults.resolution ? { resolution: billingDefaults.resolution } : {}),
          ...(billingDefaults.aspectRatio ? { aspectRatio: billingDefaults.aspectRatio } : {})
        }
      }
    }
    return {
      quoteType: 'FORM_IMAGE',
      payload: {
        formIds: billingIds,
        agentCode: billingDefaults.agent,
        ...(billingDefaults.model ? { modelCode: billingDefaults.model } : {}),
        ...(billingDefaults.resolution ? { resolution: billingDefaults.resolution } : {}),
        ...(billingDefaults.aspectRatio ? { aspectRatio: billingDefaults.aspectRatio } : {})
      }
    }
  }, [billingDefaults, mode, normalizedItems, open, selectedIds])
  const generationQuote = useBillingQuote(quoteRequest)

  function toggleSelectAll() {
    if (listLoadingRef.current) return
    const selectableIds = displayItems.filter((i) => i.selectable).map((i) => i.id)
    if (selectableIds.every((id) => selectedIdsRef.current.includes(id))) {
      updateSelectedIds(selectedIdsRef.current.filter((id) => !selectableIds.includes(id)))
      return
    }
    const next = new Set([...selectedIdsRef.current, ...selectableIds])
    updateSelectedIds([...next])
  }

  function handleCancel() {
    if (confirmLoadingRef.current) return
    onOpenChange(false)
  }

  async function handleConfirm() {
    if (listLoadingRef.current || confirmLoadingRef.current) return
    if (selectedIdsRef.current.length === 0) return
    setConfirmLoading(true)
    try {
      const defaults = needsGenerationConfig
        ? await resolveSubmitDefaultsFromGenConfig()
        : { agent: '', model: '', resolution: '' }
      if (!defaults) return
      await onConfirm?.({
        type: propsRef.current.type,
        mode: propsRef.current.mode ?? 'image',
        agent: defaults.agent,
        model: defaults.model,
        resolution: defaults.resolution,
        selectedItemIds: [...selectedIdsRef.current]
      })
      onOpenChange(false)
    } finally {
      setConfirmLoading(false)
    }
  }

  function isSelected(id: string | number) {
    return selectedIds.includes(id)
  }

  function toggleSelect(id: string | number, selectable: boolean) {
    if (!selectable) return
    if (selectedIdsRef.current.includes(id)) {
      updateSelectedIds(selectedIdsRef.current.filter((x) => x !== id))
      return
    }
    updateSelectedIds([...selectedIdsRef.current, id])
  }

  const renderCard = (item: (typeof normalizedItems)[number]) => (
    <article
      className={[
        'bgam-card',
        isSelected(item.id) ? 'bgam-card--selected' : '',
        !item.selectable ? 'bgam-card--disabled' : ''
      ]
        .filter(Boolean)
        .join(' ')}
      onClick={() => toggleSelect(item.id, item.selectable)}
    >
      <div className="bgam-card-media">
        {item.cover ? (
          <ShimmerImage
            src={item.cover}
            alt={item.name}
            imgClass="bgam-card-img"
            objectFit="cover"
            revealDirection="fade"
          />
        ) : (
          <div className="bgam-card-empty">
            <img
              src={emptyImageIconUrl}
              alt=""
              className="empty-image-icon empty-image-icon--sm bgam-card-empty__icon"
            />
          </div>
        )}
        {item.selectable && (
          <img
            className="bgam-card-select"
            src={isSelected(item.id) ? dialogSelectSelIcon : dialogSelectNorIcon}
            alt=""
          />
        )}
      </div>
      <div className="bgam-card-meta">
        <div className="bgam-card-name">{item.name || defaultName}</div>
      </div>
    </article>
  )

  return (
    <Modal
      open={open}
      width={1100}
      footer={null}
      title={null}
      closable={false}
      className="batch-generate-asset-modal"
      wrapClassName="create-flow-modal batch-generate-asset-wrap"
      forceRender
      onCancel={handleCancel}
    >
      <div className="bgam">
        <header className="bgam-header">
          <div className="bgam-title-wrap">
            <h2 className="bgam-title">{modalTitle}</h2>
            <p className="bgam-subtitle">{modalSubtitle}</p>
          </div>
          <button
            type="button"
            className="bgam-close"
            aria-label="关闭"
            disabled={confirmLoading}
            onClick={handleCancel}
          >
            <CloseOutlined />
          </button>
        </header>

        <div className="bgam-toolbar">
          <button
            type="button"
            className="bgam-select-all"
            disabled={listLoading}
            onClick={toggleSelectAll}
          >
            <img
              className="bgam-check-icon"
              src={isAllSelectableChecked ? dialogSelectSelIcon : dialogSelectNorIcon}
              alt=""
            />
            <span className="bgam-select-all-text">
              全选 ({selectedIds.length}/{selectableCount})
            </span>
          </button>
          {listLoading ? (
            <span className="bgam-pending bgam-pending--muted">正在同步列表…</span>
          ) : (
            <span className="bgam-pending bgam-pending--muted">
              {mode === 'form' || mode === 'delete'
                ? `可选择 (${selectableCount}/${displayItems.length})`
                : `已有图片 (${withImageCount}/${displayItems.length})`}
            </span>
          )}
        </div>

        <div className="bgam-body">
          {listLoading ? (
            <div className="bgam-list-loading">正在从服务器同步资产列表…</div>
          ) : (
            <div className="bgam-grid">
              {displayItems.map((item) =>
                !item.selectable && item.disabledTooltip ? (
                  <Tooltip key={item.id} title={item.disabledTooltip}>
                    {renderCard(item)}
                  </Tooltip>
                ) : (
                  <Fragment key={item.id}>{renderCard(item)}</Fragment>
                )
              )}
            </div>
          )}
        </div>

        <footer className="bgam-footer">
          <div className="bgam-config" />
          <div className="bgam-actions">
            <BillingQuoteHint
              quote={generationQuote.quote}
              loading={generationQuote.loading}
              error={generationQuote.error}
              active={Boolean(quoteRequest)}
            />
            <Button className="bgam-btn-cancel" disabled={confirmLoading} onClick={handleCancel}>
              <div className="text-gradient">取消</div>
            </Button>
            <Button
              className="bgam-btn-ok"
              type="primary"
              loading={confirmLoading}
              disabled={selectedIds.length === 0 || listLoading || confirmLoading}
              onClick={handleConfirm}
            >
              {mode === 'delete' ? '确定' : '批量生成'}
            </Button>
          </div>
        </footer>
      </div>
    </Modal>
  )
}

export default BatchGenerateAssetModal
