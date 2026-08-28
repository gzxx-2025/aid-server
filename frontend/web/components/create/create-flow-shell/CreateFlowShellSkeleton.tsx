import HomeNewSidebar from '~/components/layout/HomeNewSidebar'

export function CreateFlowShellSkeleton(props: {
  seriesChrome: boolean
  seriesScriptUpload: boolean
  seriesEpisodeList: boolean
  stepCount: number
}) {
  return (
    <div className="main-layout">
      <HomeNewSidebar skeleton />
      <div className="create-main">
        {!props.seriesChrome ? (
          <div className="create-workflow create-workflow--skeleton">
            <div className="create-workflow__scroll">
              {Array.from({ length: props.stepCount }, (_, index) => (
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
            {!props.seriesChrome ? (
              <div className="preview-actions">
                <div className="skeleton-btn" />
                <div className="skeleton-btn skeleton-btn-primary" />
              </div>
            ) : null}
          </div>
          <div
            className={`preview-content${props.seriesScriptUpload ? ' step-series-script-upload' : ''}${props.seriesEpisodeList ? ' step-series-episode-list' : ''}`}
          >
            <div className="skeleton-block" />
          </div>
        </div>
      </div>
    </div>
  )
}
