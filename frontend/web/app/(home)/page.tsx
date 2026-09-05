'use client'

import { useRouter } from 'next/navigation'
import starWhiteRaw from '~/assets/img/icon/star_white.svg'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useUserStore } from '~/stores/user'
import { assetUrl } from '~/utils/assetUrl'
import { requireLogin } from '~/utils/authLoginNavigation'

const starWhiteUrl = assetUrl(starWhiteRaw)

/** 首页保留平台介绍、作品管理与创作入口。 */
export default function HomeNewIndexPage() {
  const router = useRouter()
  const token = useUserStore((state) => state.token)
  const createModal = useHomeShellCreateModal()

  const startCreating = () => {
    if (!token) {
      requireLogin()
      return
    }
    createModal.openCreateModal()
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
      </div>
    </div>
  )
}
