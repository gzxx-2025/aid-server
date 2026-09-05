'use client'

import { useEffect, useMemo, useState } from 'react'
import { Button, ConfigProvider, Modal, Select, message } from 'antd'
import { AppstoreOutlined, LoadingOutlined } from '@ant-design/icons'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { ModelFreeBadge } from '~/components/common/ModelFreeBadge'
import { AgentPickerModal, type AgentOption } from '~/components/steps/AgentPickerModal'
import type { ProjectGenConfigVO, UserModelListItem } from '~/types/business-api'
import { useCreationStore } from '~/stores/creation'
import { resolveUserModelProviderLogo } from '~/utils/userModelOption'
import { shouldShowModelFreeBadge } from '~/utils/modelFreeStatus'
import {
  FORM_IMAGE_AGENT_BIZ_CATEGORY,
  agentOptionsFromGenConfigVo,
  pickFirstAgentOption
} from '~/utils/extractAgentBiz'
import {
  fetchProjectGenConfigList,
  saveProjectGenConfigItems,
  clearProjectGenConfigCache,
  buildProjectGenConfigVisibleGroups,
  resolveProjectGenConfigSceneCode
} from '~/utils/projectGenConfig'
import { type ProjectGenConfigSceneKind } from '~/utils/projectGenConfigScenes'
import {
  resolveImageGenParamsFromAgentDefaults,
  resolveImageGenParamsForModel,
  resolveModelAspectRatioOptions,
  resolveModelSizeOptions
} from '~/utils/modelCapability'
import { assetUrl } from '~/utils/assetUrl'
import subtractIconRaw from '~/assets/img/icon/Subtract.svg'
import './ProjectGenConfigModal.css'

const subtractIconUrl = assetUrl(subtractIconRaw)

/** 默认按「形态生图」4 条场景撑满，切 Tab 条数变化时整体高度不变 */
const PGC_MODAL_HEIGHT = 'min(740px, calc(100dvh - 48px))'

const PGC_SELECT_THEME = {
  components: {
    Select: {
      selectorBg: '#1e2028',
      colorBorder: '#30363d',
      hoverBorderColor: '#4a5560',
      activeBorderColor: '#4a5560',
      activeOutlineColor: 'transparent',
      colorText: '#c0c4cc'
    }
  }
} as const

interface SceneRowDraft {
  agentCode: string
  agentName: string
  agentIconUrl: string
  modelCode: string
  resolution: string
  aspectRatio: string
  /** GET 返回的配置来源；清晰度/比例始终以 GET 的智能体级字段优先 */
  source: string
  availableModels: UserModelListItem[]
}

const TAB_LABEL_MAP: Record<string, string> = {
  '资产提取（文字）': '资产提取(文字)',
  '形态文案（文字）': '形态提取(文字)',
  '形态生图（图片）': '形态生图(图片)',
  '分镜（文字）': '分镜脚本(文字)',
  '分镜生图（图片）': '分镜生图(图片)'
}

export interface ProjectGenConfigModalProps {
  open: boolean
  projectId: number | null
  episodeId?: number | null
  onOpenChange: (value: boolean) => void
  onSaved?: () => void
}

function tabLabel(title: string): string {
  return TAB_LABEL_MAP[title] ?? title
}

function sceneLabelLines(label: string): string[] {
  const text = String(label || '').trim()
  if (!text) return ['']
  if (text.length <= 3) return [text]
  const lines: string[] = []
  for (let i = 0; i < text.length; i += 2) {
    lines.push(text.slice(i, i + 2))
  }
  return lines
}

function configSourceMeta(source?: string): { label: string; className: string; title: string } {
  if (source === 'project') {
    return { label: '作品配置', className: 'is-project', title: '已保存为当前作品的个性化配置' }
  }
  if (source === 'draft') {
    return { label: '待保存', className: 'is-draft', title: '当前选择尚未保存' }
  }
  if (source === 'none') {
    return { label: '待配置', className: 'is-empty', title: '当前场景没有可用的智能体或模型' }
  }
  return {
    label: '策略默认',
    className: 'is-default',
    title: '来自后台策略矩阵；配置下线时会自动切换到当前可用默认项'
  }
}

