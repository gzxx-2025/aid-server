'use client'

// 旧版首页，保留访问路径 /index-legacy
import { EyeOutlined,PlayCircleOutlined,SearchOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useRouter } from 'next/navigation'
import { useEffect,useRef,useState } from 'react'
import algcMod from '~/assets/img/home/algc.png'
import allNorIconMod from '~/assets/img/home/all-nor.svg'
import allSelIconMod from '~/assets/img/home/all-sel.svg'
import bannerMod from '~/assets/img/home/banner.svg'
import curatedNorIconMod from '~/assets/img/home/curated-nor.svg'
import curatedSelIconMod from '~/assets/img/home/curated-sel.svg'
import episodeMod from '~/assets/img/home/episode.svg'
import latestNorIconMod from '~/assets/img/home/latest-nor.svg'
import latestSelIconMod from '~/assets/img/home/latest-sel.svg'
import popularNorIconMod from '~/assets/img/home/popular-nor.svg'
import popularSelIconMod from '~/assets/img/home/popular-sel.svg'
import userBlueMod from '~/assets/img/home/user_blue.svg'
import starWhiteMod from '~/assets/img/icon/star_white.svg'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useHomeSidebarExtraNav } from '~/composables/useHomeSidebarExtraNav'
import { useUserStore } from '~/stores/user'
import type { PublicProjectVideoRow } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { publicProjectVideoList } from '~/utils/businessApi'
import './index-legacy.css'

const bannerUrl = assetUrl(bannerMod)
const starWhiteUrl = assetUrl(starWhiteMod)
const algcUrl = assetUrl(algcMod)
const episodeUrl = assetUrl(episodeMod)
const userBlueUrl = assetUrl(userBlueMod)

const filterTabs = [
  { label: '全部', value: 'all', iconNor: assetUrl(allNorIconMod), iconSel: assetUrl(allSelIconMod) },
  {
    label: '热门',
    value: 'hot',
    iconNor: assetUrl(popularNorIconMod),
    iconSel: assetUrl(popularSelIconMod)
  },
  {
    label: '精选',
    value: 'featured',
    iconNor: assetUrl(curatedNorIconMod),
    iconSel: assetUrl(curatedSelIconMod)
  },
  {
    label: '最新',
    value: 'latest',
    iconNor: assetUrl(latestNorIconMod),
    iconSel: assetUrl(latestSelIconMod)
  }
]

interface HomeGalleryWork {
  id: number
  title: string
  coverImage: string
  projectDesc: string
  authorName: string
  videoStyleType: string
}

function normalizeWork(row: PublicProjectVideoRow): HomeGalleryWork {
  return {
    id: row.id,
    title: row.projectName || `公开项目 #${row.id}`,
    coverImage: row.coverUrl || '',
    projectDesc: String(row.projectDesc || '').trim(),
    authorName: String(row.authorNickname || '').trim() || '作者',
    videoStyleType: String(row.projectType || '').trim()
  }
}

