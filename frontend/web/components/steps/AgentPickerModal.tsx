'use client'

import { SearchOutlined } from '@ant-design/icons'
import { Button,Input,Modal } from 'antd'
import { useEffect,useMemo,useRef,useState } from 'react'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ModelFreeBadge } from '~/components/common/ModelFreeBadge'
import { ModelBillingRules } from '~/components/common/ModelBillingRules'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import type { AiModelType,UserModelListItem } from '~/types/business-api'
import type { AgentOption } from '~/types/modelAgentOptions'
import { userModelList,userModelListByFunc } from '~/utils/businessApi'
import { shouldShowModelFreeBadge } from '~/utils/modelFreeStatus'
import { mapUserModelListItemToPickerOption } from '~/utils/userModelOption'
import './AgentPickerModal.css'
 interface ModelPickerOption {
  modelCode: string
  name: string
  desc?: string
  logo?: string
  isFree?: boolean
  billing?: UserModelListItem['billing']
}

// 保持原导入路径兼容：原类型定义在本组件文件，现提升到 types/modelAgentOptions
export type { AgentOption }

interface Props {
  open: boolean
  defaultQuery?: string
  agents: AgentOption[]
  /** 预置模型池（如 gen-config/get 的 availableModels），传入则不再请求 listByFunc */
  models?: UserModelListItem[]
  /** 传入时展示模型列表并调用 POST /api/user/model/listByFunc（未传 models 时） */
  funcCode?: string
  /** funcCode 无数据时按 modelType 拉取 /api/user/model/list（如分镜视频） */
  modelType?: AiModelType
  /** 已选 modelCode，打开弹窗时回显 */
  initialModelCode?: string
  onOpenChange?: (value: boolean) => void
  onSelect?: (payload: { agent?: AgentOption; modelCode?: string }) => void
}

const EMPTY_MODELS: UserModelListItem[] = []