function emptyRow(): SceneRowDraft {
  return {
    agentCode: '',
    agentName: '',
    agentIconUrl: '',
    modelCode: '',
    resolution: '',
    aspectRatio: '',
    source: '',
    availableModels: []
  }
}

function applyVoToRow(
  vo: ProjectGenConfigVO,
  rows: Record<string, SceneRowDraft>,
  agentOptionsByScene: Record<string, AgentOption[]>
) {
  const code = resolveProjectGenConfigSceneCode(vo)
  if (!code) return
  const agentCode = String(vo.agentCode || '').trim()
  const agents = agentOptionsByScene[code] ?? []
  const agentHit = agents.find((a) => a.id === agentCode)
  rows[code] = {
    agentCode,
    agentName: agentHit?.name || agentCode,
    agentIconUrl: String(agentHit?.thumbnail || vo.agentIconUrl || '').trim(),
    modelCode: String(vo.modelCode || '').trim(),
    resolution: String(vo.resolution || '').trim(),
    aspectRatio: String(vo.aspectRatio || '').trim(),
    source: String(vo.source || '').trim(),
    availableModels: Array.isArray(vo.availableModels) ? vo.availableModels : []
  }
}

function pickDefaultModelCode(
  sceneCode: string,
  agentCode: string,
  availableModels: UserModelListItem[],
  agentOptionsByScene: Record<string, AgentOption[]>,
  preferredModelCode?: string
): string {
  const preferred = String(preferredModelCode || '').trim()
  if (preferred && availableModels.some((m) => String(m.modelCode || '').trim() === preferred)) {
    return preferred
  }

  const agent = (agentOptionsByScene[sceneCode] ?? []).find((a) => a.id === agentCode)
  const agentDefault = String(agent?.defaultModelCode || '').trim()
  if (
    agentDefault &&
    availableModels.some((m) => String(m.modelCode || '').trim() === agentDefault)
  ) {
    return agentDefault
  }

  const sorted = [...availableModels].sort(
    (a, b) => Number(b.priority ?? 0) - Number(a.priority ?? 0)
  )
  return String(sorted[0]?.modelCode || '').trim()
}

function applyImageParamDefaults(
  kind: ProjectGenConfigSceneKind,
  draft: SceneRowDraft,
  opts?: { forceModelDefaults?: boolean }
): SceneRowDraft {
  if (kind !== 'image') return draft

  const modelCode = String(draft.modelCode || '').trim()
  const hit = draft.availableModels.find((m) => String(m.modelCode || '').trim() === modelCode)
  const params = resolveImageGenParamsForModel(draft, hit, opts)
  return { ...draft, ...params }
}

function applyAgentImageParamDefaults(
  kind: ProjectGenConfigSceneKind,
  draft: SceneRowDraft
): SceneRowDraft {
  if (kind !== 'image') return draft

  const modelCode = String(draft.modelCode || '').trim()
  const hit = draft.availableModels.find((m) => String(m.modelCode || '').trim() === modelCode)
  return {
    ...draft,
    ...resolveImageGenParamsFromAgentDefaults(draft, hit)
  }
}

