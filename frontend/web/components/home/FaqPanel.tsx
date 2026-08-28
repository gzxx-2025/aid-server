'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import { SearchOutlined, RightOutlined } from '@ant-design/icons'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { userFaqDetail, userFaqList } from '~/utils/businessApi'
import type { UserFaqDetail, UserFaqListItem } from '~/types/business-api'
import { sanitizeDisplayHtml } from '~/utils/safeDisplayHtml'
import './FaqPanel.css'

const MAX_FAQ_LIST_PAGES = 100

function itemCategory(item: UserFaqListItem) {
  const cat = String(item.category || '').trim()
  return cat || '其他'
}

function computeCategories(items: UserFaqListItem[]) {
  const orderMap = new Map<string, number>()
  for (const item of items) {
    const cat = itemCategory(item)
    const sort = item.sortOrder ?? Number.MAX_SAFE_INTEGER
    const prev = orderMap.get(cat)
    if (prev == null || sort < prev) orderMap.set(cat, sort)
  }
  return [...orderMap.entries()]
    .sort((a, b) => a[1] - b[1] || a[0].localeCompare(b[0], 'zh-CN'))
    .map(([cat]) => cat)
}

export default function FaqPanel() {
  const { exchangeImageUrl, loadPublicConfig } = useAuthPublicConfig()

  const [keyword, setKeyword] = useState('')
  const [activeCategory, setActiveCategory] = useState('')
  const [allItems, setAllItems] = useState<UserFaqListItem[]>([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [detailsLoading, setDetailsLoading] = useState(false)
  const [detailCache, setDetailCache] = useState<Map<number, UserFaqDetail>>(new Map())

  const detailCacheRef = useRef(detailCache)
  detailCacheRef.current = detailCache
  const keywordRef = useRef(keyword)
  keywordRef.current = keyword
  const listLoadGenerationRef = useRef(0)
  const detailLoadGenerationRef = useRef(0)
  const keywordTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const categories = useMemo(() => computeCategories(allItems), [allItems])

  const categoryItems = useMemo(() => {
    if (!activeCategory) return []
    return allItems.filter((item) => itemCategory(item) === activeCategory)
  }, [allItems, activeCategory])

  const hasLoadedDetails = categoryItems.some((item) => detailCache.has(item.id))

  function getAnswerHtml(id: number) {
    const detail = detailCache.get(id)
    const raw = String(detail?.content || '').trim()
    if (!raw) return '<p>暂无内容</p>'
    return sanitizeDisplayHtml(raw)
  }

  async function loadAllItems() {
    const generation = ++listLoadGenerationRef.current
    setLoading(true)
    setLoadError(false)
    try {
      const merged: UserFaqListItem[] = []
      let pageNum = 1
      let hasMore = true
      while (hasMore && pageNum <= MAX_FAQ_LIST_PAGES) {
        const page = await userFaqList({
          pageNum,
          pageSize: 50,
          keyword: keywordRef.current.trim() || undefined
        })
        if (generation !== listLoadGenerationRef.current) return
        if (!page.rows.length) break
        merged.push(...page.rows)
        hasMore = page.hasMore
        pageNum += 1
      }
      if (generation !== listLoadGenerationRef.current) return
      setAllItems(merged)
      // 对齐原 ensureActiveCategory：无分类清空；当前分类失效时回落到第一个
      const cats = computeCategories(merged)
      setActiveCategory((prev) => {
        if (!cats.length) return ''
        if (!prev || !cats.includes(prev)) return cats[0]
        return prev
      })
    } catch {
      if (generation !== listLoadGenerationRef.current) return
      setAllItems([])
      setLoadError(true)
    } finally {
      if (generation === listLoadGenerationRef.current) setLoading(false)
    }
  }

  async function loadCategoryDetails(items: UserFaqListItem[]) {
    const generation = ++detailLoadGenerationRef.current
    const toFetch = items.filter((item) => !detailCacheRef.current.has(item.id))
    if (!toFetch.length) return

    setDetailsLoading(true)
    try {
      const results = await Promise.allSettled(
        toFetch.map(async (item) => {
          const detail = await userFaqDetail({ id: item.id })
          return { id: item.id, detail }
        })
      )
      if (generation !== detailLoadGenerationRef.current) return
      const nextCache = new Map(detailCacheRef.current)
      let failed = false
      for (const result of results) {
        if (result.status === 'fulfilled') {
          nextCache.set(result.value.id, result.value.detail)
        } else {
          failed = true
        }
      }
      detailCacheRef.current = nextCache
      setDetailCache(nextCache)
      if (failed) message.error('部分问题详情加载失败')
    } finally {
      if (generation === detailLoadGenerationRef.current) setDetailsLoading(false)
    }
  }

  function selectCategory(cat: string) {
    setActiveCategory(cat)
  }

  function reloadAll() {
    detailCacheRef.current = new Map()
    setDetailCache(new Map())
    void loadAllItems()
  }

  // 对齐原 watch(keyword)：仅在关键词变化后 320ms 防抖重载（跳过首渲）
  const keywordMountedRef = useRef(false)
  useEffect(() => {
    if (!keywordMountedRef.current) {
      keywordMountedRef.current = true
      return
    }
    if (keywordTimerRef.current) clearTimeout(keywordTimerRef.current)
    keywordTimerRef.current = setTimeout(() => {
      keywordTimerRef.current = null
      detailCacheRef.current = new Map()
      setDetailCache(new Map())
      void loadAllItems()
    }, 320)
     
  }, [keyword])

  // 对齐原 watch(categoryItems)：分类条目变化时补拉详情
  useEffect(() => {
    void loadCategoryDetails(categoryItems)
     
  }, [categoryItems])

  useEffect(() => {
    void loadAllItems()
    void loadPublicConfig()
    return () => {
      listLoadGenerationRef.current += 1
      detailLoadGenerationRef.current += 1
      if (keywordTimerRef.current) clearTimeout(keywordTimerRef.current)
      keywordTimerRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="home-new-sub-page help-center-page">
      <div className="page-content help-center-page__inner">
        <header className="help-center-page__header">
          <h1 className="help-center-page__title">常见问题</h1>
          <p className="help-center-page__subtitle">查找使用说明与解答，快速上手创作流程</p>
        </header>

        <div className="help-center-page__main">
          <div className="help-center-page__search">
            <SearchOutlined className="help-center-page__search-ico" />
            <input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              type="search"
              className="help-center-page__search-input"
              placeholder="搜索资产…"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  reloadAll()
                }
              }}
            />
          </div>

          <div className="help-center-page__body">
            <nav className="help-center-page__categories" aria-label="问题分类">
              {loading && !allItems.length ? (
                <div className="help-center-page__side-state">加载中…</div>
              ) : loadError ? (
                <div className="help-center-page__side-state help-center-page__side-state--error">
                  加载失败，请稍后重试
                </div>
              ) : !categories.length ? (
                <div className="help-center-page__side-state">暂无分类</div>
              ) : null}

              {categories.map((cat) => (
                <button
                  key={cat}
                  type="button"
                  className={`help-center-page__category${activeCategory === cat ? ' is-active' : ''}`}
                  onClick={() => selectCategory(cat)}
                >
                  <span className="help-center-page__category-label">{cat}</span>
                  <RightOutlined className="help-center-page__category-arrow" />
                </button>
              ))}
            </nav>

            <section className="help-center-page__answers" aria-label="问题解答">
              {loading && !allItems.length ? (
                <div className="help-center-page__answers-state">加载中…</div>
              ) : loadError ? (
                <div className="help-center-page__answers-state help-center-page__answers-state--error">
                  加载失败，请稍后重试
                </div>
              ) : !activeCategory ? (
                <div className="help-center-page__answers-state">请从左侧选择分类</div>
              ) : !categoryItems.length ? (
                <div className="help-center-page__answers-state">暂无相关问题</div>
              ) : detailsLoading && !hasLoadedDetails ? (
                <div className="help-center-page__answers-state">加载详情中…</div>
              ) : (
                <div className="help-center-page__qa-list">
                  {categoryItems.map((item, index) => (
                    <article key={item.id} className="help-center-page__qa-item">
                      <h2 className="help-center-page__qa-question">
                        {index + 1}、{item.title}
                      </h2>
                      <div
                        className="help-center-page__qa-answer"
                        dangerouslySetInnerHTML={{ __html: getAnswerHtml(item.id) }}
                      />
                    </article>
                  ))}
                </div>
              )}
            </section>
          </div>

          <footer className="help-center-page__footer">
            <div className="help-center-page__qrcode-wrap">
              {exchangeImageUrl ? (
                <img src={exchangeImageUrl} alt="客服二维码" className="help-center-page__qrcode" />
              ) : (
                <div className="help-center-page__qrcode help-center-page__qrcode--placeholder" />
              )}
            </div>
            <p className="help-center-page__qrcode-label">扫码加客服</p>
          </footer>
        </div>
      </div>
    </div>
  )
}
