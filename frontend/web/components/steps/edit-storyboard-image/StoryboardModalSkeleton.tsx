'use client'

/** 切换分镜 Tab 时的三栏骨架屏（原模板 .panel-skeleton 区块，纯静态） */
export function StoryboardModalSkeleton() {
  return (
    <div className="panel-skeleton right-panel-skeleton">
      <div className="skeleton-stage-layout">
        <aside className="skeleton-history-panel">
          <div className="skeleton-panel-title" />
          <div className="skeleton-history-list">
            {[1, 2, 3, 4, 5, 6].map((n) => (
              <div key={`sk-h-${n}`} className="skeleton-history-item" />
            ))}
          </div>
          <div className="skeleton-history-actions">
            <div className="skeleton-btn" />
            <div className="skeleton-btn" />
          </div>
        </aside>
        <section className="skeleton-canvas-panel">
          <div className="skeleton-canvas-toolbar">
            {[1, 2, 3, 4, 5].map((n) => (
              <div key={`sk-t-${n}`} className="skeleton-chip" />
            ))}
          </div>
          <div className="skeleton-canvas-main" />
        </section>
        <aside className="skeleton-config-panel">
          <div className="skeleton-config-tabs">
            <div className="skeleton-tab" />
            <div className="skeleton-tab" />
            <div className="skeleton-tab" />
          </div>
          <div className="skeleton-file-row" />
          <div className="skeleton-textarea" />
          <div className="skeleton-select-row">
            {[1, 2, 3, 4].map((n) => (
              <div key={`sk-s-${n}`} className="skeleton-select" />
            ))}
          </div>
          <div className="skeleton-primary-btn" />
        </aside>
      </div>
    </div>
  )
}

export default StoryboardModalSkeleton
