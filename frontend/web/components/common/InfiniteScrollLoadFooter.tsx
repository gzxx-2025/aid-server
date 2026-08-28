import './InfiniteScrollLoadFooter.css'

export interface InfiniteScrollLoadFooterProps {
  loading?: boolean
  hasMore?: boolean
  hasItems?: boolean
  loadingText?: string
  endText?: string
}

/** 无限滚动列表底部：加载中三点动画 / 已加载全部提示 */
export function InfiniteScrollLoadFooter({
  loading = false,
  hasMore = true,
  hasItems = false,
  loadingText = '加载中…',
  endText = '已加载全部'
}: InfiniteScrollLoadFooterProps) {
  if (loading) {
    return (
      <div
        className="infinite-scroll-load-footer flex items-center justify-center gap-[8px] pt-[14px] pb-[18px]"
        aria-live="polite"
      >
        <span
          className="infinite-scroll-load-footer__dots inline-flex items-center gap-[6px]"
          aria-hidden="true"
        >
          <span className="infinite-scroll-load-footer__dot" />
          <span className="infinite-scroll-load-footer__dot" />
          <span className="infinite-scroll-load-footer__dot" />
        </span>
        <span className="infinite-scroll-load-footer__text text-[12px] text-[#8e97a5]">
          {loadingText}
        </span>
      </div>
    )
  }

  if (!hasMore && hasItems) {
    return (
      <div className="infinite-scroll-load-footer infinite-scroll-load-footer--end flex items-center justify-center pt-[14px] pb-[18px] text-[12px] text-[#8e97a5]">
        {endText}
      </div>
    )
  }

  return null
}
