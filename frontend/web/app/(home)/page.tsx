'use client'

import { SearchOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useRouter } from 'next/navigation'
import type { KeyboardEvent as ReactKeyboardEvent,MouseEvent as ReactMouseEvent } from 'react'
import { useCallback,useEffect,useRef,useState } from 'react'
import groupAvtorRaw from '~/assets/img/home/Group-avtor.svg'
import emptyImageIconRaw from '~/assets/img/icon/empty_icon.svg'
import iconStartRaw from '~/assets/img/icon/icon_start.svg'
import starWhiteRaw from '~/assets/img/icon/star_white.svg'
import type { HomeHeroCarouselHandle } from '~/components/home/HomeHeroCarousel'
import HomeHeroCarousel from '~/components/home/HomeHeroCarousel'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useHomeSidebarExtraNav } from '~/composables/useHomeSidebarExtraNav'
import { useMotion } from '~/composables/useMotion'
import { useUserStore } from '~/stores/user'
import type { PublicProjectVideoRow } from '~/types/business-api'
import type { HeroCarouselSlide,HeroPhase } from '~/types/heroCarousel'
import { assetUrl } from '~/utils/assetUrl'
import { publicProjectVideoList,userHomeBannerList } from '~/utils/businessApi'
import {
collectNeighborCoverUrls,
resolveSlideCoverOrEmpty
} from '~/utils/heroCarouselPreload'
import { mapHomeBannerToSlide,resolveCenterVideoUrl } from '~/utils/homeBanner'
import { staggerReveal } from '~/utils/motionPresets'
import { resolveSafeHttpUrl,resolveSafeInternalPath } from '~/utils/safeNavigation'
import {
  consumeHomeGalleryScrollSnapshot,
  peekHomeGalleryScrollSnapshot,
  readHomeMainScrollTop,
  restoreHomeGalleryScroll,
  saveHomeGalleryScrollSnapshot
} from '~/utils/homeGalleryScrollRestore'

/* 原 definePageMeta({ layout: 'home-new' })：由 (home) 路由组壳层 layout 承担（主线任务统一创建） */

const iconStartUrl = assetUrl(iconStartRaw)
const groupAvtorUrl = assetUrl(groupAvtorRaw)
const emptyImageIconUrl = assetUrl(emptyImageIconRaw)
const starWhiteUrl = assetUrl(starWhiteRaw)

const heroCenterIndex = 0

const filterTabs = [
  { label: '全部', value: 'all' },
  { label: '电影/短片', value: 'movie' },
  { label: '电视剧集', value: 'series' }
] as const

type GalleryCategory = 'movie' | 'series'

interface HomeGalleryWork {
  id: number
  title: string
  coverImage: string
  authorName: string
  projectDesc: string
  viewCount: number
  category: GalleryCategory
  categoryLabel: string
}

const CATEGORY_LABEL: Record<GalleryCategory, string> = {
  movie: '电影/短剧',
  series: '电视剧集'
}

function resolveCategory(row: PublicProjectVideoRow): GalleryCategory {
  const type = String(row.projectType ?? '').toLowerCase()
  if (type === 'movie' || type === 'film') return 'movie'
  if (type === 'series' || type === 'tv') return 'series'
  return 'movie'
}

function normalizeWork(row: PublicProjectVideoRow): HomeGalleryWork {
  const category = resolveCategory(row)
  return {
    id: row.id,
    title: row.projectName || `公开项目 #${row.id}`,
    coverImage: row.coverUrl || emptyImageIconUrl,
    authorName: String(row.authorNickname || '').trim() || '作者',
    projectDesc: String(row.projectDesc || '').trim(),
    viewCount: Number(row.episodeCount) > 0 ? Number(row.episodeCount) : 0,
    category,
    categoryLabel: CATEGORY_LABEL[category]
  }
}

function preloadImages(urls: string[]) {
  if (!(typeof window !== 'undefined')) return Promise.resolve()
  const unique = [...new Set(urls.filter(Boolean))]
  return Promise.all(
    unique.map(
      (url) =>
        new Promise<void>((resolve) => {
          const img = new Image()
          img.onload = () => resolve()
          img.onerror = () => resolve()
          img.src = url
        })
    )
  )
}

/** 原 await nextTick()：React 下用 RAF 让出一帧 */
function nextFrame() {
  return new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
}

