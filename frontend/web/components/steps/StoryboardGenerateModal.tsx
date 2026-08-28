'use client'

import { useEffect, useMemo, useState, type ComponentType } from 'react'
import { Button, Modal, Select } from 'antd'
import { LoadingOutlined, ThunderboltOutlined } from '@ant-design/icons'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import AgentPickerModal from '~/components/steps/AgentPickerModal'
import { useCreationStore } from '~/stores/creation'
import type { BillingQuoteRequest } from '~/types/business-api'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { assetUrl } from '~/utils/assetUrl'
import { buildStoryboardScriptSubmitBody } from '~/utils/storyboardScriptSubmitBody'
import subtractIconRaw from '~/assets/img/icon/Subtract.svg'
import {
  usePromptDictionary,
  filterAspectRatiosForVideoModal,
  resolveShotDensityValue
} from '~/composables/usePromptDictionary'
import {
  useStoryboardGenerateAgents,
  type StoryboardAgent
} from './storyboard-generate-modal/useStoryboardGenerateAgents'
import './StoryboardGenerateModal.css'
import './storyboard-generate-modal/story-gen-figma.css'

const subtractIconUrl = assetUrl(subtractIconRaw)

/** StoryboardShotDensityEnum.value，如「精简模式」「标准模式」「细拆模式」 */
export type ShotDensity = string

/** 内置四款 id 或字典 ModelTypeEnum 的 value */
export type VideoModelId = string
export type AspectRatioOption = '16:9' | '9:16' | '4:3' | '1:1'
export type ResolutionOption = '720p' | '1080p'
export type SoundEffectsOption = 'none' | 'with-sound'

export interface StoryboardVideoGenerateSettings {
  agentId: string
  videoModel: string
  videoPromptModelCode?: string
  aspectRatio: AspectRatioOption
  resolution: ResolutionOption
  soundEffects: SoundEffectsOption
}

interface VideoModelOption {
  id: string
  name: string
  desc: string
  tag?: string
  icon: ComponentType<{ className?: string }>
  cost: string[]
}

const ViduIcon = ({ className }: { className?: string }) => (
  <div
    className={
      className
        ? `video-model-icon-svg video-model-icon-vidu ${className}`
        : 'video-model-icon-svg video-model-icon-vidu'
    }
  >
    V
  </div>
)

const DEFAULT_SHOT_DENSITY = '标准模式'
const VALID_RESOLUTION = new Set<string>(['720p', '1080p'])
const VALID_SOUND = new Set<string>(['none', 'with-sound'])
const STATIC_RESOLUTION_OPTIONS = [
  { value: '720p' as const, label: '720P' },
  { value: '1080p' as const, label: '1080P' }
]

interface Props {
  open: boolean
  mode: 'settings' | 'generate'
  /** 弹窗来源：分镜脚本 或 分镜视频，内容不同 */
  source?: 'script' | 'video'
  agent: StoryboardAgent
  shotDensity?: ShotDensity
  /** 分镜视频专用 */
  videoModel?: string
  aspectRatio?: AspectRatioOption
  resolution?: ResolutionOption
  soundEffects?: SoundEffectsOption
  costPerVideo?: number
  /** 与真实分镜脚本提交的 overwrite 保持一致，仅用于权威报价。 */
  quoteOverwrite?: boolean
  onOpenChange: (v: boolean) => void
  onSave?: (
    settings:
      | {
          agentId: string
          shotDensity: ShotDensity
          modelCode?: string
          manualAgentModelPick?: boolean
        }
      | StoryboardVideoGenerateSettings
  ) => void
  onConfirm?: (
    settings:
      | {
          agentId: string
          shotDensity: ShotDensity
          modelCode?: string
          manualAgentModelPick?: boolean
        }
      | StoryboardVideoGenerateSettings
  ) => void
}

function snapToFirstOption<T extends string>(current: T, allowed: T[], fallback: T): T {
  if (!allowed.length) return current ?? fallback
  if (allowed.includes(current)) return current
  return allowed[0]
}

