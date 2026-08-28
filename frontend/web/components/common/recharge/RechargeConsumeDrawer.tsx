'use client'

import { InfiniteScrollLoadFooter } from '@/components/common/InfiniteScrollLoadFooter'
import { useInfiniteScrollPagination } from '@/hooks/useInfiniteScrollPagination'
import { Drawer,message } from 'antd'
import { useEffect,useRef,useState } from 'react'
import type { CreditConsumeRecordRow } from '~/types/business-api'
import { creditConsumeList } from '~/utils/businessApi'
import { consumeAmountLabel,consumeDetailItems,formatConsumeChange } from './rechargeFormat'

export interface RechargeConsumeDrawerProps {
  open: boolean
  onClose: () => void
}

/** 消耗明细抽屉：按任务聚合的积分变动 + 触底分页加载 */
export function RechargeConsumeDrawer({ open, onClose }: RechargeConsumeDrawerProps) {
  const consumeScrollRef = useRef<HTMLDivElement | null>(null)
  const {
    items: consumeRows,
    loading: consumeLoading,
    loadingMore: consumeLoadingMore,
    loadError: consumeLoadError,
    isEmpty: consumeIsEmpty,
    hasMore: consumeHasMore,
    appendTick,
    reload: reloadConsumeList,
    unbindScroll: unbindConsumeScroll,
    onScroll: onConsumeScroll
  } = useInfiniteScrollPagination<CreditConsumeRecordRow>(
    consumeScrollRef,
    async (pageNum, pageSize) => {
      try {
        const data = await creditConsumeList({ pageNum, pageSize })
        return {
          rows: data.rows,
          hasMore: pageNum * pageSize < data.total
        }
      } catch (e: any) {
        message.error(e?.msg ?? e?.message ?? '获取消耗明细失败')
        throw e
      }
    },
    { pageSize: 20 }
  )

  // 对应原 TransitionGroup enter：仅上拉追加的新条目做渐现，首屏不动画
  const prevCountRef = useRef(0)
  const [animateFromIndex, setAnimateFromIndex] = useState<number | null>(null)
  useEffect(() => {
    if (appendTick > 0) setAnimateFromIndex(prevCountRef.current)
    prevCountRef.current = consumeRows.length
  }, [appendTick, consumeRows.length])

  useEffect(() => {
    if (open) {
      prevCountRef.current = 0
      setAnimateFromIndex(null)
      void reloadConsumeList().then(() => {
        requestAnimationFrame(() => onConsumeScroll())
      })
    } else {
      unbindConsumeScroll()
    }
    // 与原 watch(showConsume) 对齐：仅在开关变化时触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title="消耗明细"
      size={520}
      mask={{ closable: true }}
      rootClassName="recharge-order-drawer recharge-consume-drawer"
    >
      <p className="consume-drawer__hint">按任务聚合展示积分变动，单位：Credits</p>
      <div ref={consumeScrollRef} className="consume-drawer__scroll" onScroll={onConsumeScroll}>
        {consumeLoading && !consumeRows.length ? (
          <div className="order-empty">加载中…</div>
        ) : consumeLoadError ? (
          <div className="order-empty consume-drawer__error">加载失败，请稍后重试</div>
        ) : consumeIsEmpty ? (
          <div className="order-empty">暂无消耗记录</div>
        ) : (
          <div className="consume-list">
            {consumeRows.map((row, idx) => (
              <article
                key={row.bizTraceId}
                className={`consume-card${
                  animateFromIndex != null && idx >= animateFromIndex
                    ? ' consume-card--appended'
                    : ''
                }`}
              >
                <header className="consume-card__head">
                  <span className="consume-card__type">{row.bizTypeName || row.bizType}</span>
                  <time className="consume-card__time">{row.createTime || '—'}</time>
                </header>
                <div className="consume-card__amount-row">
                  <span
                    className={`consume-card__amount${
                      row.changeAmount < 0
                        ? ' consume-card__amount--consume'
                        : row.changeAmount > 0
                          ? ' consume-card__amount--refund'
                          : ' consume-card__amount--neutral'
                    }`}
                  >
                    {formatConsumeChange(row.changeAmount)}
                  </span>
                  <span className="consume-card__amount-label">{consumeAmountLabel(row)}</span>
                </div>
                {row.bizName ? <p className="consume-card__biz-name">{row.bizName}</p> : null}
                {row.modelName ? <p className="consume-card__model">模型：{row.modelName}</p> : null}
                {consumeDetailItems(row).length ? (
                  <ul className="consume-card__details">
                    {consumeDetailItems(row).map((item, idx2) => (
                      <li key={idx2}>{item}</li>
                    ))}
                  </ul>
                ) : null}
              </article>
            ))}
          </div>
        )}
        <InfiniteScrollLoadFooter
          loading={consumeLoadingMore}
          hasMore={consumeHasMore}
          hasItems={consumeRows.length > 0}
          endText="已加载全部记录"
        />
      </div>
    </Drawer>
  )
}

export default RechargeConsumeDrawer
