import HomeNewSidebar from '~/components/layout/HomeNewSidebar'

interface CreateFlowSkeletonProps {
  displayStepCount: number
  isSeriesFlowChrome: boolean
  isSeriesScriptUpload: boolean
  isSeriesEpisodeList: boolean
}

export function CreateFlowSkeleton({
  displayStepCount,
  isSeriesFlowChrome,
  isSeriesScriptUpload,
  isSeriesEpisodeList
}: CreateFlowSkeletonProps) {
  return (
    <div className="main-layout">
      <HomeNewSidebar skeleton />
      <div className="create-main">
        {!isSeriesFlowChrome ? (
          <div className="create-workflow create-workflow--skeleton">
            <div className="create-workflow__scroll">
              {Array.from({ length: displayStepCount }, (_, index) => (
                <div
                  key={index}
                  className="flow-step-pill flow-step-pill--strip flow-step-pill--skeleton"
                >
                  <div className="skeleton-icon" />
                  <div className="skeleton-line skeleton-line-flow-title" />
                </div>
              ))}
            </div>
          </div>
        ) : null}
        <div className="preview-panel">
          <div className="preview-header">
            <div className="skeleton-title skeleton-title-lg" />
            {!isSeriesFlowChrome ? (
              <div className="preview-actions">
                <div className="skeleton-btn" />
                <div className="skeleton-btn skeleton-btn-primary" />
              </div>
            ) : null}
          </div>
          <div
            className={`preview-content${isSeriesScriptUpload ? ' step-series-script-upload' : ''}${isSeriesEpisodeList ? ' step-series-episode-list' : ''}`}
          >
            <div className="skeleton-block" />
          </div>
        </div>
      </div>
    </div>
  )
}