/** 原 pages/index-legacy.vue：layout=home，由 app/(home-legacy)/layout.tsx 承担壳 */
export default function IndexLegacyPage() {
  const router = useRouter()
  const token = useUserStore((s) => s.token)
  const { tutorialUrl } = useAuthPublicConfig()
  const { openTutorial } = useHomeSidebarExtraNav()

  const isLoggedIn = !!token
  const homeCreateModal = useHomeShellCreateModal()

  const [searchQuery, setSearchQuery] = useState('')
  const [activeTab, setActiveTab] = useState('all')

  const [works, setWorks] = useState<HomeGalleryWork[]>([])

  async function loadPublicWorks() {
    const { rows } = await publicProjectVideoList({
      projectName: searchQuery.trim() || undefined,
      pageNum: 1,
      pageSize: 24
    })
    setWorks(rows.map(normalizeWork))
  }

  const filteredWorks = (() => {
    let result = works
    if (activeTab === 'latest') {
      result = [...result].sort((a, b) => b.id - a.id)
    } else if (activeTab === 'hot') {
      result = [...result].sort((a, b) => b.title.length - a.title.length)
    } else if (activeTab === 'featured') {
      result = result.filter((work) => work.coverImage)
    }
    return result
  })()

  function goLogin() {
    router.push('/login')
  }

  function goCaseDetail(projectId: number) {
    router.push(`/case?id=${projectId}&from=legacy`)
  }

  const goToCreate = () => {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    homeCreateModal.openCreateModal()
  }

  // 原 watch(searchQuery)：仅在变化时重查（不含首次），首次加载见下方 onMounted 等价 effect
  const isFirstSearchWatchRef = useRef(true)
  useEffect(() => {
    if (isFirstSearchWatchRef.current) {
      isFirstSearchWatchRef.current = false
      return
    }
    void loadPublicWorks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery])

  useEffect(() => {
    void (async () => {
      try {
        await loadPublicWorks()
      } catch {
        message.error('加载案例广场失败，请稍后重试')
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="home-legacy-page">
      <div className="page-content">
        <section className="hero-section">
          <div className="hero-bg">
            <img src={bannerUrl} alt="Hero Background" className="hero-bg-img" />
          </div>
          <div className="hero-content">
            <div className="hero-buttons">
              <button type="button" className="btn-primary" onClick={goToCreate}>
                <img src={starWhiteUrl} alt="" />
                <span>我要创作</span>
              </button>
              {tutorialUrl ? (
                <button type="button" className="btn-secondary" onClick={() => void openTutorial()}>
                  <div className="text">查看教程</div>
                </button>
              ) : null}
            </div>
          </div>
        </section>

        <section className="gallery-section">
          <div className="section-header">
            <img className="algc" src={algcUrl} alt="" />
            <div className="section-controls">
              <div className="search-box">
                <SearchOutlined className="search-icon" />
                <input
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  type="text"
                  placeholder="搜索作品..."
                  className="search-input"
                />
              </div>
              <div className="filter-tabs">
                {filterTabs.map((tab) => (
                  <button
                    key={tab.value}
                    type="button"
                    className={`filter-tab${activeTab === tab.value ? ' active' : ''}`}
                    onClick={() => setActiveTab(tab.value)}
                  >
                    <img
                      src={activeTab === tab.value ? tab.iconSel : tab.iconNor}
                      alt=""
                      className="filter-tab-ico"
                      width={14}
                      height={14}
                    />
                    <span>{tab.label}</span>
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className="works-grid">
            {filteredWorks.map((work) => (
              <div
                key={work.id}
                className="work-card work-card--gallery"
                role="button"
                tabIndex={0}
                onClick={() => goCaseDetail(work.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    goCaseDetail(work.id)
                  }
                }}
              >
                <div className="work-cover">
                  <img src={work.coverImage} alt={work.title} className="work-img" />
                  <div className="work-overlay">
                    <button
                      type="button"
                      className="play-btn"
                      aria-label="播放预览"
                      onClick={(e) => {
                        e.stopPropagation()
                        goCaseDetail(work.id)
                      }}
                    >
                      <PlayCircleOutlined />
                    </button>
                  </div>
                </div>
                <div className="work-info">
                  <h3 className="work-title">{work.title}</h3>
                  <div className="work-meta">
                    <span className="meta-item">
                      <img src={episodeUrl} alt="" />
                      ID {work.id}
                    </span>
                    <span className="meta-item">
                      <EyeOutlined />
                      {work.videoStyleType || '公开案例'}
                    </span>
                  </div>
                  <div className="work-author">
                    <div className="author-avatar">
                      <img src={userBlueUrl} alt="" />
                    </div>
                    <span className="author-name">{work.authorName || '作者'}</span>
                  </div>
                  {work.projectDesc ? <p className="work-desc">{work.projectDesc}</p> : null}
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}