export function StoryboardGenerateModal({
  open,
  mode = 'settings',
  source = 'script',
  agent,
  shotDensity = DEFAULT_SHOT_DENSITY,
  videoModel = 'vidu-q2-pro',
  aspectRatio = '16:9',
  resolution = '720p',
  soundEffects = 'with-sound',
  quoteOverwrite = false,
  onOpenChange,
  onSave,
  onConfirm
}: Props) {
  const {
    ensureLoaded,
    loaded: dictLoaded,
    aspectRatioEnumOptions,
    modelTypeEnumOptions,
    audioSourceEnumOptions,
    storyboardShotDensityEnumOptions
  } = usePromptDictionary()

  const aspectRatioOptionsResolved = filterAspectRatiosForVideoModal(aspectRatioEnumOptions).map(
    (r) => ({
      label: r.label,
      value: r.value as AspectRatioOption
    })
  )

  const videoModelOptionsResolved: VideoModelOption[] = modelTypeEnumOptions.map((e) => ({
    id: e.value,
    name: e.label,
    desc: '',
    icon: ViduIcon,
    cost: [] as string[]
  }))

  const shotDensityOptionsResolved = storyboardShotDensityEnumOptions.map((r) => ({
    value: r.value,
    label: r.value,
    desc: r.label
  }))

  const resolutionOptionsResolved = STATIC_RESOLUTION_OPTIONS.filter((r) =>
    VALID_RESOLUTION.has(r.value)
  ).map((r) => ({
    label: r.label,
    value: r.value as ResolutionOption
  }))

  const soundEffectsOptionsResolved = audioSourceEnumOptions
    .filter((r) => VALID_SOUND.has(r.value))
    .map((r) => ({ label: r.label, value: r.value as SoundEffectsOption }))

  const isFigmaLayout = source === 'script' || source === 'video'

  const modalWidth = isFigmaLayout ? 1100 : 560

  const agentColumnHeadTitle = source === 'video' ? '视觉导演' : '分镜编剧'

  const figmaTitleWatermark =
    mode === 'settings' ? 'CONFIG' : source === 'video' ? 'VIDEO' : 'EXTRACT'

  const modalTitle =
    mode === 'settings' ? '生成设置' : source === 'video' ? '自动生成分镜视频' : '自动生成分镜'

  const modalSubtitle = source === 'video' ? '为分镜视频设置生成参数' : '为分镜脚本设置生成参数'

  const currentAgent = agent
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  const currentEpisodeId = useCreationStore((state) => state.currentEpisodeId)
  const scriptModelCode = useCreationStore((state) => state.storyboardGenerateSettings.modelCode)
  const videoPromptModelCode = useCreationStore(
    (state) => state.storyboardVideoGenerateSettings.videoPromptModelCode
  )

  const {
    agentPickerOpen,
    setAgentPickerOpen,
    scriptAgentsLoading,
    activeAgentPickerList,
    agentPickerDefaultQuery,
    agentPickerFuncCode,
    agentPickerModelType,
    agentPickerInitialModelCode,
    loadScriptAgents,
    loadVideoAgents,
    openAgentPicker,
    onUnifiedAgentPicked
  } = useStoryboardGenerateAgents({ source, agent })

  const [localShotDensity, setLocalShotDensity] = useState<ShotDensity>(
    shotDensity ?? DEFAULT_SHOT_DENSITY
  )
  const [localVideoModel, setLocalVideoModel] = useState<string>(videoModel ?? 'vidu-q2-pro')
  const [localAspectRatio, setLocalAspectRatio] = useState<AspectRatioOption>(
    aspectRatio ?? '16:9'
  )
  const [localResolution, setLocalResolution] = useState<ResolutionOption>(resolution ?? '720p')
  const [localSoundEffects, setLocalSoundEffects] = useState<SoundEffectsOption>(
    soundEffects ?? 'with-sound'
  )

  const externalSettingsKey = [
    open ? 'open' : 'closed',
    source,
    shotDensity ?? '',
    videoModel ?? '',
    aspectRatio ?? '',
    resolution ?? '',
    soundEffects ?? ''
  ].join('|')
  const [lastExternalSettingsKey, setLastExternalSettingsKey] = useState(externalSettingsKey)
  if (lastExternalSettingsKey !== externalSettingsKey) {
    setLastExternalSettingsKey(externalSettingsKey)
    setLocalShotDensity(resolveShotDensityValue(shotDensity, storyboardShotDensityEnumOptions))
    setLocalVideoModel(videoModel ?? 'vidu-q2-pro')
    setLocalAspectRatio(aspectRatio ?? '16:9')
    setLocalResolution(resolution ?? '720p')
    setLocalSoundEffects(soundEffects ?? 'with-sound')
  }

  const selectedShotDensity = resolveShotDensityValue(
    localShotDensity,
    shotDensityOptionsResolved.map((option) => ({ value: option.value, label: option.desc }))
  )
  const selectedVideoModel = snapToFirstOption(
    localVideoModel,
    videoModelOptionsResolved.map((option) => option.id),
    'vidu-q2-pro'
  )
  const selectedAspectRatio = snapToFirstOption(
    localAspectRatio,
    aspectRatioOptionsResolved.map((option) => option.value) as AspectRatioOption[],
    '16:9'
  )
  const selectedResolution = snapToFirstOption(
    localResolution,
    resolutionOptionsResolved.map((option) => option.value) as ResolutionOption[],
    '720p'
  )
  const selectedSoundEffects = snapToFirstOption(
    localSoundEffects,
    soundEffectsOptionsResolved.map((option) => option.value) as SoundEffectsOption[],
    'with-sound'
  )

  const videoQuoteRequest = useMemo(() => {
    const projectId = Number(currentProjectId)
    const episodeId = Number(currentEpisodeId)
    if (
      source !== 'video' ||
      mode !== 'generate' ||
      !open ||
      !Number.isFinite(projectId) ||
      projectId <= 0
    ) return null
    return {
      quoteType: 'STORYBOARD_VIDEO_WITH_PROMPT',
      payload: {
        projectId,
        episodeId: Number.isFinite(episodeId) && episodeId >= 0 ? episodeId : 0,
        ...(currentAgent.id ? { agentCode: currentAgent.id } : {}),
        ...(String(videoPromptModelCode || '').trim()
          ? { modelCode: String(videoPromptModelCode).trim() }
          : {}),
        genModelName: selectedVideoModel,
        genAspectRatio: selectedAspectRatio,
        genResolution: selectedResolution,
        genGenerateAudio: selectedSoundEffects === 'with-sound'
      }
    }
  }, [
    currentEpisodeId,
    currentProjectId,
    mode,
    open,
    selectedAspectRatio,
    selectedResolution,
    selectedShotDensity,
    selectedSoundEffects,
    selectedVideoModel,
    source,
    videoPromptModelCode
  ])
  const [scriptQuoteRequest, setScriptQuoteRequest] = useState<BillingQuoteRequest | null>(null)
  useEffect(() => {
    let active = true
    if (source !== 'script' || mode !== 'generate' || !open) {
      setScriptQuoteRequest(null)
      return () => {
        active = false
      }
    }
    const store = useCreationStore.getState()
    void buildStoryboardScriptSubmitBody({
      store: {
        ...store,
        storyboardGenerateSettings: {
          ...store.storyboardGenerateSettings,
          agentId: currentAgent.id,
          modelCode: String(scriptModelCode || '').trim(),
          shotDensity: selectedShotDensity
        }
      },
      route: getRouteLikeSnapshot(),
      manualAgentModelPick: true,
      overwrite: quoteOverwrite
    }).then((submitBody) => {
      if (!active) return
      setScriptQuoteRequest(
        submitBody
          ? { quoteType: 'STORYBOARD_SCRIPT', payload: { ...submitBody } }
          : null
      )
    }).catch(() => {
      if (active) setScriptQuoteRequest(null)
    })
    return () => {
      active = false
    }
  }, [
    currentAgent.id,
    mode,
    open,
    quoteOverwrite,
    scriptModelCode,
    selectedShotDensity,
    source
  ])
  const quoteRequest = source === 'script' ? scriptQuoteRequest : videoQuoteRequest
  const generationQuote = useBillingQuote(quoteRequest)

  useEffect(() => {
    void ensureLoaded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 父级 v-if 挂载时 open 已为 true，无 immediate 则不会触发默认智能体加载
  useEffect(() => {
    if (open) {
      if (source === 'script') {
        void loadScriptAgents(true)
      } else {
        void loadVideoAgents(true)
      }
    }
    // 对齐原 watch(() => props.open)：仅 open 变化触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function handleCancel() {
    onOpenChange(false)
  }

  function handleSaveSettings() {
    if (source === 'video') {
      onSave?.({
        agentId: currentAgent.id,
        videoModel: selectedVideoModel,
        videoPromptModelCode: String(
          useCreationStore.getState().storyboardVideoGenerateSettings.videoPromptModelCode || ''
        ).trim(),
        aspectRatio: selectedAspectRatio,
        resolution: selectedResolution,
        soundEffects: selectedSoundEffects
      })
    } else {
      onSave?.({
        agentId: currentAgent.id,
        shotDensity: selectedShotDensity,
        modelCode:
          String(useCreationStore.getState().storyboardGenerateSettings.modelCode || '').trim() ||
          undefined
      })
    }
    onOpenChange(false)
  }

  function handleConfirm() {
    if (source === 'video') {
      onConfirm?.({
        agentId: currentAgent.id,
        videoModel: selectedVideoModel,
        videoPromptModelCode: String(
          useCreationStore.getState().storyboardVideoGenerateSettings.videoPromptModelCode || ''
        ).trim(),
        aspectRatio: selectedAspectRatio,
        resolution: selectedResolution,
        soundEffects: selectedSoundEffects
      })
    } else {
      onConfirm?.({
        agentId: currentAgent.id,
        shotDensity: selectedShotDensity,
        manualAgentModelPick: true,
        modelCode:
          String(useCreationStore.getState().storyboardGenerateSettings.modelCode || '').trim() ||
          undefined
      })
    }
    onOpenChange(false)
  }

  const videoSelectPopupProps = {
    classNames: { popup: { root: 'story-gen-video-select-dropdown' } }
  }

  return (
    <>
      <Modal
        open={open}
        width={modalWidth}
        footer={isFigmaLayout ? null : undefined}
        closable={isFigmaLayout}
        centered
        className={
          isFigmaLayout
            ? 'storyboard-generate-modal storyboard-generate-modal--figma'
            : 'storyboard-generate-modal'
        }
        wrapClassName="create-flow-modal"
        onCancel={handleCancel}
        title={
          isFigmaLayout ? (
            <ModalTitleWatermark title={modalTitle} watermark={figmaTitleWatermark} />
          ) : (
            <div className="modal-header">
              <span className="modal-title">{modalTitle}</span>
              <p className="modal-subtitle">{modalSubtitle}</p>
            </div>
          )
        }
      >
        {/* 分镜脚本 / 分镜视频：同宽 Figma 双栏；左侧智能体区与脚本完全一致 */}
        {isFigmaLayout ? (
          <div className="story-gen-figma">
            <div className="story-gen-figma__grid">
              <div className="story-gen-figma__col story-gen-figma__col--agent">
                <div className="story-gen-figma__label">智能体</div>
                <div className="story-gen-extract-columns extract-columns extract-columns--1">
                  <div className="extract-col">
                    <div className="extract-col__head">
                      <span className="extract-col__head-text">{agentColumnHeadTitle}</span>
                      {source === 'script' ? (
                        <span
                          className="extract-col__head-ico"
                          aria-hidden="true"
                          role="button"
                          tabIndex={0}
                          onClick={(e) => {
                            e.stopPropagation()
                            void openAgentPicker('script')
                          }}
                          onKeyDown={(e) => {
                            if (e.key !== 'Enter') return
                            e.preventDefault()
                            void openAgentPicker('script')
                          }}
                        >
                          <img src={subtractIconUrl} alt="" />
                        </span>
                      ) : (
                        <span
                          className="extract-col__head-ico"
                          aria-hidden="true"
                          role="button"
                          tabIndex={0}
                          onClick={(e) => {
                            e.stopPropagation()
                            void openAgentPicker('video')
                          }}
                          onKeyDown={(e) => {
                            if (e.key !== 'Enter') return
                            e.preventDefault()
                            void openAgentPicker('video')
                          }}
                        >
                          <img src={subtractIconUrl} alt="" />
                        </span>
                      )}
                    </div>
                    <div className="extract-col__box">
                      <button
                        type="button"
                        className="extract-col__card"
                        onClick={() => void openAgentPicker(source === 'video' ? 'video' : 'script')}
                      >
                        <figure className="extract-col__figure">
                          <div className="extract-col__img-wrap">
                            {currentAgent.thumbnail ? (
                              <ShimmerImage
                                src={currentAgent.thumbnail}
                                alt={currentAgent.name || '智能体'}
                                imgClass="extract-col__img"
                                wrapperClass="extract-col__img-shimmer"
                                objectFit="cover"
                                revealDirection="fade"
                                minShimmerMs={280}
                              />
                            ) : source === 'script' && scriptAgentsLoading ? (
                              <div className="extract-col__img-placeholder">
                                <LoadingOutlined spin />
                              </div>
                            ) : (
                              <div className="extract-col__img-placeholder">
                                <ThunderboltOutlined />
                              </div>
                            )}
                          </div>
                          <figcaption className="extract-col__caption">
                            <div className="extract-col__name">
                              {source === 'script' && scriptAgentsLoading
                                ? '加载智能体…'
                                : currentAgent.name || '点击选择智能体'}
                            </div>
                            <p className="extract-col__desc">
                              {currentAgent.desc ||
                                (currentAgent.name ? '暂无描述' : '从列表中选择适合本环节的智能体')}
                            </p>
                          </figcaption>
                        </figure>
                      </button>
                    </div>
                  </div>
                </div>
              </div>

              {/* 脚本：镜头密度 */}
              {source === 'script' ? (
                <div className="story-gen-figma__col story-gen-figma__col--density">
                  <div className="story-gen-figma__label">镜头密度</div>
                  {!dictLoaded ? (
                    <p className="story-gen-dict-placeholder">加载中…</p>
                  ) : !shotDensityOptionsResolved.length ? (
                    <p className="story-gen-dict-placeholder">暂无数据</p>
                  ) : (
                    <div className="story-gen-density-list" role="listbox" aria-label="镜头密度">
                      {shotDensityOptionsResolved.map((opt) => (
                        <button
                          key={opt.value}
                          type="button"
                          role="option"
                          aria-selected={selectedShotDensity === opt.value}
                          className={
                            selectedShotDensity === opt.value
                              ? 'story-gen-density-card story-gen-density-card--active'
                              : 'story-gen-density-card'
                          }
                          onClick={() => setLocalShotDensity(opt.value)}
                        >
                          <span className="story-gen-density-card__title">{opt.label}</span>
                          <span className="story-gen-density-card__desc">{opt.desc}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              ) : source === 'video' ? (
                /* 视频：生视频模型 + 比例 / 清晰度 / 音效（设计稿布局） */
                <div className="story-gen-figma__col story-gen-figma__col--video">
                  <div className="story-gen-figma__label">生视频模型</div>
                  {!dictLoaded ? (
                    <p className="story-gen-dict-placeholder">加载中…</p>
                  ) : !videoModelOptionsResolved.length ? (
                    <p className="story-gen-dict-placeholder">暂无数据</p>
                  ) : (
                    <div className="story-gen-vm-grid" role="listbox" aria-label="生视频模型">
                      {videoModelOptionsResolved.map((m) => (
                        <button
                          key={m.id}
                          type="button"
                          role="option"
                          aria-selected={selectedVideoModel === m.id}
                          className={
                            selectedVideoModel === m.id
                              ? 'story-gen-vm-card story-gen-vm-card--active'
                              : 'story-gen-vm-card'
                          }
                          onClick={() => setLocalVideoModel(m.id)}
                        >
                          <div className="story-gen-vm-card__top">
                            {m.tag ? (
                              <span className="story-gen-vm-card__tag">{m.tag}</span>
                            ) : (
                              <div className="story-gen-vm-card__tag-spacer" aria-hidden="true" />
                            )}
                            <div className="story-gen-vm-card__icon-wrap">
                              <m.icon className="story-gen-vm-card__icon-el" />
                            </div>
                          </div>
                          <div className="story-gen-vm-card__name">{m.name}</div>
                          <div className="story-gen-vm-card__desc">{m.desc}</div>
                          <div className="story-gen-vm-card__costs">
                            {m.cost.map((c, i) => (
                              <span key={i} className="story-gen-vm-card__cost">
                                {c}
                              </span>
                            ))}
                          </div>
                        </button>
                      ))}
                    </div>
                  )}

                  <div className="story-gen-video-select-row">
                    <div className="story-gen-video-field story-gen-video-field--select">
                      <div className="story-gen-video-field__label">比例</div>
                      <Select
                        value={selectedAspectRatio}
                        onChange={(v) => setLocalAspectRatio(v)}
                        className="story-gen-video-select"
                        {...videoSelectPopupProps}
                        options={aspectRatioOptionsResolved}
                        disabled={!aspectRatioOptionsResolved.length}
                        notFoundContent="暂无数据"
                        size="large"
                      />
                    </div>
                    <div className="story-gen-video-field story-gen-video-field--select">
                      <div className="story-gen-video-field__label">清晰度</div>
                      <Select
                        value={selectedResolution}
                        onChange={(v) => setLocalResolution(v)}
                        className="story-gen-video-select"
                        {...videoSelectPopupProps}
                        options={resolutionOptionsResolved}
                        disabled={!resolutionOptionsResolved.length}
                        notFoundContent="暂无数据"
                        size="large"
                      />
                    </div>
                    <div className="story-gen-video-field story-gen-video-field--select">
                      <div className="story-gen-video-field__label">音效</div>
                      <Select
                        value={selectedSoundEffects}
                        onChange={(v) => setLocalSoundEffects(v)}
                        className="story-gen-video-select"
                        {...videoSelectPopupProps}
                        options={soundEffectsOptionsResolved}
                        disabled={!soundEffectsOptionsResolved.length}
                        notFoundContent="暂无数据"
                        size="large"
                      />
                    </div>
                  </div>
                </div>
              ) : null}
            </div>

            <div className="story-gen-figma__footer">
              <BillingQuoteHint
                quote={generationQuote.quote}
                loading={generationQuote.loading}
                error={generationQuote.error}
                active={Boolean(quoteRequest)}
              />
              <div className="story-gen-figma__footer-actions">
                <Button className="story-gen-figma__btn-cancel" size="large" onClick={handleCancel}>
                  <div className="text-gradient">取消</div>
                </Button>
                {mode === 'settings' ? (
                  <Button
                    type="primary"
                    size="large"
                    className="story-gen-figma__btn-ok"
                    onClick={handleSaveSettings}
                  >
                    保存设置
                  </Button>
                ) : (
                  <Button
                    type="primary"
                    size="large"
                    className="story-gen-figma__btn-ok"
                    onClick={handleConfirm}
                  >
                    确定
                  </Button>
                )}
              </div>
            </div>
          </div>
        ) : null}
      </Modal>

      {isFigmaLayout ? (
        <AgentPickerModal
          open={agentPickerOpen}
          onOpenChange={setAgentPickerOpen}
          agents={activeAgentPickerList}
          defaultQuery={agentPickerDefaultQuery}
          funcCode={agentPickerFuncCode}
          modelType={agentPickerModelType}
          initialModelCode={agentPickerInitialModelCode}
          onSelect={onUnifiedAgentPicked}
        />
      ) : null}
    </>
  )
}

export default StoryboardGenerateModal
