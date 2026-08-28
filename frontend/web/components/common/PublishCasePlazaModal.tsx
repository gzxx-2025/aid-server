'use client'

/**
 * 发布至案例广场弹窗（原 components/common/PublishCasePlazaModal.vue）。
 * - props：open / projectId / initialCoverUrl? / initialProjectDesc? / okText?
 * - emits：update:open → onOpenChange；success → onSuccess（项目更新成功后抛出，由入口继续提审/发布）
 */

import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Button, Input, Modal, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import deleteWhiteIconRaw from '~/assets/img/home/delete-white.svg'
import { assetUrl } from '~/utils/assetUrl'
import { PreviewableImageThumb } from '~/components/common/PreviewableImageThumb'
import { userProjectUpdate } from '~/utils/businessApi'
import { isProjectPublicLockError, projectPublicLockUserHint } from '~/utils/projectAudit'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import {
  fetchUserProjectDetailOnce,
  invalidateUserProjectDetailCache
} from '~/utils/userProjectDetailOnce'
import './PublishCasePlazaModal.css'

const deleteWhiteIcon = assetUrl(deleteWhiteIconRaw)

const DESC_MAX = 500
const MODAL_WIDTH = 680

export interface PublishCasePlazaModalProps {
  open: boolean
  projectId: number | null
  /** 打开时优先回显，避免多余 detail 请求 */
  initialCoverUrl?: string | null
  initialProjectDesc?: string | null
  okText?: string
  onOpenChange: (value: boolean) => void
  onSuccess?: (payload: { projectId: number; coverUrl: string; projectDesc: string }) => void
}

