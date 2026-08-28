'use client'

import { useEffect, useState } from 'react'
import { CloseOutlined, DownOutlined, PlusOutlined, UpOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { useReferenceAudioPreview } from '~/composables/useReferenceAudioPreview'
import audioIconRaw from '~/assets/img/icon/music-nor.svg'
import { assetUrl } from '~/utils/assetUrl'
import {
  getOverflowReferenceStripEntries,
  getPinnedReferenceStripEntries,
  shouldAutoCollapseReferenceStrip,
  shouldShowReferenceStripCollapseToggle
} from '~/utils/referenceImagesStripCollapse'
import './GenerateSourceImagesStrip.css'

const audioIconUrl = assetUrl(audioIconRaw)

export type GenerateSourceStripImage = {
  id?: string | number
  url?: string
  thumbnail?: string
  title?: string
  name?: string
  kind?: 'image' | 'audio'
  audioSource?: 'voice_sample' | 'upload'
  /** 音频时长（毫秒），素材条左上角展示秒数 */
  durationMs?: number
}

export interface GenerateSourceImagesStripProps {
  images: GenerateSourceStripImage[]
  /** scene：场景/对话作图条；i2v：分镜视频文本域上方条 */
  variant?: 'scene' | 'i2v'
  showAdder?: boolean
  showAdderText?: boolean
  adderText?: string
  emptyHint?: string
  /** 点击缩略图是否触发 preview（分镜视频参考图需要） */
  enablePreview?: boolean
  /** 场景条用 ShimmerImage；i2v 条保持原生 img 与现网一致 */
  useShimmer?: boolean
  onRemove: (index: number) => void
  onPreview?: (img: GenerateSourceStripImage) => void
  onOpenAdder?: () => void
}

export function GenerateSourceImagesStrip({
  images,
  variant = 'scene',
  showAdder = false,
  showAdderText = true,
  adderText = '导入参考图',
  emptyHint = '',
  enablePreview = false,
  useShimmer = true,
  onRemove,
  onPreview,
  onOpenAdder
}: GenerateSourceImagesStripProps) {
  const [expanded, setExpanded] = useState(false)
  const { playingId, play } = useReferenceAudioPreview()

  function isAudioItem(img: GenerateSourceStripImage) {
    return (
      img?.kind === 'audio' || img?.audioSource === 'voice_sample' || img?.audioSource === 'upload'
    )
  }

  function audioPlayKey(img: GenerateSourceStripImage, idx: number) {
    return `strip-audio-${img.id ?? idx}-${img.url || ''}`
  }

  function isAudioPlaying(img: GenerateSourceStripImage, idx: number) {
    return isAudioItem(img) && playingId === audioPlayKey(img, idx)
  }

  /** 左上角秒数：有 durationMs 才展示 */
  function formatAudioDurationSec(img: GenerateSourceStripImage): string {
    const ms = Number(img?.durationMs)
    if (!Number.isFinite(ms) || ms <= 0) return ''
    const sec = ms / 1000
    if (sec >= 10) return `${Math.round(sec)}s`
    const rounded = Math.round(sec * 10) / 10
    return `${rounded}s`
  }

  const showCollapseToggle = shouldShowReferenceStripCollapseToggle(images?.length ?? 0)

  const pinnedEntries = getPinnedReferenceStripEntries(images ?? []).map((e) => ({
    img: e.item,
    originalIndex: e.originalIndex
  }))

  const overflowEntries = getOverflowReferenceStripEntries(images ?? []).map((e) => ({
    img: e.item,
    originalIndex: e.originalIndex
  }))

  const imagesCount = images?.length ?? 0
  useEffect(() => {
    if (shouldAutoCollapseReferenceStrip(imagesCount)) {
      setExpanded(false)
    }
  }, [imagesCount])

  function resolveSrc(img: GenerateSourceStripImage) {
    return String(img?.url || img?.thumbnail || '')
  }

  function resolveAlt(img: GenerateSourceStripImage, idx: number) {
    return img?.title || img?.name || `参考图${idx + 1}`
  }

  function resolveItemKey(img: GenerateSourceStripImage, idx: number) {
    return img?.id != null ? `ref-${img.id}` : `${resolveSrc(img)}-${idx}`
  }

  function onThumbClick(img: GenerateSourceStripImage, idx = 0) {
    if (isAudioItem(img)) {
      void play(String(img.url || ''), audioPlayKey(img, idx))
      return
    }
    if (!enablePreview) return
    if (img?.url || img?.thumbnail) {
      onPreview?.(img)
    }
  }

  function renderThumb(entry: { img: GenerateSourceStripImage; originalIndex: number }) {
    const { img, originalIndex } = entry
    const thumbClass = [
      'generate-source-thumb',
      enablePreview || isAudioItem(img) ? 'is-clickable' : '',
      isAudioItem(img) ? 'generate-source-thumb--audio' : '',
      isAudioPlaying(img, originalIndex) ? 'is-playing' : ''
    ]
      .filter(Boolean)
      .join(' ')
    return (
      <div
        key={resolveItemKey(img, originalIndex)}
        className={thumbClass}
        onClick={() => onThumbClick(img, originalIndex)}
      >
        {isAudioItem(img) ? (
          <>
            {formatAudioDurationSec(img) ? (
              <span className="generate-source-audio-duration">
                {formatAudioDurationSec(img)}
              </span>
            ) : null}
            {isAudioPlaying(img, originalIndex) ? (
              <span className="generate-source-audio-eq" aria-hidden="true">
                <span className="generate-source-eq-bar generate-source-eq-bar-1" />
                <span className="generate-source-eq-bar generate-source-eq-bar-2" />
                <span className="generate-source-eq-bar generate-source-eq-bar-3" />
              </span>
            ) : (
              <img className="generate-source-audio-icon" src={audioIconUrl} alt="" />
            )}
            <span className="generate-source-audio-name">{resolveAlt(img, originalIndex)}</span>
          </>
        ) : useShimmer ? (
          <ShimmerImage
            src={resolveSrc(img)}
            alt={resolveAlt(img, originalIndex)}
            imgClass="generate-source-thumb__image"
            objectFit="cover"
            revealDirection="fade"
          />
        ) : (
          <img src={resolveSrc(img)} alt={resolveAlt(img, originalIndex)} />
        )}
        <button
          type="button"
          className="generate-source-thumb__remove"
          onClick={(e) => {
            e.stopPropagation()
            onRemove(originalIndex)
          }}
        >
          <CloseOutlined />
        </button>
      </div>
    )
  }

  return (
    <div className={`generate-source-images-strip generate-source-images-strip--${variant}`}>
      {/*
        单行 flex-wrap 流：前 4 张 → 导入按钮 →（展开）溢出卡片 → 箭头
        导入按钮始终插在第 4 张后，不会被挤到列表末尾；箭头始终跟在当前可见卡片之后
      */}
      <div className="generate-source-images-list">
        {pinnedEntries.map((entry) => renderThumb(entry))}

        {showAdder ? (
          <button
            type="button"
            className="generate-source-thumb generate-source-thumb--adder"
            onClick={() => onOpenAdder?.()}
          >
            <PlusOutlined />
            {showAdderText ? <span className="adder-text">{adderText}</span> : null}
          </button>
        ) : null}

        {emptyHint ? <span className="generate-source-empty-hint">{emptyHint}</span> : null}

        {expanded ? overflowEntries.map((entry) => renderThumb(entry)) : null}

        {showCollapseToggle ? (
          <button
            type="button"
            className="generate-source-collapse-toggle"
            aria-expanded={expanded}
            aria-label={expanded ? '收起参考图列表' : '展开全部参考图'}
            onClick={() => setExpanded((v) => !v)}
          >
            {expanded ? <UpOutlined /> : <DownOutlined />}
          </button>
        ) : null}
      </div>
    </div>
  )
}

export default GenerateSourceImagesStrip
