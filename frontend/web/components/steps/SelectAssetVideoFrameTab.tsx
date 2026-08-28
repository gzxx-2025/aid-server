'use client'

import { useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import dialogSelectNorRaw from '@/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelRaw from '@/assets/img/icon/dialog-select-sel.svg'
import { assetUrl } from '~/utils/assetUrl'
import type { CapturedVideoFrame } from '~/utils/videoFrameCapture'
import {
  appendVideoFrame,
  listVideoFrames,
  type VideoFrameLocalItem
} from '~/utils/videoFrameLocalStore'
import { videoFrameScopeKey } from '~/utils/videoFrameScope'
import { CaptureVideoFrameModal } from './CaptureVideoFrameModal'
import './SelectAssetVideoFrameTab.css'

const dialogSelectNorIcon = assetUrl(dialogSelectNorRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelRaw)

export type VideoFrameAssetItem = {
  id: string
  url: string
  thumbnail: string
  title: string
  name: string
  importDate: string
  source: '视频帧'
  kind: 'image'
}

export interface SelectAssetVideoFrameTabProps {
  open: boolean
  projectId: number
  episodeId?: number | null
  isSelected?: (item: VideoFrameAssetItem) => boolean
  modalZIndex?: number
  onToggle: (item: VideoFrameAssetItem) => void
}

export function SelectAssetVideoFrameTab({
  open,
  projectId,
  episodeId = null,
  isSelected = () => false,
  modalZIndex = 1200,
  onToggle
}: SelectAssetVideoFrameTabProps) {
  const [frames, setFrames] = useState<VideoFrameLocalItem[]>([])
  const [captureModalOpen, setCaptureModalOpen] = useState(false)
  const [captureProjectId, setCaptureProjectId] = useState(0)
  const [captureEpisodeId, setCaptureEpisodeId] = useState<number | null>(null)
  const captureProjectIdRef = useRef(captureProjectId)
  captureProjectIdRef.current = captureProjectId
  const captureEpisodeIdRef = useRef(captureEpisodeId)
  captureEpisodeIdRef.current = captureEpisodeId

  function reloadFrames() {
    setFrames(listVideoFrames(projectId, episodeId))
  }

  function toAssetItem(frame: VideoFrameLocalItem): VideoFrameAssetItem {
    return {
      id: frame.id,
      url: frame.url,
      thumbnail: frame.thumbnail || frame.url,
      title: frame.name,
      name: frame.name,
      importDate: frame.createdAt,
      source: '视频帧',
      kind: 'image'
    }
  }

  function isFrameSelected(frame: VideoFrameLocalItem): boolean {
    return isSelected(toAssetItem(frame))
  }

  function toggleFrame(frame: VideoFrameLocalItem) {
    onToggle(toAssetItem(frame))
  }

  function openCaptureModal() {
    if (!Number.isFinite(Number(projectId)) || Number(projectId) <= 0) {
      message.error('项目无效，无法截帧')
      return
    }
    // 固定本次上传作用域，避免上传期间切作品后把旧帧写入新作品。
    setCaptureProjectId(Number(projectId))
    const epId = Number(episodeId)
    setCaptureEpisodeId(Number.isFinite(epId) && epId > 0 ? epId : null)
    setCaptureModalOpen(true)
  }

  function createFrameId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return `vf-${crypto.randomUUID()}`
    }
    return `vf-${Date.now()}-${Math.random().toString(36).slice(2)}`
  }

  function onCaptured(payload: CapturedVideoFrame) {
    const saved = appendVideoFrame(captureProjectIdRef.current, captureEpisodeIdRef.current, {
      id: createFrameId(),
      url: payload.url,
      thumbnail: payload.url,
      name: payload.name,
      sourceVideoId: payload.sourceVideoId,
      sourceLabel: payload.sourceLabel,
      capturedAtMs: payload.capturedAtMs
    })
    const capturedScope = videoFrameScopeKey(captureProjectIdRef.current, captureEpisodeIdRef.current)
    const currentScope = videoFrameScopeKey(projectId, episodeId)
    if (capturedScope === currentScope) setFrames((prev) => [...prev, saved])
  }

  function formatDate(value: string): string {
    if (!value) return ''
    return new Date(value).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }

  useEffect(() => {
    if (open) reloadFrames()
    else setCaptureModalOpen(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const scopeMountedRef = useRef(false)
  useEffect(() => {
    // 原 watch([projectId, episodeId]) 非 immediate：跳过首次执行
    if (!scopeMountedRef.current) {
      scopeMountedRef.current = true
      return
    }
    setCaptureModalOpen(false)
    if (open) reloadFrames()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, episodeId])

  return (
    <div className="savft-root">
      <div className="savft-scroll">
        <div className="savft-grid">
          <button type="button" className="savft-add-card" onClick={openCaptureModal}>
            <PlusOutlined className="savft-add-card__icon" />
            <span>新增视频帧</span>
          </button>

          {frames.map((frame) => (
            <button
              key={frame.id}
              type="button"
              className={`savft-card${isFrameSelected(frame) ? ' is-selected' : ''}`}
              title={frame.name}
              onClick={() => toggleFrame(frame)}
            >
              <div className="savft-card__media">
                <ShimmerImage
                  src={frame.thumbnail || frame.url}
                  imgClass="savft-card__img"
                  wrapperClass="savft-card__shimmer"
                  objectFit="cover"
                  revealDirection="fade"
                />
                <img
                  className="savft-card__select"
                  src={isFrameSelected(frame) ? dialogSelectSelIcon : dialogSelectNorIcon}
                  alt=""
                />
              </div>
              <span className="savft-card__meta">{formatDate(frame.createdAt)}</span>
            </button>
          ))}
        </div>
      </div>

      <CaptureVideoFrameModal
        open={captureModalOpen}
        onOpenChange={setCaptureModalOpen}
        projectId={captureProjectId}
        episodeId={captureEpisodeId}
        zIndex={modalZIndex}
        onCaptured={onCaptured}
      />
    </div>
  )
}

export default SelectAssetVideoFrameTab