export function AgentPickerModal({
  open,
  defaultQuery = '',
  agents,
  models = EMPTY_MODELS,
  funcCode = '',
  modelType = undefined,
  initialModelCode = '',
  onOpenChange,
  onSelect
}: Props) {
  const showModelSection = Boolean(
    (Array.isArray(models) && models.length > 0) ||
    String(funcCode || '').trim() ||
    modelType
  )

  const [query, setQuery] = useState(defaultQuery)
  const [selected, setSelected] = useState<AgentOption | null>(null)
  const [selectedModelCode, setSelectedModelCode] = useState('')

  const [modelOptions, setModelOptions] = useState<ModelPickerOption[]>([])
  const [modelsLoading, setModelsLoading] = useState(false)
  // 异步（applyAgentDefaultModel await 后）读取最新模型池用，避免闭包中的旧 state
  const modelOptionsRef = useRef<ModelPickerOption[]>([])

  function commitModelOptions(list: ModelPickerOption[]) {
    modelOptionsRef.current = list
    setModelOptions(list)
  }

  function selectAgent(agent: AgentOption) {
    if (selected?.id === agent.id) {
      setSelected(null)
      setSelectedModelCode('')
      return
    }
    setSelected(agent)
    void applyAgentDefaultModel(agent)
  }

  /** 切换智能体：刷新模型池后选中该智能体默认 modelCode（须在池内） */
  async function applyAgentDefaultModel(agent: AgentOption) {
    if (!showModelSection) return
    if (!modelOptionsRef.current.length) {
      await loadModelsForPicker()
    }
    const defaultCode = String(agent.defaultModelCode || '').trim()
    if (defaultCode && modelOptionsRef.current.some((m) => m.modelCode === defaultCode)) {
      setSelectedModelCode(defaultCode)
      return
    }
    setSelectedModelCode('')
  }

  function selectModel(modelCode: string) {
    const code = String(modelCode || '').trim()
    if (!code) return
    setSelectedModelCode((prev) => (prev === code ? '' : code))
  }

  function mapModelItem(item: UserModelListItem): ModelPickerOption {
    const mapped = mapUserModelListItemToPickerOption(item)
    return {
      modelCode: mapped.modelCode,
      name: mapped.name,
      desc: mapped.desc,
      logo: mapped.logo,
      isFree: mapped.isFree,
      billing: mapped.billing
    }
  }

  function applyPresetModels(list: UserModelListItem[]) {
    commitModelOptions(list.map(mapModelItem).filter((m) => m.modelCode))
  }

  async function loadModelsForPicker() {
    if (!showModelSection) {
      commitModelOptions([])
      return
    }
    const preset = models
    if (Array.isArray(preset) && preset.length) {
      applyPresetModels(preset)
      return
    }
    const code = String(funcCode || '').trim()
    setModelsLoading(true)
    try {
      let list: UserModelListItem[] = []
      if (code) {
        list = await userModelListByFunc(code)
      }
      if (!list.length && modelType) {
        list = await userModelList({ modelType })
      }
      commitModelOptions(list.map(mapModelItem).filter((m) => m.modelCode))
    } catch {
      commitModelOptions([])
    } finally {
      setModelsLoading(false)
    }
  }

  useEffect(() => {
    setQuery(defaultQuery)
  }, [defaultQuery])

  useEffect(() => {
    if (open) {
      setSelected(null)
      setQuery(defaultQuery)
      setSelectedModelCode(String(initialModelCode || '').trim())
      if (showModelSection) void loadModelsForPicker()
    }
    // 对齐原 watch(() => props.open)：仅 open 变化时重置回显并拉取
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 原 deep watch [funcCode, modelType, models]：models 为父级传入数组，以引用变化为准
  useEffect(() => {
    if (open && showModelSection) void loadModelsForPicker()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funcCode, modelType, models])

  // 原 watch(modelOptions)：模型池刷新后校验已选 modelCode 是否仍在池内
  useEffect(() => {
    const list = modelOptions
    if (!open || !list.length) return
    const current = selectedModelCode.trim()
    if (current && !list.some((m) => m.modelCode === current)) {
      setSelectedModelCode('')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelOptions])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return agents
    return agents.filter((a) => (a.name + (a.desc || '')).toLowerCase().includes(q))
  }, [agents, query])

  const filteredModels = useMemo(() => {
    const list = modelOptions
    const q = query.trim().toLowerCase()
    const preset = String(defaultQuery || '')
      .trim()
      .toLowerCase()
    // 打开弹窗时 defaultQuery 常为「场景/角色/道具」，仅用于智能体筛选，勿用它滤空模型列表
    if (!q || (preset && q === preset)) return list
    return list.filter((m) => (m.name + (m.desc || '') + m.modelCode).toLowerCase().includes(q))
  }, [modelOptions, query, defaultQuery])
  const selectedModelOption = useMemo(
    () => modelOptions.find((model) => model.modelCode === selectedModelCode) ?? null,
    [modelOptions, selectedModelCode]
  )

  const canConfirm = Boolean(selected) || Boolean(selectedModelCode.trim())

  const handleConfirm = () => {
    if (!canConfirm) return
    const mc = selectedModelCode.trim()
    onSelect?.({
      ...(selected ? { agent: selected } : {}),
      ...(mc ? { modelCode: mc } : {})
    })
    onOpenChange?.(false)
  }

  const handleCancel = () => {
    onOpenChange?.(false)
  }

  return (
    <Modal
      open={open}
      width={1100}
      footer={null}
      closable
      centered
      className="agent-picker-modal"
      wrapClassName="create-flow-modal agent-picker-wrap"
      onCancel={handleCancel}
      title={<ModalTitleWatermark title="选择智能体" />}
    >
      <div className="picker-shell">
        <div className="picker-search">
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索智能体..."
            allowClear
            size="large"
            className="picker-search-input"
            prefix={<SearchOutlined className="picker-search-ico" />}
          />
        </div>

        <div className="picker-scroll">
          <h3 className="picker-section-label">我的智能体</h3>
          {filtered.length === 0 ? (
            <div className="picker-empty">
              <p>未找到相关智能体</p>
            </div>
          ) : (
            <div className="agent-grid agent-grid--agents">
              {filtered.map((agent) => (
                <button
                  key={agent.id}
                  type="button"
                  className={
                    selected?.id === agent.id
                      ? 'agent-card agent-card--agent agent-card--selected'
                      : 'agent-card agent-card--agent'
                  }
                  onClick={() => selectAgent(agent)}
                >
                  <div className="agent-card__avatar-wrap">
                    {agent.thumbnail ? (
                      <ShimmerImage
                        src={agent.thumbnail}
                        alt={agent.name}
                        imgClass="agent-card__avatar"
                        wrapperClass="agent-card__avatar-shimmer"
                        objectFit="cover"
                        revealDirection="fade"
                        minShimmerMs={280}
                      />
                    ) : (
                      <div className="agent-card__avatar agent-card__avatar--placeholder">
                        {agent.name.slice(0, 1)}
                      </div>
                    )}
                  </div>
                  <div className="agent-card__info">
                    <div className="agent-card__name">{agent.name}</div>
                    {agent.desc ? <div className="agent-card__desc">{agent.desc}</div> : null}
                  </div>
                </button>
              ))}
            </div>
          )}

          {showModelSection ? (
            <>
              <h3 className="picker-section-label picker-section-label--models">可用模型</h3>
              {modelsLoading ? (
                <div className="picker-models-loading">模型加载中…</div>
              ) : filteredModels.length === 0 ? (
                <div className="picker-models-empty">暂无可用模型，将使用智能体默认模型</div>
              ) : (
                <>
                  <div className="agent-grid agent-grid--models">
                    {filteredModels.map((model) => (
                      <button
                        key={model.modelCode}
                        type="button"
                        className={
                          selectedModelCode === model.modelCode
                            ? 'agent-card agent-card--model agent-card--selected'
                            : 'agent-card agent-card--model'
                        }
                        onClick={() => selectModel(model.modelCode)}
                      >
                        <div className="agent-card__avatar-wrap">
                          {model.logo ? (
                            <img src={model.logo} alt={model.name} className="agent-card__avatar" />
                          ) : (
                            <div className="agent-card__avatar agent-card__avatar--placeholder agent-card__avatar--model">
                              {model.name.slice(0, 1)}
                            </div>
                          )}
                        </div>
                        <div className="agent-card__info">
                          <div className="agent-card__model-name-row">
                            <div className="agent-card__name">{model.name}</div>
                            {shouldShowModelFreeBadge(model) ? <ModelFreeBadge /> : null}
                          </div>
                          {model.desc ? <div className="agent-card__desc">{model.desc}</div> : null}
                        </div>
                      </button>
                    ))}
                  </div>
                  {selectedModelOption?.billing ? (
                    <div className="picker-model-billing-detail" aria-live="polite">
                      <span className="picker-model-billing-detail__label">
                        {selectedModelOption.name} 价格
                      </span>
                      <ModelBillingRules billing={selectedModelOption.billing} maxRules={3} />
                    </div>
                  ) : null}
                </>
              )}
            </>
          ) : null}
        </div>

        <div className="picker-footer">
          <Button className="picker-btn-cancel" size="large" onClick={handleCancel}>
            <div className="text-gradient">取消</div>
          </Button>
          <Button
            type="primary"
            size="large"
            className="picker-btn-ok"
            disabled={!canConfirm}
            onClick={handleConfirm}
          >
            确定
          </Button>
        </div>
      </div>
    </Modal>
  )
}

export default AgentPickerModal
