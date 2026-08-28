'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Modal, Tooltip, message } from 'antd'
import { CloseOutlined } from '@ant-design/icons'
import dialogSelectNorRaw from '@/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelRaw from '@/assets/img/icon/dialog-select-sel.svg'
import starWhiteRaw from '@/assets/img/icon/star_white.svg'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { assetUrl } from '~/utils/assetUrl'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import type { DubbingPanel, StoryboardVideoPanel, StoryboardPanel } from '~/types'
import type { UserStoryboardListRow } from '~/types/business-api'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { userStoryboardList } from '~/utils/businessApi'
import {
  resolveBatchDubbingCardSpeakerMeta,
  storyboardRowHasDubbingDialogue,
  storyboardRowNeedsNoDubbing
} from '~/utils/storyboardDubbingSpeaker'
import { mapStoryboardListRowToPanel } from '~/utils/storyboardPanelMap'
import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import './BatchRegenerateDubbingModal.css'

const dialogSelectNorIcon = assetUrl(dialogSelectNorRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelRaw)
const starWhiteIconUrl = assetUrl(starWhiteRaw)
const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export interface BatchRegenerateDubbingModalProps {
  open: boolean
  panels: DubbingPanel[]
  scriptPanels?: StoryboardPanel[]
  videoPanels?: StoryboardVideoPanel[]
  sceneCharacters?: string[]
  title?: string
  /** 打开时是否默认全选可选项 */
  preselectAll?: boolean
  overwrite?: boolean
  onOpenChange: (open: boolean) => void
  onBatchGenerate: (
    selectedPanelIds: string[],
    options: {
      overwrite?: boolean
    }
  ) => void
}

