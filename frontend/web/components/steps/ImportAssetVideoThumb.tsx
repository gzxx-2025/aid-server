'use client'

import { CaretRightFilled, PauseOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { useRef, useState } from 'react'
import { ShimmerVideo, type ShimmerVideoHandle } from '~/components/common/ShimmerVideo'

export interface ImportAssetVideoThumbProps {
  src: string
  title: string
}

/** 资产库视频缩略卡：播放控制不冒泡，卡片其余区域仍维持原有选中行为。 */
export function ImportAssetVideoThumb({ src, title }: ImportAssetVideoThumbProps) {
  const videoRef = useRef<ShimmerVideoHandle | null>(null)
  const [mediaReady, setMediaReady] = useState(false)
  const [playing, setPlaying] = useState(false)

  async function togglePlayback(event: React.MouseEvent<HTMLButtonElement>) {
    event.preventDefault()
    event.stopPropagation()
    const video = videoRef.current?.videoRef
    if (!video) return
    if (!video.paused) {
      video.pause()
      return
    }
    try {
      await video.play()
      setPlaying(true)
    } catch {
      setPlaying(false)
    }
  }

  return (
    <div className="group relative h-full w-full overflow-hidden bg-[#07101f]">
      <ShimmerVideo
        ref={videoRef}
        src={src}
        wrapperClass="h-full w-full"
        videoClass="h-full w-full"
        objectFit="cover"
        lazy
        preload="metadata"
        onLoad={() => setMediaReady(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        errorSlot={
          <div className="absolute inset-0 flex items-center justify-center text-4xl text-slate-500">
            <VideoCameraOutlined />
          </div>
        }
      />
      {mediaReady ? (
        <button
          type="button"
          className="absolute left-1/2 top-1/2 z-[4] flex h-12 w-12 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border border-white/70 bg-black/55 text-xl text-white shadow-lg transition hover:scale-105 hover:bg-black/70 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-300"
          title={playing ? `暂停${title}` : `播放${title}`}
          aria-label={playing ? `暂停${title}` : `播放${title}`}
          onClick={togglePlayback}
        >
          {playing ? <PauseOutlined /> : <CaretRightFilled className="ml-0.5" />}
        </button>
      ) : null}
    </div>
  )
}
