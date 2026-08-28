'use client'

import { useEffect, useMemo, useRef, useState, type SyntheticEvent } from 'react'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { Suspense } from 'react'
import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { assetUrl } from '~/utils/assetUrl'
import { emptyImageIconUrl } from '~/utils/emptyImageIcon'
import { publicProjectDetail } from '~/utils/businessApi'
import type { PublicProjectDetailRow, PublicProjectEpisodeItem } from '~/types/business-api'
import iconReturnMod from '~/assets/img/icon/icon-return.svg'
import userBlueMod from '~/assets/img/home/Group-avtor.svg'
import '~/assets/css/case-detail-page.css'
import './case-detail-episodes.css'

const iconReturnUrl = assetUrl(iconReturnMod)
const userBlueIcon = assetUrl(userBlueMod)

interface ProtagonistItem {
  name?: string
  imageUrl?: string
}

function resolveCaseProjectId(pathname: string, searchId: string | null) {
  const fromQuery = Number(searchId)
  if (Number.isFinite(fromQuery) && fromQuery > 0) return fromQuery
  const fromPath = Number(pathname.match(/^\/case\/(\d+)/)?.[1])
  return Number.isFinite(fromPath) && fromPath > 0 ? fromPath : 0
}

function CaseDetailContent() {
  const pathname = usePathname()
  const router = useRouter()
  const searchParams = useSearchParams()

  const [detailRequest, setDetailRequest] = useState<{
    projectId: number
    detail: PublicProjectDetailRow | null
    errorMessage: string
  }>({ projectId: 0, detail: null, errorMessage: '' })
  const [activeEpisodeId, setActiveEpisodeId] = useState<number | null>(null)
  const [videoLayout, setVideoLayout] = useState<'intrinsic' | 'fill'>('intrinsic')
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const detailLoadGenRef = useRef(0)

  const projectId = useMemo(
    () => resolveCaseProjectId(pathname, searchParams.get('id')),
    [pathname, searchParams]
  )

  const requestMatchesRoute = detailRequest.projectId === projectId
  const loading = projectId > 0 && !requestMatchesRoute
  const errorMessage = !projectId
    ? '无效的案例 ID'
    : requestMatchesRoute
      ? detailRequest.errorMessage
      : ''
  const detail = requestMatchesRoute ? detailRequest.detail : null

  const projectName = detail?.projectName || '影片名称'
  const episodes = useMemo<PublicProjectEpisodeItem[]>(
    () => (Array.isArray(detail?.episodes) ? detail.episodes : []),
    [detail]
  )

  const activeEpisode = useMemo(() => {
    if (!episodes.length) return null
    const hit = episodes.find((ep) => ep.episodeId === activeEpisodeId)
    return hit || episodes[0] || null
  }, [episodes, activeEpisodeId])

  const activeCoverUrl = (() => {
    const epCover = String(activeEpisode?.coverUrl || '').trim()
    if (epCover) return epCover
    return detail?.coverUrl || ''
  })()

  const activeVideoUrl = (() => {
    const epVideo = String(activeEpisode?.videoUrl || '').trim()
    if (epVideo) return epVideo
    return detail?.finalVideoUrl || ''
  })()

  const videoTypeLabel = detail?.videoStyleType || '影片的类型'
  const authorName = String(detail?.authorNickname || '').trim() || '用户'
  const descriptionText = detail?.projectDesc?.trim() || '剧情介绍...'

  const protagonistItems = useMemo<ProtagonistItem[]>(() => {
    const row = detail as
      | (PublicProjectDetailRow & {
          characterList?: ProtagonistItem[]
          protagonists?: ProtagonistItem[]
          roleList?: ProtagonistItem[]
        })
      | null
    const list = row?.characterList || row?.protagonists || row?.roleList
    if (Array.isArray(list) && list.length) {
      return list.slice(0, 8)
    }
    return []
  }, [detail])

  const showProtagonists = protagonistItems.length > 0

  function resolveBackTarget() {
    const from = String(searchParams.get('from') || '')
    if (from === 'legacy') return '/index-legacy'
    return '/'
  }

  function goBack() {
    if (typeof window !== 'undefined' && window.history.length > 1) {
      router.back()
      return
    }
    router.push(resolveBackTarget())
  }

  function selectEpisode(episodeId: number) {
    if (activeEpisodeId === episodeId) return
    setVideoLayout('intrinsic')
    setActiveEpisodeId(episodeId)
    requestAnimationFrame(() => {
      videoRef.current?.play().catch(() => {})
    })
  }

  function handleVideoLoadedMetadata(event: SyntheticEvent<HTMLVideoElement>) {
    const video = event.currentTarget
    if (!video.videoWidth || !video.videoHeight) {
      setVideoLayout('intrinsic')
      return
    }
    const ratio = video.videoWidth / video.videoHeight
    setVideoLayout(Math.abs(ratio - 16 / 9) <= 0.03 ? 'fill' : 'intrinsic')
  }

  // 路由 id 变化：重新加载详情并自动播放（对齐原 watch immediate）
  useEffect(() => {
    const gen = ++detailLoadGenRef.current
    if (!projectId) return

    void publicProjectDetail(projectId)
      .then((row) => {
        if (gen !== detailLoadGenRef.current) return
        setDetailRequest({ projectId, detail: row, errorMessage: '' })
        setVideoLayout('intrinsic')
        const firstEp = Array.isArray(row.episodes) ? row.episodes[0] : null
        setActiveEpisodeId(firstEp?.episodeId ?? null)
        requestAnimationFrame(() => {
          videoRef.current?.play().catch(() => {})
        })
      })
      .catch(() => {
        if (gen !== detailLoadGenRef.current) return
        setDetailRequest({
          projectId,
          detail: null,
          errorMessage: '加载案例详情失败，请稍后重试'
        })
        setActiveEpisodeId(null)
      })
  }, [projectId])

  // 文档标题（对齐原 useHead title）
  useEffect(() => {
    document.title = detail?.projectName ? `${detail.projectName} - 案例详情` : '案例详情'
  }, [detail?.projectName])

  return (
    <div className="case-detail-root">
      <HtmlShellClass classes="case-detail-html" />
      <div className="case-detail-bg" aria-hidden="true">
        {activeCoverUrl ? <img src={activeCoverUrl} alt="" className="case-detail-bg-img" /> : null}
        <div className="case-detail-bg-glow" />
      </div>

      {loading ? (
        <div className="case-detail-loading" role="status">
          加载中...
        </div>
      ) : errorMessage ? (
        <div className="case-detail-error">
          <p>{errorMessage}</p>
          <button type="button" className="case-detail-back-btn" aria-label="返回" onClick={goBack}>
            <img src={iconReturnUrl} alt="" width={36} height={36} />
          </button>
        </div>
      ) : (
        <div className="case-detail-page">
          <section className="case-detail-main" aria-label="案例视频">
            <header className="case-detail-nav">
              <button
                type="button"
                className="case-detail-back-btn"
                aria-label="返回"
                onClick={goBack}
              >
                <img src={iconReturnUrl} alt="" width={36} height={36} />
              </button>
            </header>

            <div className="case-detail-player-area">
              <div className="case-detail-video-wrap">
                {activeVideoUrl ? (
                  <video
                    ref={videoRef}
                    key={activeVideoUrl}
                    className={`case-detail-video${videoLayout === 'fill' ? ' case-detail-video--fill' : ''}`}
                    src={activeVideoUrl}
                    poster={activeCoverUrl || undefined}
                    controls
                    playsInline
                    preload="metadata"
                    onLoadedMetadata={handleVideoLoadedMetadata}
                  />
                ) : activeCoverUrl ? (
                  <img src={activeCoverUrl} alt={projectName} className="case-detail-cover" />
                ) : null}
              </div>

              {episodes.length > 1 ? (
                <div className="case-detail-episodes" aria-label="剧集列表">
                  {episodes.map((ep) => (
                    <button
                      key={ep.episodeId}
                      type="button"
                      className={`case-detail-episode-btn${activeEpisodeId === ep.episodeId ? ' is-active' : ''}`}
                      onClick={() => selectEpisode(ep.episodeId)}
                    >
                      {ep.coverUrl ? (
                        <img
                          src={ep.coverUrl}
                          alt={ep.title || `第${ep.episodeNo}集`}
                          className="case-detail-episode-cover"
                        />
                      ) : null}
                      <span className="case-detail-episode-title">
                        {ep.title || `第${ep.episodeNo}集`}
                      </span>
                    </button>
                  ))}
                </div>
              ) : null}
            </div>
          </section>

          <aside className="case-detail-sidebar" aria-label="案例信息">
            <div className="case-detail-user">
              <img src={userBlueIcon} alt="" className="case-detail-user-avatar" width={36} height={36} />
              <span className="case-detail-user-name">{authorName}</span>
            </div>

            <h1 className="case-detail-title">{projectName}</h1>
            <p className="case-detail-type">{videoTypeLabel}</p>
            <p className="case-detail-desc">{descriptionText}</p>

            {showProtagonists ? (
              <section aria-labelledby="case-protagonists-title">
                <h2 id="case-protagonists-title" className="case-detail-section-title">
                  故事主角
                </h2>
                <div className="case-detail-protagonists">
                  {protagonistItems.map((item, index) => (
                    <div
                      key={`${item.imageUrl}-${index}`}
                      className={`case-detail-protagonist${item.imageUrl ? '' : ' case-detail-protagonist--empty'}`}
                    >
                      {item.imageUrl ? (
                        <img src={item.imageUrl} alt={item.name || '故事主角'} loading="lazy" />
                      ) : (
                        <img
                          src={emptyImageIconUrl}
                          alt=""
                          className="empty-image-icon empty-image-icon--md case-detail-protagonist__empty-icon"
                        />
                      )}
                    </div>
                  ))}
                </div>
              </section>
            ) : null}
          </aside>
        </div>
      )}
    </div>
  )
}

/** 案例详情全屏播放页（无壳）。静态 /case?id=，避免 dev 下 [id] 动态路由拉起会崩的 static-paths-worker。 */
export default function CaseDetailPage() {
  return (
    <Suspense fallback={null}>
      <CaseDetailContent />
    </Suspense>
  )
}