function seedSceneRowDefaults(
  sceneCode: string,
  kind: ProjectGenConfigSceneKind,
  rows: Record<string, SceneRowDraft>,
  agentOptionsByScene: Record<string, AgentOption[]>
) {
  const cur = rows[sceneCode] ?? emptyRow()
  const agents = agentOptionsByScene[sceneCode] ?? []

  let agentCode = String(cur.agentCode || '').trim()
  let agentName = String(cur.agentName || '').trim()
  let agentIconUrl = String(cur.agentIconUrl || '').trim()

  if (!agentCode) {
    const first = pickFirstAgentOption(agents)
    if (first) {
      agentCode = first.id
      agentName = first.name
      agentIconUrl = String(first.thumbnail || '').trim()
    }
  } else {
    const hit = agents.find((a) => a.id === agentCode)
    if (hit) {
      agentName = hit.name
      agentIconUrl = String(hit.thumbnail || agentIconUrl || '').trim()
    } else {
      const first = pickFirstAgentOption(agents)
      if (first) {
        agentCode = first.id
        agentName = first.name
        agentIconUrl = String(first.thumbnail || '').trim()
      } else if (!agentName) {
        agentName = agentCode
      }
    }
  }

  const availableModels = Array.isArray(cur.availableModels) ? [...cur.availableModels] : []

  let modelCode = String(cur.modelCode || '').trim()
  if (!modelCode) {
    modelCode = pickDefaultModelCode(sceneCode, agentCode, availableModels, agentOptionsByScene)
  }
  if (
    modelCode &&
    availableModels.length &&
    !availableModels.some((m) => String(m.modelCode || '').trim() === modelCode)
  ) {
    modelCode = pickDefaultModelCode(sceneCode, agentCode, availableModels, agentOptionsByScene)
  }

  // 首次回显以 GET 返回的智能体 resolution / aspectRatio 为准；
  // 单项缺失时才回落模型默认值，下拉选项仍只读取模型 capability。
  rows[sceneCode] = applyAgentImageParamDefaults(
    kind,
    {
      agentCode,
      agentName,
      agentIconUrl,
      modelCode,
      resolution: cur.resolution,
      aspectRatio: cur.aspectRatio,
      source: cur.source,
      availableModels
    }
  )
}

