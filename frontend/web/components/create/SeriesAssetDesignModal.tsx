'use client'

/**
 * 剧集角色/道具设计弹窗（原 components/create/SeriesAssetDesignModal.vue）。
 * 原组件无外部调用点（与源项目一致，保留为独立组件）：
 * - props：open / assetType / projectId / episodeId
 * - emits：update:open → onOpenChange；success → onSuccess
 */

import {
CheckOutlined,
CloseOutlined,
LoadingOutlined,
UserAddOutlined
} from '@ant-design/icons'
import { Button,Input,Modal,Select,message } from 'antd'
import { useEffect,useRef,useState,type ChangeEvent } from 'react'
import starWhiteIconRaw from '~/assets/img/icon/star_white.svg'
import { assetUrl } from '~/utils/assetUrl'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import {
ensureSeriesAssetDraft,
generateSeriesAssetImage,
uploadSeriesAssetManualImage,
validateSeriesAssetDesignForm,
type SeriesAssetDesignType,
type SeriesAssetDraftState,
type SeriesCharacterDesignForm,
type SeriesPropDesignForm
} from '~/utils/seriesAssetDesignFlow'
import './SeriesAssetDesignModal.css'
const starWhiteIconUrl = assetUrl(starWhiteIconRaw)

interface Props {
  open: boolean
  assetType: SeriesAssetDesignType
  projectId: number | null
  episodeId: number | null
  onOpenChange: (value: boolean) => void
  onSuccess?: () => void
}

const ageOptions = [
  { label: '儿童', value: '儿童' },
  { label: '少年', value: '少年' },
  { label: '青年', value: '青年' },
  { label: '中年', value: '中年' },
  { label: '老年', value: '老年' }
]

const genderOptions = [
  { label: '男', value: '男' },
  { label: '女', value: '女' },
  { label: '其他', value: '其他' }
]

const emptyCharacterForm = (): SeriesCharacterDesignForm => ({
  name: '',
  ageRange: '青年',
  gender: '男',
  introduction: '',
  backgroundStory: ''
})

const emptyPropForm = (): SeriesPropDesignForm => ({
  name: '',
  summary: '',
  introduction: ''
})

