'use client'

import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent, TransitionEvent as ReactTransitionEvent } from 'react'
import { Button, Modal, Tooltip, message } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import AgentPickerModal from './AgentPickerModal'
import { useCreationStore } from '~/stores/creation'
import type { ExtractAgentOption, ExtractAgents } from '~/stores/creation'
import type {
  AssetExtractType,
  BillingQuoteRequest,
  ProjectGenConfigVO,
  UserModelListItem
} from '~/types/business-api'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { assetUrl } from '~/utils/assetUrl'
import { buildAssetExtractSubmitBody } from '~/utils/assetExtractSubmitBody'
import subtractIconRaw from '~/assets/img/icon/Subtract.svg'
import starWhiteIconRaw from '~/assets/img/icon/star_white.svg'
import {
  EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY,
  agentOptionsFromGenConfigVo,
  emptyExtractModelCodes,
  pickFirstAgentOption,
  type ExtractModelCodes
} from '~/utils/extractAgentBiz'
import {
  fetchProjectGenConfigList,
  pickProjectGenModelCodeFromVo,
  resolveProjectGenConfigSceneCode
} from '~/utils/projectGenConfig'
import './ExtractAgentModal.css'

const subtractIconUrl = assetUrl(subtractIconRaw)
const starWhiteIconUrl = assetUrl(starWhiteIconRaw)

// 原 ExtractAgents / ExtractAgentOption 定义在本组件文件，现平移到 stores/creation/types；
// 此处 re-export 保持原导入路径兼容
export type { ExtractAgents, ExtractAgentOption }

/** 弹窗展示列：all 为三列；单项时仅展示对应提取模型 */
export type ExtractModalScope = 'all' | 'scene' | 'character' | 'prop'

type AgentKind = 'scene' | 'character' | 'prop'

const columnConfig: { kind: AgentKind; label: string }[] = [
  { kind: 'scene', label: '提取场景' },
  { kind: 'character', label: '提取角色' },
  { kind: 'prop', label: '提取道具' }
]

export interface ExtractAgentStartPayload {
  agents: ExtractAgents
  modelCodes: ExtractModelCodes
  manualModelPickByKind: Partial<Record<AgentKind, boolean>>
  scope: ExtractModalScope
  overwrite?: boolean
}

interface Props {
  open: boolean
  agents: ExtractAgents
  modelCodes?: ExtractModelCodes
  /** 为 all 时三列；为 scene/character/prop 时仅展示该列 */
  scope?: ExtractModalScope
  /** start=首次空库；continueOrReextract=剧本变更后继续/重新提取 */
  actionMode?: 'start' | 'continueOrReextract'
  onOpenChange: (value: boolean) => void
  onAgentsChange?: (value: ExtractAgents) => void
  onModelCodesChange?: (value: ExtractModelCodes) => void
  onStart?: (payload: ExtractAgentStartPayload) => void
}

/** 与 .extract-progress__fill 的 transition 时长一致（毫秒） */
const PROGRESS_DURATION_MS = 1400

const emptyAgent = (): ExtractAgentOption => ({ id: '', name: '', thumbnail: '', desc: '' })

function kindLabel(kind: AgentKind): string {
  if (kind === 'scene') return '场景'
  if (kind === 'character') return '角色'
  return '道具'
}

