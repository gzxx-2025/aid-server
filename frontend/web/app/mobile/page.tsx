'use client'

import { CheckOutlined, CopyOutlined, DesktopOutlined } from '@ant-design/icons'
import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import PublicBrandLogo from '@/components/atoms/PublicBrandLogo'
import { message } from 'antd'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import picMod from '~/assets/img/icon/pic.svg'
import { assetUrl } from '~/utils/assetUrl'
import styles from './mobile-page.module.css'

const picUrl = assetUrl(picMod)
const subscribeToLocation = () => () => undefined
const getBrowserHost = () => window.location.host
const getServerHost = () => '当前站点'

/** 移动端拦截页：请使用电脑端打开 */
export default function MobilePage() {
  const siteAddress = useSyncExternalStore(subscribeToLocation, getBrowserHost, getServerHost)
  const [copied, setCopied] = useState(false)
  const copiedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (copiedTimerRef.current) {
        clearTimeout(copiedTimerRef.current)
      }
    }
  }, [])

  async function copyPcUrl() {
    const text = window.location.origin
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        const input = document.createElement('input')
        input.value = text
        input.setAttribute('aria-hidden', 'true')
        input.style.position = 'fixed'
        input.style.opacity = '0'
        document.body.appendChild(input)
        try {
          input.select()
          if (!document.execCommand('copy')) {
            throw new Error('Copy command was rejected')
          }
        } finally {
          input.remove()
        }
      }
      setCopied(true)
      if (copiedTimerRef.current) {
        clearTimeout(copiedTimerRef.current)
      }
      copiedTimerRef.current = setTimeout(() => setCopied(false), 1800)
      message.success('电脑端地址已复制')
    } catch {
      setCopied(false)
      message.error('复制失败，请手动复制地址')
    }
  }

  return (
    <main className={styles.page}>
      <HtmlShellClass classes="mobile-only-shell" />
      <div className={styles.ambientGlow} aria-hidden="true" />
      <section className={styles.panel} aria-label="电脑端访问提示">
        <header className={styles.brandBar}>
          <span className={styles.logoShell}>
            <PublicBrandLogo className={styles.logo} alt="平台标识" compactFallback />
          </span>
          <span className={styles.brandCopy}>
            <span className={styles.brandEyebrow}>DESKTOP CREATION</span>
            <span className={styles.brandTitle}>专业创作，请前往电脑端</span>
          </span>
        </header>

        <div className={styles.hero}>
          {/* eslint-disable-next-line @next/next/no-img-element -- 本地 SVG 作为完整宣传视觉，需保持其原始比例 */}
          <img src={picUrl} alt="AI 影视创作视觉" className={styles.heroImage} />
          <span className={styles.heroBorder} aria-hidden="true" />
        </div>

        <div className={styles.actionCard}>
          <span className={styles.deviceIcon} aria-hidden="true">
            <DesktopOutlined />
          </span>
          <div className={styles.actionHeading}>
            <h1>请在电脑端继续创作</h1>
            <p>复制下方地址，在电脑浏览器中打开即可使用完整功能</p>
          </div>

          <div className={styles.addressBar} aria-label={`电脑端地址：${siteAddress}`}>
            <span className={styles.addressDot} aria-hidden="true" />
            <span className={styles.addressText}>{siteAddress}</span>
          </div>

          <button
            type="button"
            className={styles.copyButton}
            onClick={copyPcUrl}
            aria-live="polite"
          >
            {copied ? <CheckOutlined aria-hidden="true" /> : <CopyOutlined aria-hidden="true" />}
            <span>{copied ? '已复制电脑端地址' : '复制电脑端地址'}</span>
          </button>
        </div>

      </section>
    </main>
  )
}