export function PublishCasePlazaModal({
  open,
  projectId,
  initialCoverUrl = '',
  initialProjectDesc = '',
  okText = '确认发布',
  onOpenChange,
  onSuccess
}: PublishCasePlazaModalProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [coverUrl, setCoverUrl] = useState('')
  const [projectDesc, setProjectDesc] = useState('')
  const [hydrating, setHydrating] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const busy = hydrating || uploading || submitting
  const busyRef = useRef(busy)
  busyRef.current = busy

  /** 提交时读最新表单值（textarea 输入是受控异步） */
  const coverUrlRef = useRef(coverUrl)
  coverUrlRef.current = coverUrl
  const projectDescRef = useRef(projectDesc)
  projectDescRef.current = projectDesc

  function resetForm() {
    setCoverUrl('')
    setProjectDesc('')
    setHydrating(false)
    setUploading(false)
    setSubmitting(false)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  async function hydrateForm() {
    const pid = Number(projectId)
    if (!Number.isFinite(pid) || pid <= 0) {
      message.error('缺少项目信息')
      onOpenChange(false)
      return
    }

    // 打开时先清空，避免 modal 实例复用时残留上一作品表单
    setCoverUrl('')
    setProjectDesc('')

    const initialCover = String(initialCoverUrl || '').trim()
    const initialDesc = String(initialProjectDesc || '').trim()

    // 入口已带齐字段则不再请求 detail（仅作乐观回显；两侧都有才跳过）
    if (initialCover && initialDesc) {
      setCoverUrl(initialCover)
      setProjectDesc(initialDesc.slice(0, DESC_MAX))
      return
    }

    let localCover = ''
    let localDesc = ''
    if (initialCover) {
      localCover = initialCover
      setCoverUrl(initialCover)
    }
    if (initialDesc) {
      localDesc = initialDesc.slice(0, DESC_MAX)
      setProjectDesc(localDesc)
    }

    setHydrating(true)
    try {
      const detail = await fetchUserProjectDetailOnce(pid)
      const detailCover = String(detail.coverUrl || '').trim()
      const detailDesc = String(detail.projectDesc || '')
        .trim()
        .slice(0, DESC_MAX)
      // 已拉取当前 project 的 detail 时，以服务端为准（含空描述），避免 store/initial 跨作品残留盖住真相
      if (detailCover) {
        localCover = detailCover
        setCoverUrl(detailCover)
      }
      localDesc = detailDesc
      setProjectDesc(detailDesc)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      // 有局部 initial 时详情失败不阻断编辑
      if (!localCover && !localDesc) {
        message.error(err?.msg || err?.message || '加载项目信息失败')
        onOpenChange(false)
      }
    } finally {
      setHydrating(false)
    }
  }

  // 原 watch([open, projectId])
  useEffect(() => {
    if (!open) {
      resetForm()
      return
    }
    void hydrateForm()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectId])

  function triggerPickCover() {
    if (busyRef.current) return
    fileInputRef.current?.click()
  }

  function clearCover() {
    if (busyRef.current) return
    setCoverUrl('')
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  async function onFilePicked(e: ChangeEvent<HTMLInputElement>) {
    const input = e.target
    const file = input.files?.[0]
    input.value = ''
    if (!file || busyRef.current) return

    if (!file.type.startsWith('image/')) {
      message.error('只能上传图片文件')
      return
    }

    setUploading(true)
    try {
      const url = await uploadImageToOssWithToast(file)
      if (!url) return
      setCoverUrl(url)
    } finally {
      setUploading(false)
    }
  }

  function handleCancel() {
    if (busyRef.current) return
    onOpenChange(false)
  }

  async function handleConfirm(e?: { preventDefault?: () => void }) {
    e?.preventDefault?.()
    if (busyRef.current) return

    const pid = Number(projectId)
    if (!Number.isFinite(pid) || pid <= 0) {
      message.error('缺少项目信息')
      return
    }

    const nextCover = coverUrlRef.current.trim()
    const nextDesc = projectDescRef.current.trim()
    if (!nextCover) {
      message.warning('请上传作品封面')
      return
    }
    if (!nextDesc) {
      message.warning('请填写作品描述')
      return
    }
    if (nextDesc.length > DESC_MAX) {
      message.warning(`作品描述最多 ${DESC_MAX} 字`)
      return
    }

    setSubmitting(true)
    try {
      await userProjectUpdate({
        id: pid,
        coverUrl: nextCover,
        projectDesc: nextDesc
      })
      invalidateUserProjectDetailCache(pid)
      onOpenChange(false)
      onSuccess?.({ projectId: pid, coverUrl: nextCover, projectDesc: nextDesc })
    } catch (err: unknown) {
      if (isProjectPublicLockError(err)) {
        message.error(projectPublicLockUserHint())
        return
      }
      const e2 = err as { msg?: string; message?: string }
      message.error(e2?.msg || e2?.message || '更新项目失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      open={open}
      title="发布至案例广场"
      width={MODAL_WIDTH}
      centered
      destroyOnHidden
      mask={{ closable: !busy }}
      closable={!busy}
      wrapClassName="create-flow-modal publish-case-plaza-modal-wrap"
      className="publish-case-plaza-modal"
      onCancel={handleCancel}
      footer={
        <div className="publish-case-plaza__footer">
          <Button className="publish-case-plaza__btn-cancel" disabled={busy} onClick={handleCancel}>
            <span className="text-gradient">取消</span>
          </Button>
          <Button
            type="primary"
            className="publish-case-plaza__btn-ok"
            loading={submitting}
            disabled={busy && !submitting}
            onClick={(e) => void handleConfirm(e)}
          >
            {okText}
          </Button>
        </div>
      }
    >
      {hydrating ? (
        <div className="publish-case-plaza__loading">加载中…</div>
      ) : (
        <div className="publish-case-plaza">
          <p className="publish-case-plaza__tip">
            请上传作品封面并填写介绍，发布后将在案例广场展示。
          </p>

          <div className="publish-case-plaza__field">
            <label className="publish-case-plaza__label">
              作品封面<span className="publish-case-plaza__required">*</span>
            </label>
            <div className="publish-case-plaza__cover">
              {/* 已有封面：预览；悬停显示删除，删后可重新上传 */}
              {coverUrl ? (
                <div className="publish-case-plaza__cover-preview">
                  <PreviewableImageThumb
                    src={coverUrl}
                    alt="作品封面"
                    title="作品封面"
                    objectFit="cover"
                  />
                  <button
                    type="button"
                    className="publish-case-plaza__cover-delete"
                    disabled={busy}
                    aria-label="删除封面"
                    title="删除封面"
                    onClick={(e) => {
                      e.stopPropagation()
                      clearCover()
                    }}
                  >
                    <img src={deleteWhiteIcon} alt="" width={14} height={14} />
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  className="publish-case-plaza__cover-btn"
                  disabled={busy}
                  aria-label="上传封面"
                  onClick={triggerPickCover}
                >
                  <span className="publish-case-plaza__cover-empty">
                    <PlusOutlined />
                    <span>{uploading ? '上传中…' : '上传封面'}</span>
                  </span>
                </button>
              )}
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              tabIndex={-1}
              aria-hidden="true"
              className="publish-case-plaza__file"
              onChange={(e) => void onFilePicked(e)}
            />
          </div>

          <div className="publish-case-plaza__field">
            <label className="publish-case-plaza__label" htmlFor="publish-case-plaza-desc">
              作品描述<span className="publish-case-plaza__required">*</span>
            </label>
            <Input.TextArea
              id="publish-case-plaza-desc"
              value={projectDesc}
              onChange={(e) => setProjectDesc(e.target.value)}
              rows={6}
              maxLength={500}
              showCount
              disabled={busy}
              placeholder="介绍一下你的作品（最多 500 字）"
            />
          </div>
        </div>
      )}
    </Modal>
  )
}

export default PublishCasePlazaModal
