'use client'

import { CopyOutlined } from '@ant-design/icons'
import { Button,message } from 'antd'
import { useEffect,useRef,useState } from 'react'
import groupAvtorUrl from '~/assets/img/home/Group-avtor.svg'
import inviteHeroPicUrl from '~/assets/img/home/pic_yq.svg'
import type { InviteInfoVO,InvitedUserVO } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { userInviteInfo,userInviteUsers } from '~/utils/businessApi'
import { copyPlainText } from '~/utils/clipboard'
import { noDataIconUrl } from '~/utils/emptyImageIcon'
import './InvitePanel.css'

const defaultAvatar = assetUrl(groupAvtorUrl)

function formatCredits(v: unknown): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '0'
  return n.toFixed(2).replace(/\.00$/, '')
}

function parseRegisterDate(raw?: string): Date | null {
  if (!raw) return null
  const normalized = String(raw).trim().replace(/-/g, '/').replace('T', ' ')
  const d = new Date(normalized)
  if (Number.isNaN(d.getTime())) return null
  return d
}

function formatRegisterTime(raw?: string): string {
  const d = parseRegisterDate(raw)
  if (!d) return raw || '—'
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}/${m}/${day}`
}

async function copyText(text: string, okMsg: string) {
  const copied = await copyPlainText(text)
  if (copied) {
    message.success(okMsg)
    return
  }
  message.error('复制失败，请手动复制')
}

export default function InvitePanel() {
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [info, setInfo] = useState<InviteInfoVO | null>(null)

  const [users, setUsers] = useState<InvitedUserVO[]>([])
  const [usersTotal, setUsersTotal] = useState(0)
  const [usersLoading, setUsersLoading] = useState(false)

  // 分页页码不参与渲染，用 ref 保证同步递增后立刻取到最新值（对齐原 usersPage.value += 1）
  const usersPageRef = useRef(1)
  const usersLoadingRef = useRef(false)

  const rebateRatioText = (() => {
    const n = Number(info?.rebateRatio)
    return Number.isFinite(n) ? String(n) : '10'
  })()

  const monthInvitedCount = (() => {
    const now = new Date()
    const y = now.getFullYear()
    const m = now.getMonth()
    return users.filter((u) => {
      const d = parseRegisterDate(u.registerTime)
      return d != null && d.getFullYear() === y && d.getMonth() === m
    }).length
  })()

  async function loadInfo(): Promise<InviteInfoVO | null> {
    setLoading(true)
    setLoadError('')
    try {
      const data = await userInviteInfo()
      setInfo(data)
      return data
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      setLoadError(err?.msg || err?.message || '加载失败')
      return null
    } finally {
      setLoading(false)
    }
  }

  async function loadUsers(reset = false) {
    if (usersLoadingRef.current) return
    if (reset) {
      usersPageRef.current = 1
      setUsers([])
    }
    usersLoadingRef.current = true
    setUsersLoading(true)
    try {
      const res = await userInviteUsers({ pageNum: usersPageRef.current, pageSize: 10 })
      setUsersTotal(res.total)
      setUsers((prev) => (reset ? res.data : [...prev, ...res.data]))
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载邀请用户失败')
    } finally {
      usersLoadingRef.current = false
      setUsersLoading(false)
    }
  }

  function loadMoreUsers() {
    usersPageRef.current += 1
    void loadUsers(false)
  }

  function copyInvite() {
    const code = String(info?.inviteCode || '').trim()
    if (!code) return
    void copyText(code, '邀请码已复制')
  }

  function copyInviteLink() {
    const code = String(info?.inviteCode || '').trim()
    if (!code) return
    const origin = typeof window !== 'undefined' ? window.location.origin : ''
    void copyText(`${origin}/login?invite=${encodeURIComponent(code)}`, '邀请链接已复制')
  }

  useEffect(() => {
    void (async () => {
      const data = await loadInfo()
      if (data?.enabled) {
        void loadUsers(true)
      }
    })()
     
  }, [])

  return (
    <div className="home-new-sub-page invite-page">
      <div className="page-content invite-page__inner">
        {/* 顶部：左侧标题+邀请码卡，右侧插画（与设计稿同结构） */}
        <section className="invite-page__top">
          <div className="invite-page__top-main">
            <header className="invite-page__header">
              <h1 className="invite-page__title">邀请有礼</h1>
              <p className="invite-page__subtitle">
                邀请好友注册，分享专属邀请码，用户充值后您将获得{rebateRatioText}%的积分
              </p>
            </header>

            {loading && !info ? (
              <div className="invite-page__card invite-page__card--state">加载中…</div>
            ) : loadError ? (
              <div className="invite-page__card invite-page__card--state invite-page__card--error">
                {loadError}
              </div>
            ) : info && !info.enabled ? (
              <div className="invite-page__card invite-page__card--state">邀请活动暂未开启</div>
            ) : info ? (
              <div className="invite-page__card" aria-label="我的邀请码">
                <div className="invite-page__card-label">我的邀请码</div>
                <div className="invite-page__code-row">
                  <div className="invite-page__code-box">
                    <span className="invite-page__code">{info.inviteCode || '—'}</span>
                    <button
                      type="button"
                      className="invite-page__code-copy"
                      disabled={!info.inviteCode}
                      aria-label="复制邀请码"
                      onClick={copyInvite}
                    >
                      <CopyOutlined />
                    </button>
                  </div>
                  <button
                    type="button"
                    className="invite-page__link-btn"
                    disabled={!info.inviteCode}
                    onClick={copyInviteLink}
                  >
                    <span className="invite-page__link-btn-text">复制邀请链接</span>
                  </button>
                </div>
                <div className="invite-page__stats">
                  <div className="invite-page__stat">
                    <span className="invite-page__stat-label">已邀请</span>
                    <span className="invite-page__stat-num">{info.invitedCount ?? 0}</span>
                    <span className="invite-page__stat-unit">人</span>
                  </div>
                  <div className="invite-page__stat">
                    <span className="invite-page__stat-label">已获返利</span>
                    <span className="invite-page__stat-num">{formatCredits(info.totalRebate)}</span>
                    <span className="invite-page__stat-unit">积分</span>
                  </div>
                </div>
              </div>
            ) : null}
          </div>

          <div className="invite-page__pic-wrap" aria-hidden="true">
            <img
              className="invite-page__pic"
              src={assetUrl(inviteHeroPicUrl)}
              alt=""
              width={310}
              height={268}
            />
          </div>
        </section>

        {info?.enabled && (
          <section className="invite-page__records" aria-label="邀请记录">
            <div className="invite-page__records-head">
              <h2 className="invite-page__records-title">邀请记录</h2>
              <span className="invite-page__records-meta">本月已邀请 {monthInvitedCount} 人</span>
            </div>

            <div className="invite-page__table" role="table" aria-label="邀请用户列表">
              <div className="invite-page__thead" role="row">
                <span className="invite-page__th invite-page__col-index" role="columnheader">序号</span>
                <span className="invite-page__th invite-page__col-user" role="columnheader">用户</span>
                <span className="invite-page__th invite-page__col-time" role="columnheader">注册时间</span>
                <span className="invite-page__th invite-page__col-points" role="columnheader">获得积分</span>
              </div>

              {usersLoading && !users.length ? (
                <div className="invite-page__state invite-page__state--table">加载中…</div>
              ) : users.length ? (
                <>
                  {users.map((u, i) => (
                    <div
                      key={`${u.nickName}-${u.registerTime}-${i}`}
                      className={`invite-page__tr${i % 2 === 1 ? ' is-alt' : ''}`}
                      role="row"
                    >
                      <span className="invite-page__td invite-page__col-index" role="cell">
                        <span className="invite-page__index">{i + 1}</span>
                      </span>
                      <span className="invite-page__td invite-page__col-user" role="cell">
                        <img
                          className="invite-page__avatar"
                          src={u.avatar || defaultAvatar}
                          alt=""
                          width={24}
                          height={24}
                        />
                        <span className="invite-page__user-name">{u.nickName || '用户'}</span>
                      </span>
                      <span className="invite-page__td invite-page__col-time" role="cell">
                        {formatRegisterTime(u.registerTime)}
                      </span>
                      <span className="invite-page__td invite-page__col-points" role="cell">
                        {formatCredits(u.totalRebate)}
                      </span>
                    </div>
                  ))}
                  {usersTotal > users.length && (
                    <div className="invite-page__more">
                      <Button size="small" loading={usersLoading} onClick={loadMoreUsers}>
                        加载更多
                      </Button>
                    </div>
                  )}
                </>
              ) : (
                <div className="invite-page__empty">
                  <img
                    className="invite-page__empty-icon empty-image-icon empty-image-icon--lg"
                    src={assetUrl(noDataIconUrl)}
                    alt=""
                  />
                  <p className="invite-page__empty-text">暂无记录</p>
                </div>
              )}
            </div>
          </section>
        )}
      </div>
    </div>
  )
}
