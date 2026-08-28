'use client'

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState
} from 'react'
import { Button, Switch, Upload, message } from 'antd'
import type { UploadFile, UploadProps } from 'antd'
import {
  CaretRightOutlined,
  DeleteOutlined,
  UserOutlined,
  RightOutlined,
  DownOutlined,
  UpOutlined,
  InboxOutlined
} from '@ant-design/icons'
import RichTextEditor from '~/components/common/RichTextEditor'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import type { BillingQuoteRequest } from '~/types/business-api'
import { htmlPlainTextLength, isHtmlContentEmpty } from '~/utils/htmlPlain'
import starWhiteRaw from '@/assets/img/icon/star_white.svg'
import { assetUrl } from '~/utils/assetUrl'
import './DubbingEditLeftPanel.css'

const starWhiteIconUrl = assetUrl(starWhiteRaw)

export interface DubbingEditLeftPanelProps {
  dialogue: string
  emotion: string
  /** 情绪选项（由父弹窗拉取 tags 后下发，避免重复请求） */
  emotionOptions?: string[]
  lipSync: boolean
  voiceName: string
  voiceAvatarUrl?: string
  /** 试听接口请求中 */
  ttsPreviewLoading?: boolean
  /** 试听音频播放中（展示动效） */
  ttsPreviewPlaying?: boolean
  /** 最近一次试听成功后的音频时长（秒） */
  ttsPreviewDurationSec?: number | null
  billingRequest?: BillingQuoteRequest | null
  onDialogueChange: (v: string) => void
  onEmotionChange: (v: string) => void
  onLipSyncChange: (v: boolean) => void
  onVoiceNameChange: (v: string) => void
  onPreviewListen: () => void
  onPickVoice: () => void
  onStartDubbing: (payload: { mode: 'tts' | 'upload'; localFile: File | null }) => void
}

/** 原 defineExpose({ leftTab, getLocalAudioFile }) 契约 */
export interface DubbingEditLeftPanelHandle {
  readonly leftTab: 'tts' | 'upload'
  getLocalAudioFile: () => File | null
}

const EMOTION_GRID_COLS = 3
const EMOTION_COLLAPSED_ROWS = 4
const EMOTION_COLLAPSED_COUNT = EMOTION_GRID_COLS * EMOTION_COLLAPSED_ROWS

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function formatPreviewDuration(sec: number) {
  if (!Number.isFinite(sec) || sec <= 0) return '—'
  return `${sec.toFixed(1)} 秒`
}

export const DubbingEditLeftPanel = forwardRef<
  DubbingEditLeftPanelHandle,
  DubbingEditLeftPanelProps