export function SeriesAssetDesignModal({
  open,
  assetType,
  projectId,
  episodeId,
  onOpenChange,
  onSuccess
}: Props) {
  const modalTitle = assetType === 'character' ? '角色设计' : '道具设计'

  const [characterForm, setCharacterForm] = useState<SeriesCharacterDesignForm>(emptyCharacterForm)
  const [propForm, setPropForm] = useState<SeriesPropDesignForm>(emptyPropForm)

  const draftRef = useRef<SeriesAssetDraftState | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [imageGenerating, setImageGenerating] = useState(false)
  const [generatingText, setGeneratingText] = useState('正在生成图片…')
  const [confirming, setConfirming] = useState(false)
  const [uploading, setUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const busy = imageGenerating || confirming || uploading
  const busyRef = useRef(busy)
  busyRef.current = busy

  /** 事件回调读取最新表单（异步流程期间用户可能继续编辑） */
  const characterFormRef = useRef(characterForm)
  characterFormRef.current = characterForm
  const propFormRef = useRef(propForm)
  propFormRef.current = propForm

  function activeForm(): SeriesCharacterDesignForm | SeriesPropDesignForm {
    return assetType === 'character' ? { ...characterFormRef.current } : { ...propFormRef.current }
  }

  function resetState() {
    setCharacterForm(emptyCharacterForm())
    setPropForm(emptyPropForm())
    draftRef.current = null
    setPreviewUrl(null)
    setImageGenerating(false)
    setGeneratingText('正在生成图片…')
    setConfirming(false)
    setUploading(false)
  }

  function ensureContext(): { projectId: number; episodeId: number } | null {
    const pid = Number(projectId)
    const eid = Number(episodeId)
    if (!Number.isFinite(pid) || pid <= 0) {
      message.warning('缺少项目信息')
      return null
    }
    if (!Number.isFinite(eid) || eid <= 0) {
      message.warning('请先新增至少一集后再添加资产')
      return null
    }
    return { projectId: pid, episodeId: eid }
  }

  // 原 watch([open, assetType, projectId])：关闭时重置
  useEffect(() => {
    if (!open) {
      resetState()
    }
     
  }, [open, assetType, projectId])

  function handleCancel() {
    if (busyRef.current) return
    onOpenChange(false)
  }

  function handleManualUpload() {
    if (busyRef.current) return
    const err = validateSeriesAssetDesignForm(assetType, activeForm())
    if (err) {
      message.warning(err)
      return
    }
    fileInputRef.current?.click()
  }

  async function onFilePicked(e: ChangeEvent<HTMLInputElement>) {
    const input = e.target
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    const ctx = ensureContext()
    if (!ctx) return

    setUploading(true)
    try {
      const url = await uploadImageToOssWithToast(file)
      if (!url) return
      draftRef.current = await uploadSeriesAssetManualImage({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId,
        assetType,
        form: activeForm(),
        imageUrl: url,
        draft: draftRef.current
      })
      setPreviewUrl(url)
      message.success('图片上传成功')
    } catch (err: unknown) {
      const e2 = err as { msg?: string; message?: string }
      message.error(e2?.msg || e2?.message || '上传失败')
    } finally {
      setUploading(false)
    }
  }

  async function handleAutoGenerate() {
    if (busyRef.current) return
    const err = validateSeriesAssetDesignForm(assetType, activeForm())
    if (err) {
      message.warning(err)
      return
    }
    const ctx = ensureContext()
    if (!ctx) return

    setImageGenerating(true)
    setGeneratingText('正在生成图片…')
    try {
      draftRef.current = await generateSeriesAssetImage({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId,
        assetType,
        form: activeForm(),
        draft: draftRef.current,
        onProgress: (p) => {
          if (p.message) setGeneratingText(p.message)
        }
      })
      setPreviewUrl(draftRef.current.imageUrl ?? null)
    } catch (e: unknown) {
      const e2 = e as { msg?: string; message?: string }
      message.error(e2?.msg || e2?.message || '生成失败')
    } finally {
      setImageGenerating(false)
    }
  }

  async function handleConfirm() {
    if (busyRef.current) return
    const err = validateSeriesAssetDesignForm(assetType, activeForm())
    if (err) {
      message.warning(err)
      return
    }
    const ctx = ensureContext()
    if (!ctx) return

    setConfirming(true)
    try {
      if (!draftRef.current) {
        draftRef.current = await ensureSeriesAssetDraft({
          projectId: ctx.projectId,
          episodeId: ctx.episodeId,
          assetType,
          form: activeForm()
        })
      } else {
        draftRef.current = await ensureSeriesAssetDraft({
          projectId: ctx.projectId,
          episodeId: ctx.episodeId,
          assetType,
          form: activeForm(),
          existing: draftRef.current
        })
      }
      message.success(assetType === 'character' ? '角色已保存' : '道具已保存')
      onSuccess?.()
      onOpenChange(false)
    } catch (e: unknown) {
      const e2 = e as { msg?: string; message?: string }
      message.error(e2?.msg || e2?.message || '保存失败')
    } finally {
      setConfirming(false)
    }
  }

  return (
    <Modal
      open={open}
      width={1100}
      footer={null}
      title={null}
      closable={false}
      centered
      className="series-asset-design-modal"
      wrapClassName="create-flow-modal series-asset-design-modal-wrap"
      onCancel={handleCancel}
    >
      <div className="sad-shell">
        <header className="sad-header">
          <h2 className="sad-title">{modalTitle}</h2>
          <button
            type="button"
            className="sad-close"
            aria-label="关闭"
            disabled={busy}
            onClick={handleCancel}
          >
            <CloseOutlined />
          </button>
        </header>

        <div className="sad-body">
          <section className="sad-media">
            <div className="sad-media__frame">
              {imageGenerating ? (
                <div className="sad-media__generating" role="status" aria-live="polite">
                  <LoadingOutlined spin className="sad-media__generating-icon" />
                  <p className="sad-media__generating-text">{generatingText}</p>
                </div>
              ) : previewUrl ? (
                <>
                  <img src={previewUrl} alt="" className="sad-media__preview" />
                  <div className="sad-media__overlay-actions">
                    <button
                      type="button"
                      className="sad-action-btn sad-action-btn--ghost sad-action-btn--compact"
                      disabled={busy}
                      onClick={handleManualUpload}
                    >
                      重新上传
                    </button>
                    <button
                      type="button"
                      className="sad-action-btn sad-action-btn--primary sad-action-btn--compact"
                      disabled={busy}
                      onClick={() => void handleAutoGenerate()}
                    >
                      重新生成
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <div className="sad-media__placeholder">
                    <UserAddOutlined className="sad-media__placeholder-icon" />
                  </div>
                  <div className="sad-media__actions">
                    <button
                      type="button"
                      className="sad-action-btn sad-action-btn--ghost"
                      disabled={busy}
                      onClick={handleManualUpload}
                    >
                      手动上传
                    </button>
                    <button
                      type="button"
                      className="sad-action-btn sad-action-btn--primary"
                      disabled={busy}
                      onClick={() => void handleAutoGenerate()}
                    >
                      <img src={starWhiteIconUrl} alt="" className="sad-action-btn__star" />
                      智能生成
                    </button>
                  </div>
                </>
              )}
            </div>
          </section>

          <section className="sad-form asset-form-modal">
            {assetType === 'character' ? (
              <>
                <div className="sad-field">
                  <label className="sad-label">名称</label>
                  <Input
                    value={characterForm.name}
                    onChange={(e) => setCharacterForm({ ...characterForm, name: e.target.value })}
                    maxLength={100}
                    placeholder="请输入角色名称"
                    disabled={busy}
                  />
                </div>
                <div className="sad-field">
                  <label className="sad-label">年龄</label>
                  <Select
                    value={characterForm.ageRange}
                    options={ageOptions}
                    placeholder="请选择"
                    disabled={busy}
                    onChange={(v) => setCharacterForm({ ...characterForm, ageRange: v })}
                  />
                </div>
                <div className="sad-field">
                  <label className="sad-label">性别</label>
                  <Select
                    value={characterForm.gender}
                    options={genderOptions}
                    placeholder="请选择"
                    disabled={busy}
                    onChange={(v) => setCharacterForm({ ...characterForm, gender: v })}
                  />
                </div>
                <div className="sad-field">
                  <label className="sad-label">人物描述</label>
                  <Input.TextArea
                    value={characterForm.introduction}
                    onChange={(e) =>
                      setCharacterForm({ ...characterForm, introduction: e.target.value })
                    }
                    rows={3}
                    maxLength={500}
                    placeholder="请输入人物描述"
                    disabled={busy}
                  />
                </div>
                <div className="sad-field">
                  <label className="sad-label">背景故事</label>
                  <Input.TextArea
                    value={characterForm.backgroundStory}
                    onChange={(e) =>
                      setCharacterForm({ ...characterForm, backgroundStory: e.target.value })
                    }
                    rows={4}
                    maxLength={1000}
                    placeholder="请输入背景故事"
                    disabled={busy}
                  />
                </div>
              </>
            ) : (
              <>
                <div className="sad-field">
                  <label className="sad-label">名称</label>
                  <Input
                    value={propForm.name}
                    onChange={(e) => setPropForm({ ...propForm, name: e.target.value })}
                    maxLength={100}
                    placeholder="请输入道具名称"
                    disabled={busy}
                  />
                </div>
                <div className="sad-field">
                  <label className="sad-label">道具概要</label>
                  <Input.TextArea
                    value={propForm.summary}
                    onChange={(e) => setPropForm({ ...propForm, summary: e.target.value })}
                    rows={3}
                    maxLength={500}
                    placeholder="请输入道具概要"
                    disabled={busy}
                  />
                </div>
                <div className="sad-field">
                  <label className="sad-label">道具描述</label>
                  <Input.TextArea
                    value={propForm.introduction}
                    onChange={(e) => setPropForm({ ...propForm, introduction: e.target.value })}
                    rows={5}
                    maxLength={1000}
                    placeholder="请输入道具描述"
                    disabled={busy}
                  />
                </div>
              </>
            )}
          </section>
        </div>

        <footer className="sad-footer">
          <Button className="sad-footer-btn sad-footer-btn--ghost" disabled={busy} onClick={handleCancel}>
            取消
          </Button>
          <Button
            type="primary"
            className="sad-footer-btn sad-footer-btn--ok"
            loading={confirming}
            disabled={busy && !confirming}
            icon={<CheckOutlined />}
            onClick={() => void handleConfirm()}
          >
            确认
          </Button>
        </footer>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        className="sad-file-input"
        onChange={(e) => void onFilePicked(e)}
      />
    </Modal>
  )
}

export default SeriesAssetDesignModal
