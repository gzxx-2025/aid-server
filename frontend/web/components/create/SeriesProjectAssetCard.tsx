'use client'

import { message } from 'antd'
import { useEffect,useState } from 'react'
import deleteWhiteRaw from '~/assets/img/home/delete-white.svg'
import editWhiteRaw from '~/assets/img/home/edit-white.svg'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { assetUrl } from '~/utils/assetUrl'
import { emptyImageIconUrl as defaultCoverRaw } from '~/utils/emptyImageIcon'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import './SeriesProjectAssetCard.css'
const deleteWhite = assetUrl(deleteWhiteRaw)
const editWhite = assetUrl(editWhiteRaw)
const defaultCoverUrl = assetUrl(defaultCoverRaw)

export interface SeriesProjectAssetCardProps {
  title: string
  coverImage: string
  /** 接口 createTime / updateTime，有则展示为生成日期 */
  dateLabel?: string
  onDelete?: () => void
  onEdit?: () => void
}

function resolveCover(url: string | undefined) {
  const t = (url || '').trim()
  return t || defaultCoverUrl
}

/** 与 components/home/AssetsLibraryPanel 中资产卡片 DOM 结构一致 */
export function SeriesProjectAssetCard({
  title,
  coverImage,
  dateLabel = '',
  onDelete,
  onEdit
}: SeriesProjectAssetCardProps) {
  const [displayCover, setDisplayCover] = useState(() => resolveCover(coverImage))
  const [coverFailed, setCoverFailed] = useState(false)

  useEffect(() => {
    setCoverFailed(false)
    setDisplayCover(resolveCover(coverImage))
  }, [coverImage])

  const hasRealCover = (() => {
    if (coverFailed) return false
    const url = (displayCover || '').trim()
    return !!url && url !== defaultCoverUrl
  })()

  function onCoverError() {
    setCoverFailed(true)
    setDisplayCover(defaultCoverUrl)
  }

  function openPreview() {
    if (!hasRealCover) {
      message.info('该资产暂无图片可预览')
      return
    }
    openImagePreviewModal({
      url: displayCover,
      title
    })
  }

  const formattedDate = (() => {
    const raw = (dateLabel || '').trim()
    if (!raw) return ''
    const date = new Date(raw)
    if (Number.isNaN(date.getTime())) return ''
    return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
  })()

  return (
    <article className="series-project-asset-card works-lib-card" onClick={openPreview}>
      <div
        className={`works-lib-card__cover${hasRealCover ? '' : ' works-lib-card__cover--placeholder'}`}
      >
        {hasRealCover ? (
          <ShimmerImage
            src={displayCover}
            alt={title}
            imgClass="works-lib-card__cover-img"
            wrapperClass="works-lib-card__cover-shimmer"
            objectFit="cover"
            revealDirection="fade"
            minShimmerMs={280}
            onError={onCoverError}
          />
        ) : (
          <img
            className="card-cover-placeholder-icon"
            src={defaultCoverUrl}
            alt=""
            width={88}
            height={88}
            draggable={false}
          />
        )}
        <div className="works-lib-card__cover-actions">
          <button
            type="button"
            className="works-lib-card__cover-btn"
            aria-label="删除"
            onClick={(e) => {
              e.stopPropagation()
              onDelete?.()
            }}
          >
            <img src={deleteWhite} alt="" width={16} height={16} />
          </button>
          <button
            type="button"
            className="works-lib-card__cover-btn"
            aria-label="编辑"
            onClick={(e) => {
              e.stopPropagation()
              onEdit?.()
            }}
          >
            <img src={editWhite} alt="" width={16} height={16} />
          </button>
        </div>
      </div>
      <div className="works-lib-card__body">
        <h3 className="works-lib-card__title">{title}</h3>
        {formattedDate ? (
          <div className="works-lib-card__row works-lib-card__row--asset">
            <span className="works-lib-card__from">生成日期：{formattedDate}</span>
          </div>
        ) : null}
      </div>
    </article>
  )
}

export default SeriesProjectAssetCard