/** 生成配置弹窗：为当前作品配置各业务场景的默认智能体与模型（原 components/steps/ProjectGenConfigModal.vue） */
export function ProjectGenConfigModal({
  open,
  projectId,
  episodeId = null,
  onOpenChange,
  onSaved
}: ProjectGenConfigModalProps) {
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [activeTabIndex, setActiveTabIndex] = useState(0)
  const [rows, setRows] = useState<Record<string, SceneRowDraft>>({})
  const [agentOptionsByScene, setAgentOptionsByScene] = useState<Record<string, AgentOption[]>>({})
  const [applicableSceneCodes, setApplicableSceneCodes] = useState<string[]>([])

  const visibleSceneGroups = useMemo(
    () => buildProjectGenConfigVisibleGroups(applicableSceneCodes),
    [applicableSceneCodes]
  )

  const [agentPickerOpen, setAgentPickerOpen] = useState(false)
  const [pickerSceneCode, setPickerSceneCode] = useState('')
  const [pickerAgents, setPickerAgents] = useState<AgentOption[]>([])
  const [pickerAvailableModels, setPickerAvailableModels] = useState<UserModelListItem[]>([])
  const [pickerInitialModelCode, setPickerInitialModelCode] = useState('')

  const activeGroup = visibleSceneGroups[activeTabIndex] ?? visibleSceneGroups[0]

  function rowValue(sceneCode: string): SceneRowDraft {
    return rows[sceneCode] ?? emptyRow()
  }

  function selectedAgent(sceneCode: string): AgentOption | undefined {
    const r = rowValue(sceneCode)
    const code = String(r.agentCode || '').trim()
    if (!code) return undefined
    return (agentOptionsByScene[sceneCode] ?? []).find((a) => a.id === code)
  }

  function selectedModelItem(sceneCode: string): UserModelListItem | undefined {
    const r = rowValue(sceneCode)
    const code = String(r.modelCode || '').trim()
    if (!code) return undefined
    return r.availableModels.find((m) => String(m.modelCode || '').trim() === code)
  }

  function agentDisplayName(sceneCode: string): string {
    const r = rowValue(sceneCode)
    return r.agentName || r.agentCode || '选择智能体'
  }

  function agentDisplayDesc(sceneCode: string): string {
    const agent = selectedAgent(sceneCode)
    if (agent?.desc) return agent.desc
    if (rowValue(sceneCode).agentCode) return '已配置默认智能体'
    return '点击选择默认智能体'
  }

  function agentThumbnail(sceneCode: string): string {
    return String(selectedAgent(sceneCode)?.thumbnail || rowValue(sceneCode).agentIconUrl || '').trim()
  }

  function modelDisplayName(sceneCode: string): string {
    const hit = selectedModelItem(sceneCode)
    if (hit?.modelName) return String(hit.modelName)
    const code = rowValue(sceneCode).modelCode
    return code || '选择模型'
  }

  function modelDisplayLogo(sceneCode: string): string {
    return resolveUserModelProviderLogo(selectedModelItem(sceneCode))
  }

  function modelDisplayInitial(sceneCode: string): string {
    const name = modelDisplayName(sceneCode)
    if (!name || name === '选择模型') return 'M'
    return name.slice(0, 1).toUpperCase()
  }

  function resolutionOptionsFor(sceneCode: string) {
    const opts = resolveModelSizeOptions(selectedModelItem(sceneCode))
    return opts.map((v) => ({ label: v, value: v }))
  }

  function aspectRatioOptionsFor(sceneCode: string) {
    const opts = resolveModelAspectRatioOptions(selectedModelItem(sceneCode))
    return opts.map((v) => ({ label: v, value: v }))
  }

  async function loadAll() {
    const pid = Number(projectId)
    if (!Number.isFinite(pid) || pid <= 0) {
      message.warning('请先选择作品后再配置')
      return
    }

    setLoading(true)
    setActiveTabIndex(0)
    try {
      setAgentOptionsByScene({})
      setRows({})
      setApplicableSceneCodes([])

      clearProjectGenConfigCache(pid)

      const nextAgentOptions: Record<string, AgentOption[]> = {}
      const nextRows: Record<string, SceneRowDraft> = {}

      const eid = Number(episodeId)
      const list = await fetchProjectGenConfigList(pid, {
        force: true,
        ...(Number.isFinite(eid) && eid > 0 ? { episodeId: eid } : {})
      })
      const sceneCodes: string[] = []

      for (const vo of list) {
        const code = resolveProjectGenConfigSceneCode(vo)
        if (!code) continue
        sceneCodes.push(code)
        nextAgentOptions[code] = agentOptionsFromGenConfigVo(vo)
        applyVoToRow(vo, nextRows, nextAgentOptions)
      }

      const scenes = buildProjectGenConfigVisibleGroups(sceneCodes).flatMap((g) => g.scenes)
      for (const scene of scenes) {
        const code = String(scene.sceneCode)
        if (!nextRows[code]) nextRows[code] = emptyRow()
        seedSceneRowDefaults(code, scene.kind, nextRows, nextAgentOptions)
      }

      setAgentOptionsByScene(nextAgentOptions)
      setRows(nextRows)
      setApplicableSceneCodes(sceneCodes)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载生成配置失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (open) void loadAll()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectId, episodeId])

  function openAgentPicker(sceneCode: string) {
    const agents = agentOptionsByScene[sceneCode] ?? []
    setPickerSceneCode(sceneCode)
    setPickerInitialModelCode(rowValue(sceneCode).modelCode)
    setPickerAgents(agents)
    setPickerAvailableModels([...(rowValue(sceneCode).availableModels ?? [])])
    if (!agents.length) {
      message.warning('暂无可用智能体')
      return
    }
    setAgentPickerOpen(true)
  }

  function sceneKindFor(sceneCode: string): ProjectGenConfigSceneKind {
    const hit = visibleSceneGroups
      .flatMap((g) => g.scenes)
      .find((s) => String(s.sceneCode) === sceneCode)
    return hit?.kind ?? 'text'
  }

  function onAgentPicked(payload: { agent?: AgentOption; modelCode?: string }) {
    const sceneCode = pickerSceneCode
    if (!sceneCode) return
    const cur = { ...rowValue(sceneCode) }
    const agent = payload.agent
    if (agent) {
      cur.agentCode = agent.id
      cur.agentName = agent.name
      cur.agentIconUrl = String(agent.thumbnail || '').trim()
    }
    const pickedModel = String(payload.modelCode || agent?.defaultModelCode || '').trim()
    if (pickedModel) {
      cur.modelCode = pickedModel
    }
    cur.source = 'draft'
    // 确认选择模型后，清晰度/比例一律回落该模型 capability.defaultSize / defaultAspectRatio
    const next = applyImageParamDefaults(sceneKindFor(sceneCode), cur, {
      forceModelDefaults: Boolean(pickedModel)
    })
    setRows((prev) => ({ ...prev, [sceneCode]: next }))
  }

  function onResolutionChange(sceneCode: string, v: string) {
    const cur = { ...rowValue(sceneCode), resolution: String(v || '').trim(), source: 'draft' }
    setRows((prev) => ({ ...prev, [sceneCode]: cur }))
  }

  function onAspectRatioChange(sceneCode: string, v: string) {
    const cur = { ...rowValue(sceneCode), aspectRatio: String(v || '').trim(), source: 'draft' }
    setRows((prev) => ({ ...prev, [sceneCode]: cur }))
  }

  async function handleSave() {
    const pid = Number(projectId)
    if (!Number.isFinite(pid) || pid <= 0) {
      message.warning('项目无效')
      return
    }

    const configs = visibleSceneGroups
      .flatMap((g) => g.scenes)
      .map((scene) => {
        const r = rowValue(String(scene.sceneCode))
        if (!r.agentCode || !r.modelCode) return null
        return {
          sceneCode: String(scene.sceneCode),
          agentCode: r.agentCode,
          modelCode: r.modelCode,
          ...(scene.kind === 'image' && r.resolution ? { resolution: r.resolution } : {}),
          ...(scene.kind === 'image' && r.aspectRatio ? { aspectRatio: r.aspectRatio } : {})
        }
      })
      .filter(Boolean) as Array<{
      sceneCode: string
      agentCode: string
      modelCode: string
      resolution?: string
      aspectRatio?: string
    }>

    if (!configs.length) {
      message.warning('请至少完成一项场景的智能体与模型配置')
      return
    }

    setSaving(true)
    try {
      await saveProjectGenConfigItems(pid, configs)
      // 同步形态图模型到分桶缓存，避免列表自动生成/重新生成仍读到保存前的旧 modelCode
      const imageCodes: { scene?: string; character?: string; prop?: string } = {}
      for (const item of configs) {
        const code = String(item.modelCode || '').trim()
        if (!code) continue
        if (item.sceneCode === FORM_IMAGE_AGENT_BIZ_CATEGORY.scene) imageCodes.scene = code
        else if (item.sceneCode === FORM_IMAGE_AGENT_BIZ_CATEGORY.character) imageCodes.character = code
        else if (item.sceneCode === FORM_IMAGE_AGENT_BIZ_CATEGORY.prop) imageCodes.prop = code
      }
      if (imageCodes.scene || imageCodes.character || imageCodes.prop) {
        useCreationStore.getState().updateExtractImageModelCodes(imageCodes)
      }
      message.success('生成配置已保存')
      onSaved?.()
      onOpenChange(false)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  function handleCancel() {
    onOpenChange(false)
  }

  return (
    <ConfigProvider theme={PGC_SELECT_THEME}>
    <Modal
      open={open}
      width="58%"
      footer={null}
      centered
      className="project-gen-config-modal"
      wrapClassName="create-flow-modal project-gen-config-modal-wrap"
      destroyOnHidden
      onCancel={handleCancel}
      styles={{
        container: {
          height: PGC_MODAL_HEIGHT,
          maxHeight: 'calc(100dvh - 48px)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          padding: '20px 24px'
        }
      }}
      title={<ModalTitleWatermark title="生成配置" watermark="CONFIGURATION" />}
    >
      <div className="pgc-shell">
        <div className="pgc-body">
          <p className="pgc-desc">
            为当前作品配置各业务场景的默认智能体与模型。自动提取、批量生成未手动选模型时将使用此处配置（经济/性能模式跟随作品「模型策略」）。
          </p>
          <div className="pgc-fallback-note">
            后台停用模型或智能体后，未固定的场景会自动使用当前可用的策略默认项；历史生成结果和计费记录不受影响。
          </div>

          {loading ? (
            <div className="pgc-loading" role="status" aria-live="polite" aria-busy="true">
              <LoadingOutlined spin className="pgc-loading__icon" />
              <p className="pgc-loading__text">正在加载生成配置…</p>
            </div>
          ) : (
            <>
              <div className="header-tabs">
                <div className="import-tab-bar__inner">
                  <nav className="import-tab-inner" aria-label="生成配置分类">
                    {visibleSceneGroups.map((group, index) => (
                      <button
                        key={group.title}
                        type="button"
                        className={`import-tab${activeTabIndex === index ? ' import-tab--active' : ''}`}
                        onClick={() => setActiveTabIndex(index)}
                      >
                        {tabLabel(group.title)}
                      </button>
                    ))}
                  </nav>
                </div>
              </div>

              <div className="pgc-scenes-scroll">
                <div className="pgc-scenes">
                  {(activeGroup?.scenes ?? []).map((scene) => (
                    <section key={String(scene.sceneCode)} className="pgc-scene-panel">
                      <div className="pgc-scene-label">
                        {sceneLabelLines(scene.label).map((line, lineIndex) => (
                          <span key={lineIndex} className="pgc-scene-label__line">
                            {line}
                          </span>
                        ))}
                      </div>

                      <div
                        className={`pgc-scene-config${scene.kind === 'image' ? ' pgc-scene-config--image' : ''}`}
                      >
                        <div className="pgc-config-head-row">
                          <div className="pgc-config-head">
                            <span className="pgc-config-head__text">智能体/模型</span>
                            {(() => {
                              const source = configSourceMeta(
                                rowValue(String(scene.sceneCode)).source
                              )
                              return (
                                <span
                                  className={`pgc-config-source ${source.className}`}
                                  title={source.title}
                                >
                                  {source.label}
                                </span>
                              )
                            })()}
                            <span className="pgc-config-head__ico" aria-hidden="true">
                              <img
                                onClick={() => openAgentPicker(String(scene.sceneCode))}
                                src={subtractIconUrl}
                                alt=""
                              />
                            </span>
                          </div>
                          {scene.kind === 'image' ? (
                            <>
                              <div className="pgc-config-head pgc-config-head--param">
                                <span className="pgc-config-head__text">清晰度</span>
                              </div>
                              <div className="pgc-config-head pgc-config-head--param">
                                <span className="pgc-config-head__text">比例</span>
                              </div>
                            </>
                          ) : null}
                        </div>

                        <div className="pgc-config-row">
                          <button
                            type="button"
                            className="pgc-agent-model-card"
                            onClick={() => openAgentPicker(String(scene.sceneCode))}
                          >
                            <div className="pgc-agent-model-card__agent">
                              <div className="pgc-agent-model-card__avatar">
                                {agentThumbnail(String(scene.sceneCode)) ? (
                                  <ShimmerImage
                                    src={agentThumbnail(String(scene.sceneCode))}
                                    alt={agentDisplayName(String(scene.sceneCode))}
                                    imgClass="pgc-agent-model-card__avatar-img"
                                    wrapperClass="pgc-agent-model-card__avatar-shimmer"
                                    objectFit="cover"
                                    revealDirection="fade"
                                    minShimmerMs={280}
                                  />
                                ) : (
                                  <AppstoreOutlined className="pgc-agent-model-card__avatar-ico" />
                                )}
                              </div>
                              <div className="pgc-agent-model-card__info">
                                <div className="pgc-agent-model-card__name">
                                  {agentDisplayName(String(scene.sceneCode))}
                                </div>
                                <div className="pgc-agent-model-card__desc">
                                  {agentDisplayDesc(String(scene.sceneCode))}
                                </div>
                              </div>
                            </div>

                            <span className="pgc-agent-model-card__sep" aria-hidden="true">
                              /
                            </span>

                            <div className="pgc-agent-model-card__model">
                              <div className="pgc-agent-model-card__avatar pgc-agent-model-card__avatar--model">
                                {modelDisplayLogo(String(scene.sceneCode)) ? (
                                  <img
                                    src={modelDisplayLogo(String(scene.sceneCode))}
                                    alt={modelDisplayName(String(scene.sceneCode))}
                                    className="pgc-agent-model-card__avatar-img"
                                  />
                                ) : (
                                  <span>{modelDisplayInitial(String(scene.sceneCode))}</span>
                                )}
                              </div>
                              <div className="pgc-agent-model-card__info">
                                <div className="pgc-agent-model-card__model-name-row">
                                  <div className="pgc-agent-model-card__name">
                                    {modelDisplayName(String(scene.sceneCode))}
                                  </div>
                                  {shouldShowModelFreeBadge(
                                    selectedModelItem(String(scene.sceneCode)) || {}
                                  ) ? (
                                    <ModelFreeBadge />
                                  ) : null}
                                </div>
                              </div>
                            </div>
                          </button>

                          {scene.kind === 'image' ? (
                            <>
                              <div className="pgc-param-field">
                                <Select
                                  className="pgc-select pgc-select--param"
                                  value={rowValue(String(scene.sceneCode)).resolution || undefined}
                                  placeholder="清晰度"
                                  options={resolutionOptionsFor(String(scene.sceneCode))}
                                  disabled={!resolutionOptionsFor(String(scene.sceneCode)).length}
                                  classNames={{ popup: { root: 'pgc-select-popup' } }}
                                  onChange={(v) =>
                                    onResolutionChange(String(scene.sceneCode), String(v ?? ''))
                                  }
                                />
                              </div>
                              <div className="pgc-param-field">
                                <Select
                                  className="pgc-select pgc-select--param"
                                  value={rowValue(String(scene.sceneCode)).aspectRatio || undefined}
                                  placeholder="比例"
                                  options={aspectRatioOptionsFor(String(scene.sceneCode))}
                                  disabled={!aspectRatioOptionsFor(String(scene.sceneCode)).length}
                                  classNames={{ popup: { root: 'pgc-select-popup' } }}
                                  onChange={(v) =>
                                    onAspectRatioChange(String(scene.sceneCode), String(v ?? ''))
                                  }
                                />
                              </div>
                            </>
                          ) : null}
                        </div>
                      </div>
                    </section>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>

        <div className="pgc-footer">
          <Button size="large" className="pgc-btn-cancel" disabled={loading} onClick={handleCancel}>
            <div className="text-gradient">取消</div>
          </Button>
          <Button
            type="primary"
            size="large"
            className="pgc-btn-save"
            loading={saving}
            disabled={loading}
            onClick={handleSave}
          >
            确定
          </Button>
        </div>
      </div>

      <AgentPickerModal
        open={agentPickerOpen}
        onOpenChange={setAgentPickerOpen}
        agents={pickerAgents}
        models={pickerAvailableModels}
        initialModelCode={pickerInitialModelCode}
        onSelect={onAgentPicked}
      />
    </Modal>
    </ConfigProvider>
  )
}

export default ProjectGenConfigModal
