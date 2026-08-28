import type { MutableRefObject } from 'react'
import { useCreationStore } from '~/stores/creation'

export function isSameStoryboardVideoRecordList(
  leftValue: any[] | undefined,
  rightValue: any[] | undefined
): boolean {
  const left = Array.isArray(leftValue) ? leftValue : []
  const right = Array.isArray(rightValue) ? rightValue : []
  if (left.length !== right.length) return false
  return left.every((item, index) => {
    const other = right[index]
    return (
      String(item?.id ?? item?.url ?? item?.thumbnail ?? '') ===
        String(other?.id ?? other?.url ?? other?.thumbnail ?? '') &&
      !!item?._generating === !!other?._generating &&
      !!item?._localGeneratingPlaceholder === !!other?._localGeneratingPlaceholder &&
      !!item?.isStoryboardVideo === !!other?.isStoryboardVideo
    )
  })
}

export function clearPanelVideoGenFailureIfMainVideoSet(params: {
  sceneIndex: number
  videos: unknown
  resolvePanelStoryboardId: (index: number) => number | null
}) {
  const { sceneIndex, videos, resolvePanelStoryboardId } = params
  if (!Array.isArray(videos)) return
  const hasMainVideo = videos.some(
    (video) =>
      !!(video as { isStoryboardVideo?: boolean; url?: string }).isStoryboardVideo &&
      String((video as { url?: string }).url ?? '').trim()
  )
  if (!hasMainVideo) return
  const storyboardId = resolvePanelStoryboardId(sceneIndex)
  if (storyboardId == null) return
  const store = useCreationStore.getState()
  store.clearStoryboardPanelVideoGenError(storyboardId)
  store.clearStoryboardPanelVideoGenStatus(storyboardId)
}

export function scrollToLatestStoryboardVideoPanel(params: {
  rootRef: MutableRefObject<HTMLDivElement | null>
  listRef: MutableRefObject<HTMLDivElement | null>
  bottomAddBarRef: MutableRefObject<HTMLDivElement | null>
  behavior?: ScrollBehavior
}) {
  const { rootRef, listRef, bottomAddBarRef, behavior = 'smooth' } = params
  const run = () => {
    const preview = rootRef.current?.closest('.preview-content') as HTMLElement | null
    if (preview) {
      const maxScroll = Math.max(0, preview.scrollHeight - preview.clientHeight)
      if (maxScroll > 0) {
        preview.scrollTo({ top: maxScroll, behavior })
        return
      }
    }
    const target =
      bottomAddBarRef.current ||
      (listRef.current?.querySelector('.storyboard-list-item:last-of-type') as HTMLElement | null)
    target?.scrollIntoView({ behavior, block: 'end' })
  }
  setTimeout(() => setTimeout(() => requestAnimationFrame(run), 0), 0)
}
