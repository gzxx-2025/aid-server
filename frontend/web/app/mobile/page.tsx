'use client'

/* eslint-disable @next/next/no-img-element -- 本地宣传 SVG 与公开配置直链需保持静态导出行为 */

import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import PublicBrandLogo from '@/components/atoms/PublicBrandLogo'
import { message } from 'antd'
import { useSyncExternalStore } from 'react'
import picMod from '~/assets/img/icon/pic.svg'
import { assetUrl } from '~/utils/assetUrl'
import styles from './mobile-page.module.css'

const picUrl = assetUrl(picMod)
const subscribeToLocation = () => () => undefined
const getBrowserHost = () => window.location.host
const getServerHost = () => '当前站点'
const getBrowserOrigin = () => window.location.origin
const getServerOrigin = () => ''

/** 移动端拦截页：请使用电脑端打开（开源版：PublicBrandLogo + 当前站点地址） */
export default function MobilePage() {
  const siteAddress = useSyncExternalStore(subscribeToLocation, getBrowserHost, getServerHost)
  const siteOrigin = useSyncExternalStore(subscribeToLocation, getBrowserOrigin, getServerOrigin)

  async function copyPcUrl() {
    const text = siteOrigin || (typeof window !== 'undefined' ? window.location.origin : '')
    if (!text) {
      message.error('复制失败，请手动复制地址')
      return
    }
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        const input = document.createElement('input')
        input.value = text
        document.body.appendChild(input)
        input.select()
        document.execCommand('copy')
        document.body.removeChild(input)
      }
      message.success('电脑端地址已复制')
    } catch {
      message.error('复制失败，请手动复制地址')
    }
  }

  return (
    <div className={styles.page}>
      <HtmlShellClass classes="mobile-only-shell" />
      <div className={styles.panel}>
        <PublicBrandLogo className={styles.logo} alt="平台标识" compactFallback />
        <div className={styles.hero}>
          <img src={picUrl} alt="用AI重新定义短剧创作" className={styles.heroImage} />
        </div>
        <div className={styles.tip}>
          <p className={styles.tipTitle}>请使用电脑端打开本网页</p>
          <p className={styles.tipDomain}>{siteAddress}</p>
          <button type="button" className={styles.copyButton} onClick={copyPcUrl}>
            复制电脑端地址
          </button>
        </div>
      </div>
    </div>
  )
}