>(function DubbingEditLeftPanel(
  {
    dialogue,
    emotion,
    emotionOptions = [],
    lipSync,
    voiceName,
    voiceAvatarUrl,
    ttsPreviewLoading = false,
    ttsPreviewPlaying = false,
    ttsPreviewDurationSec = null,
    billingRequest = null,
    onDialogueChange,
    onEmotionChange,
    onLipSyncChange,
    onPreviewListen,
    onPickVoice,
    onStartDubbing
  },
  ref
) {
  const EMOTIONS = emotionOptions?.length
    ? emotionOptions
    : ['中性', '开心', '悲伤', '愤怒', '激动']

  const [emotionExpanded, setEmotionExpanded] = useState(false)
  const [emotionModel, setEmotionModel] = useState(emotion || '中性')

  const emotionOverflowCount = Math.max(0, EMOTIONS.length - EMOTION_COLLAPSED_COUNT)

  /** 折叠时固定 4 行；若当前选中不在前 12 项，替换末位以保证选中可见 */
  const visibleEmotions = (() => {
    const all = EMOTIONS
    if (emotionExpanded || all.length <= EMOTION_COLLAPSED_COUNT) return all
    const head = all.slice(0, EMOTION_COLLAPSED_COUNT)
    const selected = String(emotionModel || '').trim()
    if (!selected || head.includes(selected)) return head
    const next = head.slice(0, EMOTION_COLLAPSED_COUNT - 1)
    next.push(selected)
    return next
  })()

  const [leftTab, setLeftTab] = useState<'tts' | 'upload'>('tts')
  const leftTabRef = useRef(leftTab)
  leftTabRef.current = leftTab
  const [uploadFileList, setUploadFileList] = useState<UploadFile[]>([])
  const localAudioFileRef = useRef<File | null>(null)
  const [localAudioName, setLocalAudioName] = useState('')

  const [dialogueModel, setDialogueModel] = useState(dialogue)
  const dialoguePlainLen = htmlPlainTextLength(dialogueModel)
  const [lipSyncModel, setLipSyncModel] = useState(lipSync)
  const [voiceNameModel, setVoiceNameModel] = useState(voiceName)
  const voiceNameModelRef = useRef(voiceNameModel)
  voiceNameModelRef.current = voiceNameModel
  const dialogueModelRef = useRef(dialogueModel)
  dialogueModelRef.current = dialogueModel
  const [viewportHeight, setViewportHeight] = useState(1080)
  const dubbingQuote = useBillingQuote(leftTab === 'tts' ? billingRequest : null)

  const dubbingEditorHeight = (() => {
    const vp = clamp(viewportHeight, 768, 1400)
    const ratio = (vp - 768) / (1400 - 768)
    const height = Math.round(96 + ratio * (190 - 96))
    return `${height}px`
  })()

  useEffect(() => {
    if (typeof window === 'undefined') return
    const updateViewportHeight = () => {
      setViewportHeight(window.innerHeight)
    }
    updateViewportHeight()
    window.addEventListener('resize', updateViewportHeight)
    return () => {
      window.removeEventListener('resize', updateViewportHeight)
    }
  }, [])

  // 原 watch(props.*)：父级回写时同步本地模型
  useEffect(() => {
    setDialogueModel(dialogue)
  }, [dialogue])
  useEffect(() => {
    setEmotionModel(emotion || '中性')
  }, [emotion])
  useEffect(() => {
    setLipSyncModel(!!lipSync)
  }, [lipSync])
  useEffect(() => {
    setVoiceNameModel(voiceName || '')
  }, [voiceName])

  // 原 watch(xxxModel) → emit('update:xxx')：本地变更时通知父级
  function updateDialogueModel(v: string) {
    setDialogueModel(v)
    onDialogueChange(v)
  }
  function updateEmotionModel(v: string) {
    setEmotionModel(v)
    onEmotionChange(v)
  }
  function updateLipSyncModel(v: boolean) {
    setLipSyncModel(v)
    onLipSyncChange(v)
  }

  const beforeUploadAudio: UploadProps['beforeUpload'] = (file) => {
    localAudioFileRef.current = file as unknown as File
    setLocalAudioName((file as unknown as File).name)
    return false
  }

  function onRemoveUpload() {
    localAudioFileRef.current = null
    setLocalAudioName('')
  }

  function onStartDubbingClick() {
    if (leftTabRef.current === 'upload') {
      if (!localAudioFileRef.current) {
        message.warning('请先上传音频文件')
        return
      }
      onStartDubbing({ mode: 'upload', localFile: localAudioFileRef.current })
      return
    }
    if (isHtmlContentEmpty(dialogueModelRef.current)) {
      message.warning('请先为分镜添加台词')
      return
    }
    const vn = (voiceNameModelRef.current || '').trim()
    if (!vn || vn === '无音色') {
      message.warning('请先选择配音音色')
      return
    }
    onStartDubbing({ mode: 'tts', localFile: null })
  }

  useImperativeHandle(ref, () => ({
    get leftTab() {
      return leftTabRef.current
    },
    getLocalAudioFile: () => localAudioFileRef.current
  }))

  return (
    <div className="dubbing-edit-left">
      <div className="config-tabs config-tabs--two">
        <button
          type="button"
          className={`config-tab${leftTab === 'tts' ? ' active' : ''}`}
          onClick={() => setLeftTab('tts')}
        >
          文本朗读
        </button>
        <button
          type="button"
          className={`config-tab${leftTab === 'upload' ? ' active' : ''}`}
          onClick={() => setLeftTab('upload')}
        >
          上传本地配音
        </button>
      </div>

      <div className="dubbing-edit-scroll">
        {leftTab === 'tts' ? (
          <div className="dubbing-left-tts">
            <RichTextEditor
              value={dialogueModel}
              onChange={updateDialogueModel}
              minHeight={dubbingEditorHeight}
              maxHeight={dubbingEditorHeight}
              maxLength={50}
              placeholder="请输入要配音的台词"
              className="dubbing-dialogue-input"
            />
            <div className="dubbing-tts-actions">
              <Button
                type="link"
                className={`dubbing-preview-btn${ttsPreviewPlaying ? ' is-playing' : ''}`}
                loading={ttsPreviewLoading}
                disabled={ttsPreviewLoading}
                onClick={() => onPreviewListen()}
              >
                <span className="dubbing-preview-btn__inner">
                  {ttsPreviewPlaying && !ttsPreviewLoading ? (
                    <span className="voice-preview-eq" aria-hidden="true">
                      <span className="voice-preview-eq-bar voice-preview-eq-bar-1" />
                      <span className="voice-preview-eq-bar voice-preview-eq-bar-2" />
                      <span className="voice-preview-eq-bar voice-preview-eq-bar-3" />
                    </span>
                  ) : !ttsPreviewLoading ? (
                    <CaretRightOutlined />
                  ) : null}
                  <span>
                    {ttsPreviewLoading ? '生成中…' : ttsPreviewPlaying ? '播放中' : '试听'}
                  </span>
                </span>
              </Button>
              <span className="dubbing-tts-hint">试听音色可获取准确的语调时长</span>
              {ttsPreviewDurationSec != null && !ttsPreviewLoading ? (
                <span className="dubbing-tts-duration">
                  时长 {formatPreviewDuration(ttsPreviewDurationSec)}
                </span>
              ) : null}
            </div>
            <div className="dubbing-dialogue-footer">
              <span className="dubbing-char-count">{dialoguePlainLen}/50</span>
              <button
                type="button"
                className="dubbing-clear-btn"
                aria-label="清空"
                onClick={() => updateDialogueModel('')}
              >
                <DeleteOutlined />
              </button>
            </div>

            <div className="dubbing-field dubbing-voice-field">
              <div className="dubbing-field-label">配音音色：</div>
              <button type="button" className="dubbing-voice-picker" onClick={() => onPickVoice()}>
                <div className="dubbing-voice-avatar">
                  {!voiceAvatarUrl ? <UserOutlined /> : <img src={voiceAvatarUrl} alt="" />}
                </div>
                <span className="dubbing-voice-name">{voiceNameModel || '无音色'}</span>
                <RightOutlined className="dubbing-voice-arrow" />
              </button>
            </div>

            <div className="dubbing-field dubbing-emotion-field">
              <div className="dubbing-emotion-header">
                <div className="dubbing-field-label">情感</div>
                {emotionOverflowCount > 0 ? (
                  <span className="dubbing-emotion-count">共 {EMOTIONS.length}</span>
                ) : null}
              </div>
              <div
                className={`dubbing-emotion-shell${
                  !emotionExpanded && emotionOverflowCount > 0 ? ' is-collapsed' : ''
                }`}
              >
                <div className="dubbing-emotion-grid">
                  {visibleEmotions.map((emo) => (
                    <button
                      key={emo}
                      type="button"
                      className={`dubbing-emotion-btn${emotionModel === emo ? ' active' : ''}`}
                      onClick={() => updateEmotionModel(emo)}
                    >
                      {emo}
                    </button>
                  ))}
                </div>
                {!emotionExpanded && emotionOverflowCount > 0 ? (
                  <div className="dubbing-emotion-fade" aria-hidden="true" />
                ) : null}
              </div>
              {emotionOverflowCount > 0 ? (
                <button
                  type="button"
                  className="dubbing-emotion-toggle"
                  aria-expanded={emotionExpanded}
                  onClick={() => setEmotionExpanded((v) => !v)}
                >
                  <span>{emotionExpanded ? '收起' : `展开全部（+${emotionOverflowCount}）`}</span>
                  {emotionExpanded ? (
                    <UpOutlined className="dubbing-emotion-toggle-icon" />
                  ) : (
                    <DownOutlined className="dubbing-emotion-toggle-icon" />
                  )}
                </button>
              ) : null}
            </div>
          </div>
        ) : (
          <div className="dubbing-left-upload">
            <Upload.Dragger
              fileList={uploadFileList}
              onChange={({ fileList }) => setUploadFileList(fileList)}
              name="file"
              maxCount={1}
              accept="audio/*,.mp3,.wav,.m4a,.aac"
              beforeUpload={beforeUploadAudio}
              onRemove={onRemoveUpload}
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">点击或拖拽音频文件到此区域</p>
              <p className="ant-upload-hint">支持常见音频格式，将用于本段分镜配音</p>
            </Upload.Dragger>
            {localAudioName ? (
              <p className="dubbing-upload-name">已选：{localAudioName}</p>
            ) : null}
          </div>
        )}
      </div>

      <div className="dubbing-bottom-actions">
        <div className="dubbing-lip-row">
          <span className="dubbing-lip-label">对口型</span>
          <Switch checked={lipSyncModel} onChange={(v) => updateLipSyncModel(v)} />
        </div>
        <BillingQuoteHint
          quote={dubbingQuote.quote}
          loading={dubbingQuote.loading}
          error={dubbingQuote.error}
          active={leftTab === 'tts' && Boolean(billingRequest)}
          className="billing-quote-hint--submit"
        />
        <Button
          type="primary"
          block
          size="large"
          className="dubbing-start-btn"
          onClick={onStartDubbingClick}
        >
          <img src={starWhiteIconUrl} alt="" />
          开始配音
        </Button>
      </div>
    </div>
  )
})

/** 原 emit('update:voiceName') 契约在 React 侧由 onVoiceNameChange 承接（父级选完音色后回写 props） */
export default DubbingEditLeftPanel
