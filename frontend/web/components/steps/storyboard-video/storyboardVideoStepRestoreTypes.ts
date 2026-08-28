import type { MutableRefObject } from 'react'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'

export interface StoryboardVideoStepRestoreOptions {
  panelsRef: MutableRefObject<StoryboardVideoPanel[]>
  scriptPanelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(next: StoryboardVideoPanel[]) => void>
  batchVideoSubmittingRef: MutableRefObject<boolean>
  isVideoModalOpenRef: MutableRefObject<boolean>
  resolvePanelStoryboardId: (index: number) => number | null
  openEditVideoModalAt: (index: number) => void
  reopenVideoModalAt: (index: number) => void
  storyboardListSyncReady: boolean
  isHydrated: boolean
  currentProjectId: number | null
  currentEpisodeId: number | null
  routeProjectId: unknown
  routeEpisodeId: unknown
  panelsLength: number
  scriptPanelsLength: number
}
