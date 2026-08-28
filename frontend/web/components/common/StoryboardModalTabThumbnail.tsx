'use client'

import { LoadingOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { VideoPosterThumb } from '~/components/common/VideoPosterThumb'
import { VIDEO_POSTER_PRIORITY } from '~/utils/ensureVideoPoster'
import { isProbablyImageUrl } from '~/utils/mediaLoadGate'

export interface StoryboardModalTabThumbnailProps {
  generating?: boolean
  /** 分镜图封面：有则立即展示，避免抽帧卡 loading */
  coverImageUrl?: string
  /** 视频 URL：无封面时再抽帧 / video 兜底 */
  videoUrl?: string
  /** 是否当前选中 Tab（影响抽帧优先级与懒加载） */
  isActive?: boolean
}

/**
 * 分镜视频 / 配音弹窗顶部 Tab 缩略图（封面优先，无封面再视频抽帧）。
 * 抽帧与预览抢槽时不再一直 shimmer。
 */
export function StoryboardModalTabThumbnail({
  generating = false,
  coverImageUrl = '',
  videoUrl = '',
  isActive = false
}: StoryboardModalTabThumbnailProps) {
  const cover = String(coverImageUrl || '').trim()
  const video = String(videoUrl || '').trim()

  if (generating) {
    return (
      <div className="thumbnail-loading-wrap">
        <LoadingOutlined spin className="thumbnail-loading-icon" />
      </div>
    )
  }

  if (cover) {
    return (
      <div className="thumbnail-video-wrap">
        <img src={cover} alt="" className="thumbnail-video" draggable={false} />
      </div>
    )
  }

  if (video) {
    return (
      <div className="thumbnail-video-wrap">
        <VideoPosterThumb
          src={video}
          priority={isActive ? VIDEO_POSTER_PRIORITY.activeTab : VIDEO_POSTER_PRIORITY.tab}
          imgClass="thumbnail-video"
          videoClass="thumbnail-video"
          objectFit="cover"
          allowVideoFallback
          fallbackLazy={!isActive}
          fallbackGated={!isActive}
        />
      </div>
    )
  }

  return (
    <div className="thumbnail-placeholder">
      <VideoCameraOutlined />
    </div>
  )
}

/** 从 headerTab.thumbnailUrl + 场景封面解析 Tab 展示用封面 / 视频 */
export function resolveStoryboardModalTabMedia(input: {
  tabThumbnailUrl?: string
  coverImageUrl?: string
  videoUrl?: string
}): { coverImageUrl: string; videoUrl: string } {
  const tabThumb = String(input.tabThumbnailUrl || '').trim()
  const coverFromScene = String(input.coverImageUrl || '').trim()
  const videoFromScene = String(input.videoUrl || '').trim()
  const tabIsImage = isProbablyImageUrl(tabThumb)
  const coverImageUrl = coverFromScene || (tabIsImage ? tabThumb : '')
  const videoUrl = videoFromScene || (!tabIsImage ? tabThumb : '')
  return { coverImageUrl, videoUrl }
}