export function BatchRegenerateDubbingModal({
  open,
  panels,
  scriptPanels = [],
  videoPanels = [],
  sceneCharacters: _sceneCharacters = [],
  title = '批量生成分镜配音',
  preselectAll = false,
  overwrite = false,
  onOpenChange,
  onBatchGenerate
}: BatchRegenerateDubbingModalProps) {
  const modalTitle = title

  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [listLoading, setListLoading] = useState(false)
  const modalOpenInitGenRef = useRef(0)
  /** 卡片权威数据源：POST /api/user/storyboard/list */
  const [storyboardRowsById, setStoryboardRowsById] = useState<Map<number, UserStoryboardListRow>>(
    () => new Map()
  )
  /** 打开弹窗后以 /storyboard/list 刷新结果为卡片数据源（与批量分镜弹窗一致） */
  const [refreshedDubbingPanels, setRefreshedDubbingPanels] = useState<DubbingPanel[] | null>(null)
  const [refreshedScriptPanels, setRefreshedScriptPanels] = useState<StoryboardPanel[] | null>(
    null
  )
  const [refreshedVideoPanels, setRefreshedVideoPanels] = useState<StoryboardVideoPanel[] | null>(
    null
  )
  const [billingContext, setBillingContext] = useState<{
    projectId: number
    episodeId: number
  } | null>(null)

  const effectivePanels = refreshedDubbingPanels ?? panels
  const effectiveScriptPanels = refreshedScriptPanels ?? scriptPanels
  const effectiveVideoPanels = refreshedVideoPanels ?? videoPanels

  function resetModalSessionState() {
    setSelectedIds(new Set())
    setStoryboardRowsById(new Map())
    setRefreshedDubbingPanels(null)
    setRefreshedScriptPanels(null)
    setRefreshedVideoPanels(null)
    setBillingContext(null)
  }

  function resolveStoryboardIdForPanel(panel: DubbingPanel, panelIndex: number): number | null {
    const fromScript = parseServerStoryboardId(effectiveScriptPanels?.[panelIndex]?.id ?? '')
    if (fromScript != null) return fromScript
    return parseServerStoryboardId(panel.id)
  }

  function hasApiListLoaded(): boolean {
    return storyboardRowsById.size > 0
  }

  /** 本地视频面板 / 配音面板上的封面回退 */
  function pickLocalStoryboardVideoUrl(panelIndex: number): string {
    const panel = effectivePanels[panelIndex]
    if (panel?.dubbingLipSyncVideoUrl) return panel.dubbingLipSyncVideoUrl
    const fromPanel = effectiveVideoPanels?.[panelIndex]
    const url = String(fromPanel?.finalVideoUrl ?? '').trim()
    if (url) return url
    const main = fromPanel?.videos?.find((v) => v.isStoryboardVideo && String(v.url ?? '').trim())
    return String(main?.url ?? '').trim()
  }

  function hasLocalStoryboardSourceVideo(panelIndex: number): boolean {
    const fromPanel = effectiveVideoPanels?.[panelIndex]
    if (String(fromPanel?.finalVideoUrl ?? '').trim()) return true
    if (fromPanel?.videos?.some((v) => v.isStoryboardVideo && String(v.url ?? '').trim())) return true
    return !!String(effectivePanels[panelIndex]?.dubbingLipSyncVideoUrl ?? '').trim()
  }

  /** 卡片封面：优先分镜原视频，其次配音主视频（finalComposeVideoUrl），最后本地缓存 */
  function pickStoryboardVideoUrl(storyboardId: number | null, panelIndex: number): string {
    if (storyboardId != null && hasApiListLoaded()) {
      const row = storyboardRowsById.get(storyboardId)
      if (row) {
        const fromSourceVideo = String(row.finalVideoUrl ?? '').trim()
        if (fromSourceVideo) return fromSourceVideo
        const fromCompose = String(row.finalComposeVideoUrl ?? '').trim()
        if (fromCompose) return fromCompose
      }
      // API 已加载但该行无 URL（字段滞后 / 未入选）时回退本地视频面板
      return pickLocalStoryboardVideoUrl(panelIndex)
    }
    return pickLocalStoryboardVideoUrl(panelIndex)
  }

  /** 是否已有分镜原视频（批量配音前置条件） */
  function hasStoryboardSourceVideo(storyboardId: number | null, panelIndex: number): boolean {
    if (storyboardId != null && hasApiListLoaded()) {
      const row = storyboardRowsById.get(storyboardId)
      if (row) {
        if (String(row.finalVideoUrl ?? '').trim()) return true
        if (row.finalVideoId != null && Number(row.finalVideoId) > 0) return true
        if (String(row.finalComposeVideoUrl ?? '').trim()) return true
      }
      return hasLocalStoryboardSourceVideo(panelIndex)
    }
    return hasLocalStoryboardSourceVideo(panelIndex)
  }

  function resolveDisabledTooltip(item: {
    hasSourceVideo: boolean
    needsNoDubbing: boolean
    hasDialogue: boolean
  }): string {
    if (!item.hasSourceVideo) return '暂无分镜视频'
    if (item.needsNoDubbing) return '无需配音'
    if (!item.hasDialogue) return '无台词，暂不支持生成配音'
    return ''
  }

  function isStoryboardDubbingDone(storyboardId: number | null, panel: DubbingPanel): boolean {
    if (storyboardId != null && hasApiListLoaded()) {
      const row = storyboardRowsById.get(storyboardId)
      if (!row) return false
      return !!String(row.finalComposeVideoUrl ?? '').trim()
    }
    if (panel.status === 'done') return true
    if (String(panel.dubbingLipSyncVideoUrl ?? '').trim()) return true
    return false
  }

  function formatCardTitle(title: string) {
    const match = title.match(/^分镜配音\d*[：:]\s*(.+)$/)
    if (match) return `分镜配音:${match[1]}`
    return title.replace(/：/g, ':')
  }

  const cardList = effectivePanels.map((panel, i) => {
    const storyboardId = resolveStoryboardIdForPanel(panel, i)
    const row = storyboardId != null ? storyboardRowsById.get(storyboardId) : undefined
    const scriptPanel = effectiveScriptPanels?.[i]
    const speakerMeta = resolveBatchDubbingCardSpeakerMeta({
      speakerRoles: row?.speakerRoles ?? scriptPanel?.speakerRoles,
      speakerVoices: row?.speakerVoices ?? scriptPanel?.speakerVoices,
      fallbackSpeakerRole: panel.speakerRole,
      fallbackVoiceName: panel.dubbingVoiceName
    })
    const thumbnailUrl = pickStoryboardVideoUrl(storyboardId, i)
    const hasSourceVideo = hasStoryboardSourceVideo(storyboardId, i)
    const hasDialogue =
      storyboardRowHasDubbingDialogue(row) ||
      !!String(scriptPanel?.dialogueText ?? '').trim() ||
      !!scriptPanel?.scriptContent?.trim() ||
      !!panel.dialogue?.trim()
    const needsNoDubbing = storyboardRowNeedsNoDubbing(row)
    const canSelect = hasSourceVideo && hasDialogue && !needsNoDubbing
    return {
      panel,
      panelIndex: i,
      thumbnailUrl,
      hasSourceVideo,
      hasDialogue,
      needsNoDubbing,
      speakerRole: speakerMeta.speakerRole,
      voiceName: speakerMeta.voiceName,
      isDubbingDone: isStoryboardDubbingDone(storyboardId, panel),
      storyboardId,
      canSelect,
      disabledTooltip: canSelect
        ? ''
        : resolveDisabledTooltip({ hasSourceVideo, needsNoDubbing, hasDialogue })
    }
  })

  const selectableCount = cardList.filter((c) => c.canSelect).length

  const pendingCount = cardList.filter((c) => c.canSelect && !c.isDubbingDone).length

  const isAllSelectableChecked = (() => {
    const selectable = cardList.filter((c) => c.canSelect)
    if (selectable.length === 0) return false
    return selectable.every((c) => selectedIds.has(c.panel.id))
  })()
  const quoteRequest = useMemo(() => {
    if (!open || !billingContext || selectedIds.size === 0) return null
    const storyboardIds = cardList
      .filter((item) => item.storyboardId != null && selectedIds.has(item.panel.id))
      .map((item) => item.storyboardId as number)
    if (!storyboardIds.length) return null
    return {
      quoteType: 'STORYBOARD_AUDIO_BATCH',
      payload: {
        projectId: billingContext.projectId,
        episodeId: billingContext.episodeId,
        storyboardIds,
        overwrite,
        resolution: 'FHD'
      }
    }
  }, [billingContext, cardList, open, overwrite, selectedIds])
  const batchQuote = useBillingQuote(quoteRequest)

  function toggleSelect(id: string) {
    const item = cardList.find((c) => c.panel.id === id)
    if (!item?.canSelect) return
    const next = new Set(selectedIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelectedIds(next)
  }

  function onCardClick(item: { panel: DubbingPanel; canSelect: boolean }) {
    if (item.canSelect) toggleSelect(item.panel.id)
  }

  function toggleSelectAll() {
    const selectable = cardList.filter((c) => c.canSelect).map((c) => c.panel.id)
    if (selectable.every((id) => selectedIds.has(id))) {
      const next = new Set(selectedIds)
      selectable.forEach((id) => next.delete(id))
      setSelectedIds(next)
    } else {
      const next = new Set(selectedIds)
      selectable.forEach((id) => next.add(id))
      setSelectedIds(next)
    }
  }

  async function loadModalListData(expectedGen: number) {
    setListLoading(true)
    try {
      const ctx = await resolveStoryScriptSaveContext(
        useCreationStore.getState(),
        getRouteLikeSnapshot()
      )
      if (expectedGen !== modalOpenInitGenRef.current) return null
      if (!ctx) {
        message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
        return null
      }
      setBillingContext({ projectId: ctx.projectId, episodeId: ctx.episodeId })

      // 卡片列表统一走 /api/user/storyboard/list：
      // 发言角色/配音音色（speakerRoles + speakerVoices）、原视频、配音主视频均由此接口提供
      const storyboardRows = await userStoryboardList({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId
      })
      if (expectedGen !== modalOpenInitGenRef.current) return null

      const sorted = [...storyboardRows].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
      const scriptPanels = sorted.map((row, index) => mapStoryboardListRowToPanel(row, index))
      applyStoryboardScriptPanelsFromApi(scriptPanels)

      const rowMap = new Map<number, UserStoryboardListRow>()
      for (const row of storyboardRows) {
        const sid = Number(row.id)
        if (Number.isFinite(sid) && sid > 0) rowMap.set(sid, row)
      }
      setStoryboardRowsById(rowMap)
      const fresh = useCreationStore.getState()
      const nextScriptPanels = fresh.formData.storyboardScript.panels as StoryboardPanel[]
      const nextDubbingPanels = fresh.formData.dubbing.panels as DubbingPanel[]
      const nextVideoPanels = fresh.formData.storyboardVideo.panels as StoryboardVideoPanel[]
      setRefreshedScriptPanels(nextScriptPanels)
      setRefreshedDubbingPanels(nextDubbingPanels)
      setRefreshedVideoPanels(nextVideoPanels)
      return { rowMap, dubbingPanels: nextDubbingPanels, scriptPanels: nextScriptPanels, videoPanels: nextVideoPanels }
    } catch (e: unknown) {
      if (expectedGen !== modalOpenInitGenRef.current) return null
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '获取分镜配音数据失败')
      return null
    } finally {
      if (expectedGen === modalOpenInitGenRef.current) setListLoading(false)
    }
  }

  function handleBatchGenerate() {
    if (selectedIds.size === 0 || listLoading) return
    onBatchGenerate([...selectedIds], {})
    onOpenChange(false)
  }

  function handleCancel() {
    onOpenChange(false)
  }

  async function handleModalContentInit() {
    if (!open) return
    const gen = ++modalOpenInitGenRef.current
    resetModalSessionState()
    const loaded = await loadModalListData(gen)
    if (gen !== modalOpenInitGenRef.current) return
    if (preselectAll) {
      // setState 异步：用 loadModalListData 返回的最新数据现算可选卡片，不能依赖旧 cardList
      const nextPanels = loaded?.dubbingPanels ?? panels
      const nextScripts = loaded?.scriptPanels ?? scriptPanels
      const nextVideos = loaded?.videoPanels ?? videoPanels
      const rowMap = loaded?.rowMap ?? new Map<number, UserStoryboardListRow>()
      const ids = nextPanels
        .filter((panel, i) => {
          const fromScript = parseServerStoryboardId(nextScripts?.[i]?.id ?? '')
          const storyboardId = fromScript != null ? fromScript : parseServerStoryboardId(panel.id)
          const row = storyboardId != null ? rowMap.get(storyboardId) : undefined
          const scriptPanel = nextScripts?.[i]
          const hasDialogue =
            storyboardRowHasDubbingDialogue(row) ||
            !!String(scriptPanel?.dialogueText ?? '').trim() ||
            !!scriptPanel?.scriptContent?.trim() ||
            !!panel.dialogue?.trim()
          const needsNoDubbing = storyboardRowNeedsNoDubbing(row)
          let hasSourceVideo = false
          if (storyboardId != null && rowMap.size > 0) {
            const r = rowMap.get(storyboardId)
            if (r) {
              hasSourceVideo =
                !!String(r.finalVideoUrl ?? '').trim() ||
                (r.finalVideoId != null && Number(r.finalVideoId) > 0) ||
                !!String(r.finalComposeVideoUrl ?? '').trim()
            }
          }
          if (!hasSourceVideo) {
            const fromPanel = nextVideos?.[i]
            hasSourceVideo =
              !!String(fromPanel?.finalVideoUrl ?? '').trim() ||
              !!fromPanel?.videos?.some((v) => v.isStoryboardVideo && String(v.url ?? '').trim()) ||
              !!String(panel.dubbingLipSyncVideoUrl ?? '').trim()
          }
          return hasSourceVideo && hasDialogue && !needsNoDubbing
        })
        .map((p) => p.id)
      setSelectedIds(new Set(ids))
    }
  }

  /**
   * 父级用 v-if 挂载时 open 已为 true：无 immediate 时 watch 不会触发，
   * 导致 /api/user/storyboard/list 从未请求。
   */
  useEffect(() => {
    if (!open) {
      modalOpenInitGenRef.current++
      resetModalSessionState()
      return
    }
    void handleModalContentInit()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  return (
    <Modal
      open={open}
      width={1100}
      footer={null}
      title={null}
      closable={false}
      className="batch-regenerate-dubbing-modal"
      wrapClassName="create-flow-modal batch-regenerate-dubbing-wrap"
      onCancel={handleCancel}
    >
      <div className="brdm">
        <header className="brdm-header">
          <h2 className="brdm-title">{modalTitle}</h2>
          <button type="button" className="brdm-close" aria-label="关闭" onClick={handleCancel}>
            <CloseOutlined />
          </button>
        </header>

        <div className="brdm-toolbar">
          <button type="button" className="brdm-select-all" onClick={toggleSelectAll}>
            <img
              className="brdm-check-icon"
              src={isAllSelectableChecked ? dialogSelectSelIcon : dialogSelectNorIcon}
              alt=""
            />
            <span className="brdm-select-all-text">
              全选 ({selectedIds.size}/{selectableCount})
            </span>
          </button>
          <span className="brdm-pending">待处理 ({pendingCount})</span>
        </div>

        <div className="brdm-body">
          {listLoading ? (
            <div className="brdm-loading">加载分镜数据中…</div>
          ) : (
            <div className="brdm-grid">
              {cardList.map((item) => {
                const media = (
                  <div className="brdm-card-media">
                    {item.thumbnailUrl ? (
                      <ShimmerVideo
                        src={item.thumbnailUrl}
                        videoClass="brdm-card-img"
                        objectFit="cover"
                        revealDirection="fade"
                      />
                    ) : (
                      <div className="brdm-card-media-cover">
                        <img
                          src={emptyImageIconUrl}
                          className="empty-image-icon empty-image-icon--xl brdm-card-placeholder-img"
                          alt=""
                        />
                      </div>
                    )}
                    {item.canSelect ? (
                      <img
                        className="brdm-card-select"
                        src={
                          selectedIds.has(item.panel.id)
                            ? dialogSelectSelIcon
                            : dialogSelectNorIcon
                        }
                        alt=""
                      />
                    ) : null}
                  </div>
                )

                const meta = (
                  <div className="brdm-card-meta">
                    <div className="brdm-card-name">{formatCardTitle(item.panel.title)}</div>

                    {item.needsNoDubbing ? (
                      <div className="brdm-card-field brdm-card-field--static brdm-card-field--no-dubbing">
                        无需配音
                      </div>
                    ) : item.hasDialogue ? (
                      <>
                        <div className="brdm-card-field brdm-card-field--static">
                          <span className="brdm-field-label">发言角色：</span>
                          <span className="brdm-field-value">{item.speakerRole}</span>
                        </div>
                        <div className="brdm-card-field brdm-card-field--static">
                          <span className="brdm-field-label">配音音色：</span>
                          <span className="brdm-field-value">
                            <span className="brdm-voice-placeholder" />
                            {item.voiceName}
                          </span>
                        </div>
                      </>
                    ) : (
                      <>
                        <div className="brdm-card-field brdm-card-field--static">
                          <span className="brdm-field-label">发言角色：暂无</span>
                        </div>
                        <p className="brdm-no-dialogue-tip">无台词，暂不支持生成配音</p>
                      </>
                    )}
                  </div>
                )

                if (!item.canSelect) {
                  return (
                    <Tooltip key={item.panel.id} title={item.disabledTooltip}>
                      <article className="brdm-card brdm-card--disabled">
                        {media}
                        {meta}
                      </article>
                    </Tooltip>
                  )
                }

                return (
                  <article
                    key={item.panel.id}
                    className={`brdm-card${
                      selectedIds.has(item.panel.id) ? ' brdm-card--selected' : ''
                    }`}
                    onClick={() => onCardClick(item)}
                  >
                    {media}
                    {meta}
                  </article>
                )
              })}
            </div>
          )}
        </div>

        <footer className="brdm-footer">
          <BillingQuoteHint
            quote={batchQuote.quote}
            loading={batchQuote.loading}
            error={batchQuote.error}
            active={Boolean(quoteRequest)}
          />
          <Button
            type="primary"
            className="brdm-submit-btn"
            disabled={selectedIds.size === 0 || listLoading}
            icon={<img src={starWhiteIconUrl} alt="" />}
            onClick={handleBatchGenerate}
          >
            批量生成
          </Button>
        </footer>
      </div>
    </Modal>
  )
}

export default BatchRegenerateDubbingModal
