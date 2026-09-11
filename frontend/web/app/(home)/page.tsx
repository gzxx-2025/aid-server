'use client'

import { SearchOutlined } from '@ant-design/icons'
import { useRouter } from 'next/navigation'
import { useEffect, useMemo, useRef, useState } from 'react'
import groupAvatarRaw from '~/assets/img/home/Group-avtor.svg'
import emptyImageIconRaw from '~/assets/img/icon/empty_icon.svg'
import iconStartRaw from '~/assets/img/icon/icon_start.svg'
import starWhiteRaw from '~/assets/img/icon/star_white.svg'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useUserStore } from '~/stores/user'
import type { UserProjectType } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { requireLogin } from '~/utils/authLoginNavigation'
import { publicProjectVideoList } from '~/utils/businessApi'
import { normalizePublicCaseCard, type PublicCaseCardItem } from '~/utils/publicCasePlaza'

const starWhiteUrl = assetUrl(starWhiteRaw)
const groupAvatarUrl = assetUrl(groupAvatarRaw)
const emptyImageIconUrl = assetUrl(emptyImageIconRaw)
const iconStartUrl = assetUrl(iconStartRaw)

const filterTabs = [
  { label: '全部', value: 'all' },
  { label: '电影/短片', value: 'movie' },
  { label: '电视剧集', value: 'series' }
] as const

type GalleryFilter = (typeof filterTabs)[number]['value']

/** 公开版首页：平台介绍、创作入口与无需登录的案例广场。 */
export default function HomeNewIndexPage() {
  const router = useRouter()
  const token = useUserStore((state) => state.token)
  const createModal = useHomeShellCreateModal()
  const [searchQuery, setSearchQuery] = useState('')
  const [activeTab, setActiveTab] = useState<GalleryFilter>('all')
  const [works, setWorks] = useState<PublicCaseCardItem[]>([])
  const [loading, setLoading] = useState(true)
  const [errorMessage, setErrorMessage] = useState('')
  const requestGenerationRef = useRef(0)

  const projectType = useMemo<UserProjectType | undefined>(
    () => (activeTab === 'all' ? undefined : activeTab),
    [activeTab]
  )

  useEffect(() => {
    const generation = ++requestGenerationRef.current
    const timer = window.setTimeout(() => {
      setLoading(true)
      setErrorMessage('')
      void publicProjectVideoList({
        projectName: searchQuery.trim() || undefined,
        projectType,
        pageNum: 1,
        pageSize: 24
      })
        .then(({ rows }) => {
          if (generation !== requestGenerationRef.current) return
          setWorks(rows.map(normalizePublicCaseCard))
        })
        .catch(() => {
          if (generation !== requestGenerationRef.current) return
          setWorks([])
          setErrorMessage('案例列表加载失败，请稍后重试')
        })
        .finally(() => {
          if (generation === requestGenerationRef.current) setLoading(false)
        })
    }, searchQuery ? 250 : 0)

    return () => {
      window.clearTimeout(timer)
      if (requestGenerationRef.current === generation) requestGenerationRef.current += 1
    }
  }, [projectType, searchQuery])

  const startCreating = () => {
    if (!token) {
      requireLogin()
      return
    }
    createModal.openCreateModal()
  }

  const openCaseDetail = (projectId: number) => {
    router.push(`/case?id=${projectId}`)
  }

  return (
    <div className="home-new-index">
      <div className="page-content">
        <section className="home-new-hero" aria-labelledby="aid-open-home-title">
          <div className="home-new-hero-media">
            <div className="home-new-hero-stage">
              <div className="home-new-hero-ambient" aria-hidden="true" />
              <div className="home-new-hero-fade" aria-hidden="true" />
              <div className="home-new-open-intro">
                <p className="home-new-open-intro__eyebrow">AID · AI 内容创作平台</p>
                <h1 id="aid-open-home-title">从剧本到成片，完成一体化 AI 创作</h1>
                <p>支持 AI 漫剧、AI 电影与 AI 漫画的项目化创作、资产管理和生成流程。</p>
              </div>
            </div>
          </div>
        </section>

        <div className="home-new-actions">
          <button type="button" className="btn-primary" onClick={startCreating}>
            <img src={starWhiteUrl} alt="" />
            <span>我要创作</span>
          </button>
          <button type="button" className="btn-secondary" onClick={() => router.push('/faq')}>
            <span className="text-gradient">使用教程</span>
          </button>
        </div>

        <section className="gallery-section" aria-labelledby="gallery-section-title">
          <header className="section-header">
            <h2 id="gallery-section-title" className="section-title">案例广场</h2>
            <div className="section-toolbar">
              <div className="filter-tabs" role="tablist" aria-label="案例分类">
                {filterTabs.map((tab) => (
                  <button
                    key={tab.value}
                    type="button"
                    role="tab"
                    aria-selected={activeTab === tab.value}
                    className={`filter-tab${activeTab === tab.value ? ' active' : ''}`}
                    onClick={() => setActiveTab(tab.value)}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
              <label className="search-box">
                <SearchOutlined className="search-icon" />
                <input
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  type="search"
                  placeholder="搜索作品..."
                  className="search-input"
                  aria-label="搜索作品"
                />
              </label>
            </div>
          </header>

          {loading && works.length === 0 ? (
            <div className="gallery-feedback" role="status">案例加载中...</div>
          ) : errorMessage ? (
            <div className="gallery-feedback gallery-feedback--error" role="alert">{errorMessage}</div>
          ) : works.length === 0 ? (
            <div className="gallery-feedback">暂无符合条件的公开案例</div>
          ) : (
            <div className={`works-grid${loading ? ' is-refreshing' : ''}`} aria-busy={loading}>
              {works.map((work) => (
                <a
                  key={work.id}
                  className="work-card work-card--gallery"
                  data-gallery-work-id={work.id}
                  href={`/case?id=${work.id}`}
                  onClick={(event) => {
                    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
                    event.preventDefault()
                    openCaseDetail(work.id)
                  }}
                >
                  <div className="work-cover">
                    <img
                      src={work.coverUrl || emptyImageIconUrl}
                      alt={work.title}
                      className={`work-img${work.coverUrl ? '' : ' work-img--empty'}`}
                      loading="lazy"
                    />
                    <span className="work-play-badge" aria-hidden="true">
                      <img src={iconStartUrl} alt="" width={24} height={24} />
                    </span>
                  </div>
                  <div className="work-body">
                    <h3 className="work-title">{work.title}</h3>
                    <div className="work-footer">
                      <div className="work-author">
                        <img src={groupAvatarUrl} alt="" className="work-author-avatar" width={16} height={16} />
                        <span className="work-author-name">{work.authorName}</span>
                      </div>
                      <div className="work-stats">
                        {work.episodeCount > 0 ? <span className="work-views">{work.episodeCount}集</span> : null}
                        <span className="work-tag">{work.categoryLabel}</span>
                      </div>
                    </div>
                  </div>
                </a>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
