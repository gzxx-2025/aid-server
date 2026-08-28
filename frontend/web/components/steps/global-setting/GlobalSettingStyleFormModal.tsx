'use client'

import { forwardRef, useImperativeHandle, useMemo, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { Button, Form, Input, Modal, Select, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { userAssetCustomCreate } from '~/utils/businessApi'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'

export interface GlobalSettingStyleFormModalHandle {
  /** 原 openCreateStyleModal：重置表单并打开弹窗（锁定/提交中判断由调用方完成） */
  openCreateStyleModal: () => void
  /** 提交中禁止重复打开（原 creatingStyle 闸门） */
  isCreatingStyle: () => boolean
}

export interface GlobalSettingStyleFormModalProps {
  /** 提交时二次校验风格锁（弹窗打开期间锁可能生效） */
  styleLocked: boolean
  styleLockActionMessage: string
  /** 创建成功（弹窗已关、toast 已出）后由父级刷新列表并选中新风格 */
  onCreated: (createdId: number) => Promise<void>
}

/** GlobalSetting「新增风格」弹窗（自 GlobalSetting.vue 原样拆出：表单、封面上传、提交） */
export const GlobalSettingStyleFormModal = forwardRef<
  GlobalSettingStyleFormModalHandle,
  GlobalSettingStyleFormModalProps
>(function GlobalSettingStyleFormModal({ styleLocked, styleLockActionMessage, onCreated }, ref) {
  const styleLockedRef = useRef(styleLocked)
  styleLockedRef.current = styleLocked
  const styleLockActionMessageRef = useRef(styleLockActionMessage)
  styleLockActionMessageRef.current = styleLockActionMessage

  const [styleFormOpen, setStyleFormOpen] = useState(false)
  const [creatingStyle, setCreatingStyle] = useState(false)
  const creatingStyleRef = useRef(false)
  const [styleCoverUploading, setStyleCoverUploading] = useState(false)
  const styleCoverUploadingRef = useRef(false)
  const styleCoverInputRef = useRef<HTMLInputElement | null>(null)
  const [styleForm, setStyleForm] = useState({
    assetType: 'style',
    assetName: '',
    personalityDesc: '',
    promptText: '',
    imageUrl: '',
    remark: ''
  })
  const styleFormRef = useRef(styleForm)
  styleFormRef.current = styleForm

  const styleTypeSelectOptions = useMemo(() => [{ value: 'style', label: '风格' }], [])

  function patchStyleForm(patch: Partial<typeof styleForm>) {
    const next = { ...styleFormRef.current, ...patch }
    styleFormRef.current = next
    setStyleForm(next)
  }

  function openCreateStyleModal() {
    styleCoverUploadingRef.current = false
    setStyleCoverUploading(false)
    const next = {
      assetType: 'style',
      assetName: '',
      personalityDesc: '',
      promptText: '',
      imageUrl: '',
      remark: ''
    }
    styleFormRef.current = next
    setStyleForm(next)
    setStyleFormOpen(true)
  }

  useImperativeHandle(ref, () => ({
    openCreateStyleModal,
    isCreatingStyle: () => creatingStyleRef.current
  }))

  function closeStyleFormModal() {
    setStyleFormOpen(false)
    styleCoverUploadingRef.current = false
    setStyleCoverUploading(false)
    if (styleCoverInputRef.current) styleCoverInputRef.current.value = ''
  }

  function triggerStyleCoverUpload() {
    if (styleCoverUploadingRef.current) return
    styleCoverInputRef.current?.click()
  }

  function clearStyleCover() {
    patchStyleForm({ imageUrl: '' })
    if (styleCoverInputRef.current) styleCoverInputRef.current.value = ''
  }

  async function onStyleCoverFileChange(event: ChangeEvent<HTMLInputElement>) {
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

    styleCoverUploadingRef.current = true
    setStyleCoverUploading(true)
    try {
      const url = await uploadImageToOssWithToast(file)
      if (url) patchStyleForm({ imageUrl: url })
    } finally {
      styleCoverUploadingRef.current = false
      setStyleCoverUploading(false)
    }
  }

  function validateStyleForm(): string | null {
    if (!styleFormRef.current.assetName.trim()) return '请填写资产名称'
    if (!styleFormRef.current.promptText.trim()) return '请填写风格提示词'
    return null
  }

  async function submitStyleForm() {
    if (styleLockedRef.current) {
      message.warning(styleLockActionMessageRef.current)
      setStyleFormOpen(false)
      return
    }
    const errText = validateStyleForm()
    if (errText) {
      message.warning(errText)
      return
    }
    creatingStyleRef.current = true
    setCreatingStyle(true)
    try {
      const payload = {
        assetType: 'style',
        assetName: styleFormRef.current.assetName.trim(),
        personalityDesc: styleFormRef.current.personalityDesc.trim() || undefined,
        promptText: styleFormRef.current.promptText.trim() || undefined,
        imageUrl: styleFormRef.current.imageUrl.trim() || undefined,
        remark: styleFormRef.current.remark.trim() || undefined
      }
      const created = await userAssetCustomCreate(payload)
      setStyleFormOpen(false)
      message.success('风格添加成功')

      await onCreated(created.id)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '添加风格失败')
    } finally {
      creatingStyleRef.current = false
      setCreatingStyle(false)
    }
  }

  return (
    <Modal
      open={styleFormOpen}
      footer={null}
      width="50%"
      wrapClassName="create-flow-modal"
      onCancel={closeStyleFormModal}
      title={<ModalTitleWatermark title="新增风格" watermark="STYLE" />}
    >
      <div className="asset-form-modal">
        <Form layout="vertical" className="asset-form-modal__grid">
          <Form.Item className="asset-form-item asset-form-item--full asset-form-item--upload">
            <div className="style-cover-upload style-cover-upload--header">
              <input
                ref={styleCoverInputRef}
                type="file"
                accept="image/*"
                hidden
                onChange={onStyleCoverFileChange}
              />
              <button
                type="button"
                className={`style-cover-upload__box${styleForm.imageUrl ? ' style-cover-upload__box--filled' : ''}`}
                disabled={styleCoverUploading}
                onClick={triggerStyleCoverUpload}
              >
                {styleForm.imageUrl ? (
                  <ShimmerImage
                    src={styleForm.imageUrl}
                    alt="风格图片"
                    wrapperClass="style-cover-upload__preview"
                    imgClass="style-cover-upload__img"
                    objectFit="cover"
                    revealDirection="fade"
                    minShimmerMs={280}
                  />
                ) : styleCoverUploading ? (
                  <span className="style-cover-upload__hint">上传中…</span>
                ) : (
                  <span className="style-cover-upload__placeholder">
                    <PlusOutlined className="style-cover-upload__icon" />
                    <span>上传图片</span>
                  </span>
                )}
              </button>
              <div className="style-cover-upload__meta">
                <p className="style-cover-upload__tip">支持 JPG、PNG，最大 10MB</p>
                {styleForm.imageUrl ? (
                  <a
                    className="style-cover-upload__remove"
                    onClick={(e) => {
                      e.preventDefault()
                      clearStyleCover()
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
              value={styleForm.assetType}
              disabled
              placeholder="类型"
              options={styleTypeSelectOptions}
            />
          </Form.Item>
          <Form.Item label="资产名称" required className="asset-form-item asset-form-item--half">
            <Input
              value={styleForm.assetName}
              maxLength={100}
              placeholder="请输入"
              onChange={(e) => patchStyleForm({ assetName: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="特征描述" className="asset-form-item asset-form-item--full">
            <Input.TextArea
              value={styleForm.personalityDesc}
              rows={4}
              maxLength={500}
              placeholder="请输入"
              onChange={(e) => patchStyleForm({ personalityDesc: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="提示词" required className="asset-form-item asset-form-item--full">
            <Input.TextArea
              value={styleForm.promptText}
              rows={4}
              placeholder="请输入用于生成画面的风格提示词"
              onChange={(e) => patchStyleForm({ promptText: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="备注" className="asset-form-item asset-form-item--full">
            <Input
              value={styleForm.remark}
              maxLength={500}
              placeholder="请输入"
              onChange={(e) => patchStyleForm({ remark: e.target.value })}
            />
          </Form.Item>
        </Form>

        <div className="asset-form-modal__footer">
          <Button onClick={closeStyleFormModal}>
            <div className="text-gradient">取消</div>
          </Button>
          <Button type="primary" loading={creatingStyle} onClick={submitStyleForm}>
            确定
          </Button>
        </div>
      </div>
    </Modal>
  )
})

export default GlobalSettingStyleFormModal
