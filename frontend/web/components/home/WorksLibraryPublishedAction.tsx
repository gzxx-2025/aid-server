'use client'

import type { MouseEvent as ReactMouseEvent } from 'react'
import publishedIcon from '~/assets/img/icon/yfb-icon.svg'
import { assetUrl } from '~/utils/assetUrl'
import './WorksLibraryPublishedAction.css'

const publishedIconUrl = assetUrl(publishedIcon)

export interface WorksLibraryPublishedActionProps {
  loading?: boolean
  onCancel?: () => void
}

export function WorksLibraryPublishedAction({
  loading,
  onCancel
}: WorksLibraryPublishedActionProps) {
  function handleClick(e: ReactMouseEvent<HTMLButtonElement>) {
    e.stopPropagation()
    onCancel?.()
  }

  return (
    <button
      type="button"
      className="works-lib-published-action"
      disabled={loading}
      aria-busy={loading}
      aria-label="取消发布"
      onClick={handleClick}
    >
      <span className="works-lib-published-action__state works-lib-published-action__state--default">
        <img src={publishedIconUrl} alt="" width={16} height={16} />
        <span>已发布</span>
      </span>
      <span className="works-lib-published-action__state works-lib-published-action__state--cancel">
        {loading ? '取消中…' : '取消发布'}
      </span>
    </button>
  )
}

export default WorksLibraryPublishedAction
