'use client'

import type { KeyboardEvent, MouseEvent } from 'react'
import { LoadingOutlined } from '@ant-design/icons'
import { HistoryRecordWrap } from '~/components/common/HistoryRecordWrap'
import { VideoPosterThumb } from '~/components/common/VideoPosterThumb'
import { VIDEO_POSTER_PRIORITY } from '~/utils/ensureVideoPoster'
import type { DubbingNavEntry } from './types'

export interface DubbingHistoryPanelProps {
  navEntries: DubbingNavEntry[]
  selectedNavKey: string
  isSettingFinalDubbing: boolean
  deleteIcon: string
  isHistoryDubbingMain: (nav: DubbingNavEntry) => boolean
  canSetMainFromHistory: (nav: DubbingNavEntry) => boolean
  canDeleteHistoryDubbing: (nav: DubbingNavEntry) => boolean
  onNavClick: (key: string) => void
  onSetMainFromHistory: (nav: DubbingNavEntry) => void
  onUnsetMainFromHistory: (nav: DubbingNavEntry) => void
  onDeleteHistoryDubbing: (nav: DubbingNavEntry) => void
}

/** 左侧「生成记录」栏（与分镜视频弹窗一致：主视频角标 + hover 设主） */
export function DubbingHistoryPanel({
  navEntries,
  selectedNavKey,
  isSettingFinalDubbing,
  deleteIcon,
  isHistoryDubbingMain,
  canSetMainFromHistory,
  canDeleteHistoryDubbing,
  onNavClick,
  onSetMainFromHistory,
  onUnsetMainFromHistory,
  onDeleteHistoryDubbing
}: DubbingHistoryPanelProps) {
  function handleDeleteClick(event: MouseEvent<HTMLDivElement>, nav: DubbingNavEntry) {
    event.stopPropagation()
    event.preventDefault()
    onDeleteHistoryDubbing(nav)
  }

  function handleDeleteKeydown(event: KeyboardEvent<HTMLDivElement>, nav: DubbingNavEntry) {
    if (event.key !== 'Enter') return
    event.stopPropagation()
    event.preventDefault()
    onDeleteHistoryDubbing(nav)
  }

  return (
    <aside className="stage-history-panel">
      <h4 className="panel-title">生成记录</h4>
      <div className="history-list">
        {navEntries.length === 0 ? (
          <div className="history-empty-msg">暂无记录</div>
        ) : (
          navEntries.map((nav) => (
            <HistoryRecordWrap
              key={nav.key}
              showSetMain={canSetMainFromHistory(nav)}
              isMain={isHistoryDubbingMain(nav)}
              setMainLabel="设置为音画同步结果"
              selectedMainLabel="已设置音画同步结果"
              unsetMainLabel="取消音画同步结果"
              setMainLoading={isSettingFinalDubbing}
              onSetMain={() => onSetMainFromHistory(nav)}
              onUnsetMain={() => onUnsetMainFromHistory(nav)}
            >
              <button
                type="button"
                className={[
                  'history-item',
                  'dubbing-history-item',
                  selectedNavKey === nav.key ? 'active' : '',
                  isHistoryDubbingMain(nav) ? 'history-item--main' : '',
                  nav.type === 'loading' ? 'history-item--generating' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
                onClick={() => onNavClick(nav.key)}
              >
                {nav.type === 'loading' ? (
                  <div className="history-generating-mask" role="status" aria-live="polite">
                    <LoadingOutlined spin className="history-generating-mask__icon" />
                  </div>
                ) : nav.url ? (
                  /* 选中态只调整加载优先级；每条完成记录都必须展示视频首帧。 */
                  <VideoPosterThumb
                    src={nav.url}
                    priority={
                      selectedNavKey === nav.key
                        ? VIDEO_POSTER_PRIORITY.selectedHistory
                        : VIDEO_POSTER_PRIORITY.history
                    }
                    imgClass="history-thumb-video"
                    videoClass="history-thumb-video"
                    objectFit="cover"
                    allowVideoFallback
                    fallbackLazy={selectedNavKey !== nav.key}
                    fallbackGated={selectedNavKey !== nav.key}
                  />
                ) : (
                  <div className="history-empty">—</div>
                )}
                {canDeleteHistoryDubbing(nav) ? (
                  <div
                    className="history-delete-icon"
                    role="button"
                    tabIndex={0}
                    onClick={(e) => handleDeleteClick(e, nav)}
                    onKeyDown={(e) => handleDeleteKeydown(e, nav)}
                  >
                    <img src={deleteIcon} alt="删除" />
                  </div>
                ) : null}
              </button>
            </HistoryRecordWrap>
          ))
        )}
      </div>
    </aside>
  )
}