export default function HomeNewIndexPage() {
  const router = useRouter()
  const token = useUserStore((s) => s.token)
  const homeCreateModal = useHomeShellCreateModal()
  const { tutorialUrl } = useAuthPublicConfig()
  const { openTutorial } = useHomeSidebarExtraNav()

  const isLoggedIn = !!token

  const [searchQuery, setSearchQuery] = useState('')
  const [activeTab, setActiveTab] = useState<string>('all')
  const [carouselIndex, setCarouselIndex] = useState(heroCenterIndex)
  const [heroPhase, setHeroPhase] = useState<HeroPhase>('idle')

  const [isHeroContentReady, setIsHeroContentReadyState] = useState(false)
  const isHeroContentReadyRef = useRef(false)
  const setIsHeroContentReady = useCallback((v: boolean) => {
    isHeroContentReadyRef.current = v
    setIsHeroContentReadyState(v)
  }, [])

  const [bannersLoading, setBannersLoadingState] = useState(true)
  const bannersLoadingRef = useRef(true)
  const setBannersLoading = useCallback((v: boolean) => {
    bannersLoadingRef.current = v
    setBannersLoadingState(v)
  }, [])

  /* 原模板 ref：改为回调 ref 入 state，元素挂载后触发子组件 props 更新 */
  const [heroStageEl, setHeroStageEl] = useState<HTMLElement | null>(null)
  const [heroVideoEl, setHeroVideoElState] = useState<HTMLVideoElement | null>(null)
  const heroVideoElRef = useRef<HTMLVideoElement | null>(null)
  const handleHeroVideoRef = useCallback((el: HTMLVideoElement | null) => {
    heroVideoElRef.current = el
    setHeroVideoElState(el)
  }, [])
  const heroCarouselRef = useRef<HomeHeroCarouselHandle | null>(null)

  const [carouselSlides, setCarouselSlidesState] = useState<HeroCarouselSlide[]>([])
  const carouselSlidesRef = useRef<HeroCarouselSlide[]>([])
  const setCarouselSlides = useCallback((slides: HeroCarouselSlide[]) => {
    carouselSlidesRef.current = slides
    setCarouselSlidesState(slides)
  }, [])

  const showHeroSkeleton = bannersLoading && carouselSlides.length === 0
  const showHeroCarousel = showHeroSkeleton || carouselSlides.length > 0

  const heroDefaultVideoUrl = resolveCenterVideoUrl(carouselSlides, heroCenterIndex)

  const heroPosterSrc = resolveSlideCoverOrEmpty(carouselSlides, carouselIndex)

  /** 中心封面预加载完成后再暴露海报，避免 ambient 早退、poster 空窗 */
  const showHeroPoster = isHeroContentReady && !!heroPosterSrc

  const heroVideoPreload = isHeroContentReady && heroDefaultVideoUrl ? 'auto' : 'metadata'

  const [works, setWorksState] = useState<HomeGalleryWork[]>([])
  const worksRef = useRef<HomeGalleryWork[]>([])
  const setWorks = useCallback((list: HomeGalleryWork[]) => {
    worksRef.current = list
    setWorksState(list)
  }, [])

  async function loadHomeBanners() {
    setBannersLoading(true)
    try {
      let pageNum = 1
      const pageSize = 50
      const allRows: Awaited<ReturnType<typeof userHomeBannerList>>['rows'] = []
      let hasMore = true
      while (hasMore && pageNum <= 100) {
        const page = await userHomeBannerList({ pageNum, pageSize })
        if (!page.rows.length) break
        allRows.push(...page.rows)
        hasMore = page.hasMore
        pageNum += 1
      }
      // 只使用接口返回的 Banner，不再循环补齐重复封面
      setCarouselSlides(allRows.map(mapHomeBannerToSlide).filter((s) => s.cover))
    } catch {
      setCarouselSlides([])
      message.error('加载轮播内容失败，请稍后重试')
    } finally {
      setBannersLoading(false)
    }
  }

  function onBannerLink(slide: HeroCarouselSlide) {
    const linkType = String(slide.linkType || 'none').toLowerCase()
    const linkUrl = String(slide.linkUrl || '').trim()
    if (!linkUrl || linkType === 'none') return
    if (linkType === 'external') {
      const safeUrl = resolveSafeHttpUrl(linkUrl, window.location.href)
      if (safeUrl) window.open(safeUrl, '_blank', 'noopener,noreferrer')
      return
    }
    if (linkType === 'internal') {
      const path = resolveSafeInternalPath(linkUrl, window.location.href)
      if (path) router.push(path)
    }
  }

  async function prepareHeroContent() {
    setIsHeroContentReady(false)
    if (!carouselSlidesRef.current.length) return
    // 首屏只预加载中心 + 左右邻卡，其余交给卡片自身 Shimmer 加载
    await preloadImages(
      collectNeighborCoverUrls(carouselSlidesRef.current, heroCenterIndex, 1)
    )
    await nextFrame()
    setIsHeroContentReady(true)
  }

  async function loadPublicWorks() {
    const projectType =
      activeTab === 'movie' || activeTab === 'series'
        ? (activeTab as GalleryCategory)
        : undefined
    const { rows } = await publicProjectVideoList({
      projectName: searchQuery.trim() || undefined,
      projectType,
      pageNum: 1,
      pageSize: 24
    })
    setWorks(rows.map((row) => normalizeWork(row)))
  }

  const filteredWorks = works

  const galleryGridRef = useRef<HTMLDivElement | null>(null)
  const { run: runGalleryMotion } = useMotion(galleryGridRef)
  const galleryMotionGenRef = useRef(0)
  /** 从详情返回时跳过入场动画，避免把滚动位置冲掉 */
  const pendingGalleryScrollRestoreRef = useRef(false)

  useEffect(() => {
    if (peekHomeGalleryScrollSnapshot()) {
      pendingGalleryScrollRestoreRef.current = true
    }
  }, [])

  async function playGalleryCardReveal() {
    if (!(typeof window !== 'undefined')) return
    if (pendingGalleryScrollRestoreRef.current) return
    const gen = ++galleryMotionGenRef.current
    await nextFrame()
    if (gen !== galleryMotionGenRef.current) return
    if (!galleryGridRef.current || worksRef.current.length === 0) return
    await runGalleryMotion((gsap) => {
      if (gen !== galleryMotionGenRef.current) return
      staggerReveal(gsap, '.work-card--gallery')
    })
  }

  /* 原 watch(filteredWorks, { flush: 'post' }) */
  useEffect(() => {
    if (!works.length) return
    if (pendingGalleryScrollRestoreRef.current) {
      const snapshot = consumeHomeGalleryScrollSnapshot()
      pendingGalleryScrollRestoreRef.current = false
      if (snapshot) {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            restoreHomeGalleryScroll(snapshot)
          })
        })
        return
      }
    }
    void playGalleryCardReveal()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [works])

  /** 切卡时增量预加载新的中心±邻卡，减轻背景闪屏 */
  useEffect(() => {
    if (!(typeof window !== 'undefined') || !carouselSlidesRef.current.length) return
    void preloadImages(collectNeighborCoverUrls(carouselSlidesRef.current, carouselIndex, 1))
  }, [carouselIndex])

  function bindHeroVideo() {
    if (heroVideoElRef.current && heroCarouselRef.current) {
      heroCarouselRef.current.setHeroVideoElement(heroVideoElRef.current)
    }
  }

  /* 原 watch([heroVideoRef, heroCarouselRef, isHeroContentReady], () => nextTick(bindHeroVideo), { flush: 'post' }) */
  useEffect(() => {
    const timer = window.setTimeout(bindHeroVideo, 0)
    return () => window.clearTimeout(timer)
     
  }, [heroVideoEl, isHeroContentReady])

  function goLogin() {
    router.push('/login')
  }

  function goCaseDetail(projectId: number) {
    saveHomeGalleryScrollSnapshot({
      scrollTop: readHomeMainScrollTop(),
      workId: projectId,
      savedAt: Date.now()
    })
    router.push(`/case?id=${projectId}`)
  }

  const goToCreate = () => {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    homeCreateModal.openCreateModal()
  }

  /** 案例广场首屏拉取后，筛选/搜索变化才重请求，避免与挂载叠成双请求 */
  const publicWorksBootstrappedRef = useRef(false)

  useEffect(() => {
    if (!publicWorksBootstrappedRef.current) return
    void (async () => {
      try {
        await loadPublicWorks()
      } catch {
        message.error('加载案例列表失败，请稍后重试')
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery, activeTab])

  /* Banner / 案例广场首屏：必须在 mount 后再 setState，不能在 render 里起异步任务 */
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        await Promise.all([
          (async () => {
            await loadHomeBanners()
            if (cancelled) return
            await prepareHeroContent()
          })(),
          loadPublicWorks()
        ])
        if (cancelled) return
        publicWorksBootstrappedRef.current = true
        bindHeroVideo()
      } catch {
        if (cancelled) return
        publicWorksBootstrappedRef.current = true
        message.error('加载案例广场失败，请稍后重试')
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function onWorkCardKeyDown(event: ReactKeyboardEvent<HTMLElement>, workId: number) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    goCaseDetail(workId)
  }

  function onWorkPlayBadgeClick(event: ReactMouseEvent<HTMLButtonElement>, workId: number) {
    event.stopPropagation()
    goCaseDetail(workId)
  }

  return (
    <div className="home-new-index">
      <div className="page-content">
        <section className="home-new-hero" aria-label="精选视频">
          <div className="home-new-hero-media">
            <div ref={setHeroStageEl} className="home-new-hero-stage">
              <div
                className={`home-new-hero-ambient${showHeroPoster ? ' is-faded' : ''}`}
                aria-hidden="true"
              />
              <video
                ref={handleHeroVideoRef}
                className={`home-new-hero-video${heroPhase === 'playing' ? ' is-visible' : ''}`}
                poster={showHeroPoster ? heroPosterSrc : undefined}
                muted
                playsInline
                preload={heroVideoPreload}
              />
              {showHeroPoster ? (
                <img
                  key={heroPosterSrc}
                  src={heroPosterSrc}
                  alt=""
                  className={`home-new-hero-poster is-visible${heroPhase === 'playing' ? ' is-dimmed' : ''}`}
                />
              ) : null}
              <div className="home-new-hero-fade" aria-hidden="true" />
              {showHeroCarousel ? (
                <HomeHeroCarousel
                  ref={heroCarouselRef}
                  slides={carouselSlides}
                  skeleton={showHeroSkeleton}
                  initialCenterIndex={heroCenterIndex}
                  cycleEnabled={isHeroContentReady}
                  defaultVideoUrl={heroDefaultVideoUrl}
                  heroStageRef={heroStageEl}
                  heroVideoEl={heroVideoEl}
                  onActiveIndexChange={setCarouselIndex}
                  onPhaseChange={setHeroPhase}
                  onOpenLink={onBannerLink}
                />
              ) : null}
            </div>
          </div>
        </section>

        <div className="home-new-actions">
          <button
            type="button"
            className="btn-primary"
            onClick={goToCreate}
          >
            <img src={starWhiteUrl} alt="" />
            <span>我要创作</span>
          </button>
          {tutorialUrl ? (
            <button type="button" className="btn-secondary" onClick={() => void openTutorial()}>
              <div className="text-gradient">查看教程</div>
            </button>
          ) : null}
        </div>

        <section className="gallery-section" aria-labelledby="gallery-section-title">
          <header className="section-header">
            <h2 id="gallery-section-title" className="section-title">
              案例广场
            </h2>
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
              <div className="search-box">
                <SearchOutlined className="search-icon" />
                <input
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  type="search"
                  placeholder="搜索作品..."
                  className="search-input"
                  aria-label="搜索作品"
                />
              </div>
            </div>
          </header>

          <div ref={galleryGridRef} className="works-grid">
            {filteredWorks.map((work) => (
              <article
                key={work.id}
                className="work-card work-card--gallery"
                data-gallery-work-id={work.id}
                role="button"
                tabIndex={0}
                onClick={() => goCaseDetail(work.id)}
                onKeyDown={(event) => onWorkCardKeyDown(event, work.id)}
              >
                <div className="work-cover">
                  <img src={work.coverImage} alt={work.title} className="work-img" loading="lazy" />
                  <button
                    type="button"
                    className="work-play-badge"
                    aria-label="播放预览"
                    onClick={(event) => onWorkPlayBadgeClick(event, work.id)}
                  >
                    <img src={iconStartUrl} alt="" width={24} height={24} />
                  </button>
                </div>
                <div className="work-body">
                  <h3 className="work-title">{work.title}</h3>
                  <div className="work-footer">
                    <div className="work-author">
                      <img
                        src={groupAvtorUrl}
                        alt=""
                        className="work-author-avatar"
                        width={16}
                        height={16}
                      />
                      <span className="work-author-name">{work.authorName}</span>
                    </div>
                    <div className="work-stats">
                      {work.viewCount > 0 ? (
                        <span className="work-views">{work.viewCount}集</span>
                      ) : null}
                      <span className="work-tag">{work.categoryLabel}</span>
                    </div>
                  </div>
                </div>
              </article>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}
