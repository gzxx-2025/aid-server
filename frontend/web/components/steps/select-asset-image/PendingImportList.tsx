'use client'

import audioIconRaw from '@/assets/img/icon/music-nor.svg'
import { CloseOutlined } from '@ant-design/icons'
import iconStartRaw from '~/assets/img/icon/icon_start.svg'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { assetUrl } from '~/utils/assetUrl'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { isAudioPendingItem,rowKey } from './assetGroups'
const iconStartUrl = assetUrl(iconStartRaw)
const audioIconUrl = assetUrl(audioIconRaw)
const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export interface PendingImportListProps {
  selectedList: any[]
  isPendingAudioPlaying: (item: any) => boolean
  onPreview: (item: any) => void
  onRemove: (item: any) => void
}

/** 底部「已导入素材」待确认清单（图片 / 参考音频混排） */
export function PendingImportList({
  selectedList,
  isPendingAudioPlaying,
  onPreview,
  onRemove
}: PendingImportListProps) {
  if (selectedList.length === 0) return null
  return (
    <div className="saim-pending-wrap">
      <div className="saim-pending-title">已导入素材（{selectedList.length}）</div>
      <div className="saim-pending-list">
        {selectedList.map((item, index) => (
          <div
            key={rowKey(item)}
            className={`saim-pending-item${isAudioPendingItem(item) ? ' saim-pending-item--audio' : ''}`}
          >
            <button
              type="button"
              className={`saim-pending-thumb${isAudioPendingItem(item) ? ' saim-pending-thumb--audio' : ''}${isPendingAudioPlaying(item) ? ' is-playing' : ''}`}
              title={
                isAudioPendingItem(item)
                  ? isPendingAudioPlaying(item)
                    ? '暂停试听'
                    : '试听'
                  : item.title || item.name || '预览'
              }
              onClick={() => onPreview(item)}
            >
              {isAudioPendingItem(item) ? (
                <>
                  <img className="saim-pending-audio-icon" src={audioIconUrl} alt="" />
                  <span className="saim-pending-audio-mask" aria-hidden="true" />
                  <span className="saim-pending-audio-overlay" aria-hidden="true">
                    {isPendingAudioPlaying(item) ? (
                      <span className="saim-pending-audio-eq">
                        <span className="saim-pending-eq-bar saim-pending-eq-bar-1" />
                        <span className="saim-pending-eq-bar saim-pending-eq-bar-2" />
                        <span className="saim-pending-eq-bar saim-pending-eq-bar-3" />
                      </span>
                    ) : (
                      <img
                        className="saim-pending-audio-play"
                        src={iconStartUrl}
                        alt=""
                        width={16}
                        height={16}
                      />
                    )}
                  </span>
                </>
              ) : item.url || item.thumbnail ? (
                <ShimmerImage
                  src={String(item.url || item.thumbnail || '')}
                  imgClass="saim-pending-thumb__img"
                  wrapperClass="saim-pending-thumb__shimmer"
                  objectFit="cover"
                  revealDirection="fade"
                />
              ) : (
                <img
                  src={emptyImageIconUrl}
                  alt=""
                  className="empty-image-icon empty-image-icon--xs saim-pending-thumb__empty"
                />
              )}
            </button>
            {isAudioPendingItem(item) ? (
              <button
                type="button"
                className="saim-pending-name saim-pending-name--audio"
                title={item.title || item.name}
                onClick={() => onPreview(item)}
              >
                {item.title || item.name || `音频${index + 1}`}
              </button>
            ) : (
              <span className="saim-pending-name" title={item.title || item.name}>
                {item.title || item.name || `图片${index + 1}`}
              </span>
            )}
            <button
              type="button"
              className="saim-pending-remove"
              aria-label="删除"
              onClick={() => onRemove(item)}
            >
              <CloseOutlined />
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

export default PendingImportList
