'use client'

import { useRouter, useSearchParams } from 'next/navigation'
import { Suspense, useEffect, useState } from 'react'
import groupAvatarRaw from '~/assets/img/home/Group-avtor.svg'
import iconReturnRaw from '~/assets/img/icon/icon-return.svg'
import { HtmlShellClass } from '~/components/app/HtmlShellClass'
import type { PublicProjectDetailRow } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { publicProjectDetail } from '~/utils/businessApi'
import {
  resolveActivePublicEpisode,
  resolvePublicProjectPlayback,
  resolvePublicProjectType
} from '~/utils/publicCasePlaza'
import './case-page.css'

const iconReturnUrl = assetUrl(iconReturnRaw)
const groupAvatarUrl = assetUrl(groupAvatarRaw)

function parseProjectId(value: string | null): number {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

function CaseDetailContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const projectId = parseProjectId(searchParams.get('id'))
  const [requestState, setRequestState] = useState<{
    projectId: number
    detail: PublicProjectDetailRow | null
    errorMessage: string
  }>({ projectId: 0, detail: null, errorMessage: '' })
  const [activeEpisodeId, setActiveEpisodeId] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    if (!projectId) {
      return () => {
        cancelled = true
      }
    }

    void publicProjectDetail(projectId)
      .then((row) => {
        if (cancelled) return
        setRequestState({ projectId, detail: row, errorMessage: '' })
        const firstEpisode = Array.isArray(row.episodes) ? row.episodes[0] : null
        setActiveEpisodeId(firstEpisode?.episodeId ?? null)
      })
      .catch(() => {
        if (cancelled) return
        setRequestState({
          projectId,
          detail: null,
          errorMessage: '案例详情加载失败，请稍后重试'
        })
      })

    return () => {
      cancelled = true
    }
  }, [projectId])

  const loading = projectId > 0 && requestState.projectId !== projectId
  const detail = requestState.projectId === projectId ? requestState.detail : null
  const errorMessage = !projectId
    ? '案例地址无效'
    : requestState.projectId === projectId
      ? requestState.errorMessage
      : ''
  const episodes = Array.isArray(detail?.episodes) ? detail.episodes : []
  const activeEpisode = detail ? resolveActivePublicEpisode(detail, activeEpisodeId) : null
  const playback = detail
    ? resolvePublicProjectPlayback(detail, activeEpisodeId)
    : { videoUrl: '', coverUrl: '' }
  const projectType = detail ? resolvePublicProjectType(detail.projectType) : 'movie'

  const goBack = () => {
    if (window.history.length > 1) router.back()
    else router.push('/')
  }

  return (
    <div className="public-case-page">
      <HtmlShellClass classes="public-case-shell" />
      <div className="public-case-backdrop" aria-hidden="true">
        {playback.coverUrl ? <img src={playback.coverUrl} alt="" /> : null}
      </div>

      <button type="button" className="public-case-back" aria-label="返回案例广场" onClick={goBack}>
        <img src={iconReturnUrl} alt="" width={36} height={36} />
      </button>

      {loading ? (
        <div className="public-case-feedback" role="status">案例加载中...</div>
      ) : errorMessage || !detail ? (
        <div className="public-case-feedback" role="alert">
          <p>{errorMessage || '案例不存在或尚未公开'}</p>
          <button type="button" onClick={() => router.push('/')}>返回案例广场</button>
        </div>
      ) : (
        <main className="public-case-content">
          <section className="public-case-media" aria-label="案例视频">
            <div className="public-case-player">
              {playback.videoUrl ? (
                <video
                  key={playback.videoUrl}
                  src={playback.videoUrl}
                  poster={playback.coverUrl || undefined}
                  controls
                  autoPlay
                  playsInline
                  preload="metadata"
                />
              ) : playback.coverUrl ? (
                <img src={playback.coverUrl} alt={detail.projectName} />
              ) : (
                <div className="public-case-no-media">该案例暂无可播放视频</div>
              )}
            </div>

            {episodes.length > 1 ? (
              <div className="public-case-episodes" aria-label="剧集列表">
                {episodes.map((episode, index) => (
                  <button
                    key={episode.episodeId}
                    type="button"
                    className={activeEpisode?.episodeId === episode.episodeId ? 'is-active' : ''}
                    onClick={() => setActiveEpisodeId(episode.episodeId)}
                  >
                    {episode.coverUrl ? <img src={episode.coverUrl} alt="" loading="lazy" /> : null}
                    <span>{episode.title || `第${episode.episodeNo || index + 1}集`}</span>
                  </button>
                ))}
              </div>
            ) : null}
          </section>

          <aside className="public-case-info">
            <div className="public-case-author">
              <img src={groupAvatarUrl} alt="" width={36} height={36} />
              <span>{detail.authorNickname?.trim() || '作者'}</span>
            </div>
            <h1>{detail.projectName || `公开项目 #${detail.id}`}</h1>
            <p className="public-case-type">
              {projectType === 'series' ? `电视剧集${detail.episodeCount ? ` · ${detail.episodeCount}集` : ''}` : '电影/短片'}
            </p>
            <p className="public-case-description">{detail.projectDesc?.trim() || '暂无作品介绍'}</p>
            {detail.publishTime ? <p className="public-case-published">发布于 {detail.publishTime}</p> : null}
          </aside>
        </main>
      )}
    </div>
  )
}

/** 静态 `/case?id=` 详情页，兼容开源版静态导出。 */
export default function CaseDetailPage() {
  return (
    <Suspense fallback={<div className="public-case-feedback">案例加载中...</div>}>
      <CaseDetailContent />
    </Suspense>
  )
}