export function ExtractAgentModal({
  open,
  agents,
  modelCodes,
  scope = 'all',
  actionMode = 'start',
  onOpenChange,
  onAgentsChange,
  onModelCodesChange,
  onStart
}: Props) {
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  const currentEpisodeId = useCreationStore((state) => state.currentEpisodeId)
  const visibleColumns = scope === 'all' ? columnConfig : columnConfig.filter((c) => c.kind === scope)

  const extractModalTitle =
    scope === 'scene'
      ? '提取场景'
      : scope === 'character'
        ? '提取角色'
        : scope === 'prop'
          ? '提取道具'
          : '提取场景/角色/道具'

  const extractColumnsClass =
    visibleColumns.length === 1
      ? 'extract-columns--1'
      : visibleColumns.length === 2
        ? 'extract-columns--2'
        : 'extract-columns--3'

  const extractModalWidth = scope === 'all' ? 1100 : 420

  const extractWrapClassName =
    scope === 'all'
      ? 'create-flow-modal extract-agent-modal-wrap extract-agent-modal-wrap--all'
      : 'create-flow-modal extract-agent-modal-wrap'

  /** 原 reactive：逻辑统一改 ref，再镜像到 state 触发渲染 */
  const localAgentsRef = useRef<ExtractAgents>({
    scene: emptyAgent(),
    character: emptyAgent(),
    prop: emptyAgent()
  })
  const [localAgents, setLocalAgentsState] = useState<ExtractAgents>(() => ({
    scene: { ...localAgentsRef.current.scene },
    character: { ...localAgentsRef.current.character },
    prop: { ...localAgentsRef.current.prop }
  }))
  const syncLocalAgents = () => {
    setLocalAgentsState({
      scene: { ...localAgentsRef.current.scene },
      character: { ...localAgentsRef.current.character },
      prop: { ...localAgentsRef.current.prop }
    })
  }

  const localModelCodesRef = useRef<ExtractModelCodes>(emptyExtractModelCodes())
  const [localModelCodes, setLocalModelCodesState] = useState<ExtractModelCodes>(() => ({
    ...localModelCodesRef.current
  }))
  const syncLocalModelCodes = () => {
    setLocalModelCodesState({ ...localModelCodesRef.current })
  }

  /** 用户在 AgentPicker 内手动切换过模型时才视为 manual override */
  const userPickedModelByKindRef = useRef<Record<AgentKind, boolean>>({
    scene: false,
    character: false,
    prop: false
  })
  const [manualModelPickByKind, setManualModelPickByKind] = useState<Record<AgentKind, boolean>>({
    scene: false,
    character: false,
    prop: false
  })

  const genConfigReadyRef = useRef(false)
  const [genConfigReady, setGenConfigReadyState] = useState(false)
  const setGenConfigReady = (v: boolean) => {
    genConfigReadyRef.current = v
    setGenConfigReadyState(v)
  }

  const agentsLoadingKindRef = useRef<AgentKind | null>(null)
  const agentsByKindRef = useRef<Record<AgentKind, ExtractAgentOption[]>>({
    scene: [],
    character: [],
    prop: []
  })
  const [agentsByKind, setAgentsByKindState] = useState<Record<AgentKind, ExtractAgentOption[]>>({
    scene: [],
    character: [],
    prop: []
  })
  const modelsByKindRef = useRef<Record<AgentKind, UserModelListItem[]>>({
    scene: [],
    character: [],
    prop: []
  })
  const [modelsByKind, setModelsByKindState] = useState<Record<AgentKind, UserModelListItem[]>>({
    scene: [],
    character: [],
    prop: []
  })
  const syncAgentsAndModelsByKind = () => {
    setAgentsByKindState({ ...agentsByKindRef.current })
    setModelsByKindState({ ...modelsByKindRef.current })
  }

  // 原 watch(() => props.agents, ..., { immediate: true, deep: true })：按 JSON 指纹同步
  const agentsPropFingerprint = JSON.stringify(agents)
  useEffect(() => {
    localAgentsRef.current.scene = { ...agents.scene }
    localAgentsRef.current.character = { ...agents.character }
    localAgentsRef.current.prop = { ...agents.prop }
    syncLocalAgents()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentsPropFingerprint])

  // 原 watch(() => props.modelCodes, ..., { immediate: true, deep: true })：按 JSON 指纹同步
  const modelCodesPropFingerprint = JSON.stringify(modelCodes ?? null)
  useEffect(() => {
    localModelCodesRef.current.scene = String(modelCodes?.scene || '').trim()
    localModelCodesRef.current.character = String(modelCodes?.character || '').trim()
    localModelCodesRef.current.prop = String(modelCodes?.prop || '').trim()
    syncLocalModelCodes()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelCodesPropFingerprint])

  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerKind, setPickerKind] = useState<AgentKind>('scene')
  const pickerKindRef = useRef<AgentKind>('scene')
  const [pickerDefaultQuery, setPickerDefaultQuery] = useState('')

  const pickerAgents = agentsByKind[pickerKind] ?? []

  const pickerAvailableModels = modelsByKind[pickerKind] ?? []

  function applySelectionForKind(kind: AgentKind, list: ExtractAgentOption[], autoPickDefault: boolean) {
    if (!list.length) return
    const currentId = localAgentsRef.current[kind].id?.trim()
    const matched = currentId ? list.find((a) => a.id === currentId) : null
    if (matched) {
      localAgentsRef.current[kind] = { ...matched }
      return
    }
    if (autoPickDefault) {
      const first = pickFirstAgentOption(list)
      if (first) localAgentsRef.current[kind] = { ...first }
    }
  }

  function applyGenConfigForKind(
    kind: AgentKind,
    cfg: ProjectGenConfigVO | null,
    autoPickDefault: boolean
  ) {
    const agentList = cfg ? agentOptionsFromGenConfigVo(cfg) : []
    agentsByKindRef.current[kind] = agentList
    modelsByKindRef.current[kind] = Array.isArray(cfg?.availableModels) ? cfg.availableModels : []

    if (autoPickDefault) {
      let agentCode = String(cfg?.agentCode || '').trim()
      if (agentCode) {
        const matched = agentList.find((a) => a.id === agentCode)
        localAgentsRef.current[kind] = matched
          ? { ...matched }
          : {
              id: agentCode,
              name: agentCode,
              thumbnail: String(cfg?.agentIconUrl || '').trim(),
              desc: ''
            }
      } else {
        const first = pickFirstAgentOption(agentList)
        if (first) {
          agentCode = first.id
          localAgentsRef.current[kind] = { ...first }
        }
      }
      const modelCode = pickProjectGenModelCodeFromVo(cfg, agentCode, agentList)
      if (modelCode) localModelCodesRef.current[kind] = modelCode
      return
    }

    applySelectionForKind(kind, agentList, false)
  }

  async function loadGenConfigForKinds(kinds: AgentKind[], options?: { autoPickDefault?: boolean }) {
    if (!kinds.length) return
    const projectId = Number(useCreationStore.getState().currentProjectId)
    if (!Number.isFinite(projectId) || projectId <= 0) {
      message.warning('请先选择作品')
      setGenConfigReady(false)
      return
    }

    setGenConfigReady(false)
    agentsLoadingKindRef.current = kinds.length === 1 ? kinds[0]! : null
    try {
      const list = await fetchProjectGenConfigList(projectId, { force: true })
      for (const kind of kinds) {
        const sceneCode = EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY[kind]
        const cfg =
          list.find((row) => resolveProjectGenConfigSceneCode(row) === sceneCode) ?? null
        applyGenConfigForKind(kind, cfg, options?.autoPickDefault ?? false)
      }
      onAgentsChange?.({
        scene: { ...localAgentsRef.current.scene },
        character: { ...localAgentsRef.current.character },
        prop: { ...localAgentsRef.current.prop }
      })
      onModelCodesChange?.({
        scene: localModelCodesRef.current.scene,
        character: localModelCodesRef.current.character,
        prop: localModelCodesRef.current.prop
      })
      setGenConfigReady(true)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载生成配置失败')
      for (const kind of kinds) {
        agentsByKindRef.current[kind] = []
        modelsByKindRef.current[kind] = []
      }
      setGenConfigReady(false)
    } finally {
      agentsLoadingKindRef.current = null
      syncLocalAgents()
      syncLocalModelCodes()
      syncAgentsAndModelsByKind()
    }
  }

  const openPicker = async (kind: AgentKind) => {
    pickerKindRef.current = kind
    setPickerKind(kind)
    setPickerDefaultQuery(kindLabel(kind))
    if (!agentsByKindRef.current[kind].length) {
      await loadGenConfigForKinds([kind])
    }
    if (!agentsByKindRef.current[kind].length) {
      message.warning(`暂无可用${kindLabel(kind)}智能体`)
      return
    }
    setPickerOpen(true)
  }

  const handlePicked = (payload: { agent?: ExtractAgentOption; modelCode?: string }) => {
    if (payload.agent) {
      localAgentsRef.current[pickerKindRef.current] = { ...payload.agent }
    }
    if (payload.modelCode) {
      localModelCodesRef.current[pickerKindRef.current] = String(payload.modelCode).trim()
      const nextManualPick = {
        ...userPickedModelByKindRef.current,
        [pickerKindRef.current]: true
      }
      userPickedModelByKindRef.current = nextManualPick
      setManualModelPickByKind(nextManualPick)
    }
    syncLocalAgents()
    syncLocalModelCodes()
    onAgentsChange?.({
      scene: { ...localAgentsRef.current.scene },
      character: { ...localAgentsRef.current.character },
      prop: { ...localAgentsRef.current.prop }
    })
    onModelCodesChange?.({
      scene: localModelCodesRef.current.scene,
      character: localModelCodesRef.current.character,
      prop: localModelCodesRef.current.prop
    })
  }

  const [progressPercent, setProgressPercent] = useState(0)
  const isExtractingRef = useRef(false)
  const [isExtracting, setIsExtractingState] = useState(false)
  const setIsExtracting = (v: boolean) => {
    isExtractingRef.current = v
    setIsExtractingState(v)
  }

  const resolveExtractBillingRequest = async (overwrite: boolean) => {
    const submitBody = await buildAssetExtractSubmitBody({
      store: useCreationStore.getState(),
      route: getRouteLikeSnapshot(),
      extractTypes: visibleColumns.map((column) => column.kind as AssetExtractType),
      modelCodes: { ...localModelCodesRef.current },
      manualModelPickByKind: { ...userPickedModelByKindRef.current },
      overwrite
    })
    return submitBody
      ? {
          quoteType: 'ASSET_EXTRACT' as const,
          payload: { ...submitBody }
        }
      : null
  }
  const [continueQuoteRequest, setContinueQuoteRequest] = useState<BillingQuoteRequest | null>(null)
  const [continueQuoteResolvedKey, setContinueQuoteResolvedKey] = useState('')
  const [reextractQuoteRequest, setReextractQuoteRequest] = useState<BillingQuoteRequest | null>(null)
  const [reextractQuoteResolvedKey, setReextractQuoteResolvedKey] = useState('')
  const extractQuoteSelectionKey = JSON.stringify({
    currentProjectId,
    currentEpisodeId,
    agents: localAgents,
    modelCodes: localModelCodes,
    manualModelPickByKind,
    scope
  })
  const continueQuoteKey = `continue:${extractQuoteSelectionKey}`
  const reextractQuoteKey = `reextract:${extractQuoteSelectionKey}`
  useEffect(() => {
    let cancelled = false
    setContinueQuoteRequest(null)
    setContinueQuoteResolvedKey('')
    setReextractQuoteRequest(null)
    setReextractQuoteResolvedKey('')
    if (!open || !genConfigReady) {
      return () => {
        cancelled = true
      }
    }
    void resolveExtractBillingRequest(false)
      .then((request) => {
        if (!cancelled) {
          setContinueQuoteRequest(request)
          setContinueQuoteResolvedKey(request ? continueQuoteKey : '')
        }
      })
      .catch(() => {
        if (!cancelled) {
          setContinueQuoteRequest(null)
          setContinueQuoteResolvedKey('')
        }
      })
    if (actionMode === 'continueOrReextract') {
      void resolveExtractBillingRequest(true)
        .then((request) => {
          if (!cancelled) {
            setReextractQuoteRequest(request)
            setReextractQuoteResolvedKey(request ? reextractQuoteKey : '')
          }
        })
        .catch(() => {
          if (!cancelled) {
            setReextractQuoteRequest(null)
            setReextractQuoteResolvedKey('')
          }
        })
    }
    return () => {
      cancelled = true
    }
    // 选择镜像 state 或项目/剧集变化时，重新用正式提交 builder 生成两种报价体。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionMode, continueQuoteKey, genConfigReady, open, reextractQuoteKey])
  const activeContinueQuoteRequest =
    continueQuoteResolvedKey === continueQuoteKey ? continueQuoteRequest : null
  const activeReextractQuoteRequest =
    reextractQuoteResolvedKey === reextractQuoteKey ? reextractQuoteRequest : null
  const continueQuote = useBillingQuote(activeContinueQuoteRequest)
  const reextractQuote = useBillingQuote(
    actionMode === 'continueOrReextract' ? activeReextractQuoteRequest : null
  )
  const extractPendingPayloadRef = useRef<{
    agents: ExtractAgents
    modelCodes: ExtractModelCodes
    manualModelPickByKind: Partial<Record<AgentKind, boolean>>
    overwrite: boolean
  } | null>(null)
  const extractFallbackTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearExtractFallback = () => {
    if (extractFallbackTimerRef.current != null) {
      clearTimeout(extractFallbackTimerRef.current)
      extractFallbackTimerRef.current = null
    }
  }

  const finishExtractAndClose = () => {
    clearExtractFallback()
    if (!extractPendingPayloadRef.current) return
    const payload = extractPendingPayloadRef.current
    extractPendingPayloadRef.current = null
    setIsExtracting(false)
    onStart?.({
      agents: payload.agents,
      modelCodes: payload.modelCodes,
      manualModelPickByKind: payload.manualModelPickByKind,
      scope,
      overwrite: payload.overwrite
    })
    onOpenChange(false)
    setProgressPercent(0)
  }

  const onProgressFillTransitionEnd = (e: ReactTransitionEvent<HTMLDivElement>) => {
    if (e.propertyName !== 'width') return
    if (!isExtractingRef.current || !extractPendingPayloadRef.current) return
    finishExtractAndClose()
  }

  const handleStart = (overwrite = false) => {
    if (isExtractingRef.current || !genConfigReadyRef.current) return
    for (const col of visibleColumns) {
      const a = localAgentsRef.current[col.kind]
      if (!a?.id?.trim()) {
        message.warning(`请先为「${col.label}」选择智能体`)
        return
      }
    }
    const payload = {
      agents: {
        scene: { ...localAgentsRef.current.scene },
        character: { ...localAgentsRef.current.character },
        prop: { ...localAgentsRef.current.prop }
      },
      modelCodes: {
        scene: localModelCodesRef.current.scene,
        character: localModelCodesRef.current.character,
        prop: localModelCodesRef.current.prop
      },
      manualModelPickByKind: {
        scene: userPickedModelByKindRef.current.scene,
        character: userPickedModelByKindRef.current.character,
        prop: userPickedModelByKindRef.current.prop
      },
      overwrite: !!overwrite
    }
    setIsExtracting(true)
    extractPendingPayloadRef.current = payload
    setProgressPercent(0)
    clearExtractFallback()
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        setProgressPercent(100)
      })
    })
    extractFallbackTimerRef.current = setTimeout(() => {
      extractFallbackTimerRef.current = null
      if (isExtractingRef.current && extractPendingPayloadRef.current) finishExtractAndClose()
    }, PROGRESS_DURATION_MS + 80)
  }

  // 原 watch(() => props.open)
  useEffect(() => {
    if (!open) {
      clearExtractFallback()
      setIsExtracting(false)
      setProgressPercent(0)
      extractPendingPayloadRef.current = null
      setGenConfigReady(false)
    } else {
      setProgressPercent(0)
      const resetManualPick = { scene: false, character: false, prop: false }
      userPickedModelByKindRef.current = resetManualPick
      setManualModelPickByKind(resetManualPick)
      void loadGenConfigForKinds(
        scope === 'all'
          ? (['scene', 'character', 'prop'] as AgentKind[])
          : ([scope] as AgentKind[]),
        { autoPickDefault: true }
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const handleCancel = () => {
    clearExtractFallback()
    setIsExtracting(false)
    setProgressPercent(0)
    extractPendingPayloadRef.current = null
    onOpenChange(false)
  }

  return (
    <>
      <Modal
        open={open}
        width={extractModalWidth}
        footer={null}
        closable
        centered
        className="extract-agent-modal"
        wrapClassName={extractWrapClassName}
        onCancel={handleCancel}
        title={<ModalTitleWatermark title={extractModalTitle} watermark="EXTRACT" />}
      >
        <div className="extract-body">
          {isExtracting ? (
            <div
              className="extract-progress"
              role="progressbar"
              aria-valuenow={Math.round(progressPercent)}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="提取进度"
            >
              <div className="extract-progress__track">
                <div
                  className="extract-progress__fill"
                  style={{ width: progressPercent + '%' }}
                  onTransitionEnd={onProgressFillTransitionEnd}
                />
              </div>
            </div>
          ) : null}

          <div className="extract-shell">
            <div className={`extract-columns ${extractColumnsClass}`}>
              {visibleColumns.map((col) => (
                <div key={col.kind} className="extract-col">
                  <div className="extract-col__head">
                    <span className="extract-col__head-text">{col.label}</span>
                    <span
                      className="extract-col__head-ico"
                      aria-hidden="true"
                      role="button"
                      tabIndex={0}
                      onClick={(e: ReactMouseEvent) => {
                        e.stopPropagation()
                        void openPicker(col.kind)
                      }}
                      onKeyDown={(e: ReactKeyboardEvent) => {
                        if (e.key !== 'Enter') return
                        e.preventDefault()
                        void openPicker(col.kind)
                      }}
                    >
                      <img src={subtractIconUrl} alt="" />
                    </span>
                  </div>
                  <div className="extract-col__box">
                    <button
                      type="button"
                      className="extract-col__card"
                      onClick={(e) => {
                        e.stopPropagation()
                        void openPicker(col.kind)
                      }}
                    >
                      <figure className="extract-col__figure">
                        <div className="extract-col__img-wrap">
                          {localAgents[col.kind].thumbnail ? (
                            <ShimmerImage
                              src={localAgents[col.kind].thumbnail}
                              alt={localAgents[col.kind].name || col.label + '智能体'}
                              imgClass="extract-col__img"
                              wrapperClass="extract-col__img-shimmer"
                              objectFit="cover"
                              revealDirection="fade"
                              minShimmerMs={280}
                            />
                          ) : (
                            <div className="extract-col__img-placeholder">
                              <ThunderboltOutlined />
                            </div>
                          )}
                        </div>
                        <figcaption className="extract-col__caption">
                          <div className="extract-col__name">
                            {localAgents[col.kind].name || '点击选择智能体'}
                          </div>
                          <p className="extract-col__desc">
                            {localAgents[col.kind].desc ||
                              (localAgents[col.kind].name
                                ? '暂无描述'
                                : '从列表中选择适合本环节的智能体')}
                          </p>
                        </figcaption>
                      </figure>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="extract-footer">
          <div className="extract-footer__quotes">
            <div className="extract-footer__quote-row">
              <span className="extract-footer__quote-label">
                {actionMode === 'continueOrReextract' ? '继续提取' : '开始提取'}
              </span>
              <BillingQuoteHint
                quote={continueQuote.quote}
                loading={continueQuote.loading}
                error={continueQuote.error}
                active={open && genConfigReady}
                idleText={activeContinueQuoteRequest ? '' : '正在准备预计费用…'}
                className="extract-footer__quote"
              />
            </div>
            {actionMode === 'continueOrReextract' ? (
              <div className="extract-footer__quote-row">
                <span className="extract-footer__quote-label">重新提取</span>
                <BillingQuoteHint
                  quote={reextractQuote.quote}
                  loading={reextractQuote.loading}
                  error={reextractQuote.error}
                  active={open && genConfigReady}
                  idleText={activeReextractQuoteRequest ? '' : '正在准备预计费用…'}
                  className="extract-footer__quote"
                />
              </div>
            ) : null}
          </div>
          <Button className="extract-btn-cancel" size="large" onClick={handleCancel}>
            <div className="text-gradient">取消</div>
          </Button>
          {actionMode === 'continueOrReextract' ? (
            <>
              <BillingQuoteConfirm
                disabled={isExtracting || !genConfigReady}
                resolveRequest={() => resolveExtractBillingRequest(false)}
                onConfirm={() => handleStart(false)}
                title="确认继续提取"
              >
                <Button
                  type="primary"
                  size="large"
                  className="extract-btn-ok"
                  disabled={isExtracting || !genConfigReady}
                  icon={<img src={starWhiteIconUrl} alt="" />}
                >
                  继续提取
                </Button>
              </BillingQuoteConfirm>
              <Tooltip title="重新提取会将之前提取的内容删除并重新计费">
                <span className="extract-btn-reextract-wrap">
                  <BillingQuoteConfirm
                    disabled={isExtracting || !genConfigReady}
                    resolveRequest={() => resolveExtractBillingRequest(true)}
                    onConfirm={() => handleStart(true)}
                    title="确认重新提取"
                  >
                    <Button
                      size="large"
                      className="extract-btn-reextract"
                      disabled={isExtracting || !genConfigReady}
                    >
                      重新提取
                    </Button>
                  </BillingQuoteConfirm>
                </span>
              </Tooltip>
            </>
          ) : (
            <BillingQuoteConfirm
              disabled={isExtracting || !genConfigReady}
              resolveRequest={() => resolveExtractBillingRequest(false)}
              onConfirm={() => handleStart(false)}
              title="确认开始提取"
            >
              <Button
                type="primary"
                size="large"
                className="extract-btn-ok"
                disabled={isExtracting || !genConfigReady}
                icon={<img src={starWhiteIconUrl} alt="" />}
              >
                开始提取
              </Button>
            </BillingQuoteConfirm>
          )}
        </div>
      </Modal>

      <AgentPickerModal
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        agents={pickerAgents}
        models={pickerAvailableModels}
        defaultQuery={pickerDefaultQuery}
        initialModelCode={localModelCodes[pickerKind]}
        onSelect={handlePicked}
      />
    </>
  )
}

export default ExtractAgentModal
