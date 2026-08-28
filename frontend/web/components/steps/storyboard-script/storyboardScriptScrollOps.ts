import type { MutableRefObject } from 'react'

interface StoryboardScriptScrollRefs {
  stepRootRef: MutableRefObject<HTMLDivElement | null>
  listRef: MutableRefObject<HTMLDivElement | null>
  bottomAddBarRef: MutableRefObject<HTMLDivElement | null>
}

export function createStoryboardScriptScrollOps(refs: StoryboardScriptScrollRefs) {
  const scrollToLatestPanel = (behavior: ScrollBehavior = 'smooth') => {
    const run = () => {
      const preview = refs.stepRootRef.current?.closest('.preview-content') as HTMLElement | null
      if (preview) {
        const maxScroll = Math.max(0, preview.scrollHeight - preview.clientHeight)
        if (maxScroll > 0) {
          preview.scrollTo({ top: maxScroll, behavior })
          return
        }
      }
      const target =
        (refs.bottomAddBarRef.current as HTMLElement | null) ||
        (refs.listRef.current?.querySelector(
          '.storyboard-list-item:last-of-type'
        ) as HTMLElement | null)
      target?.scrollIntoView({ behavior, block: 'end' })
    }
    setTimeout(() => setTimeout(() => requestAnimationFrame(run), 0), 0)
  }

  function getPreviewScrollContainer(): HTMLElement | null {
    return refs.stepRootRef.current?.closest('.preview-content') as HTMLElement | null
  }

  function getPanelListItem(index: number): HTMLElement | null {
    return refs.listRef.current?.querySelector(
      `[data-panel-index="${index}"]`
    ) as HTMLElement | null
  }

  function scrollToPanelIndex(targetIndex: number, behavior: ScrollBehavior = 'smooth') {
    let attempts = 0
    const run = () => {
      attempts += 1
      const preview = getPreviewScrollContainer()
      const target = getPanelListItem(targetIndex)
      if (!target) {
        if (attempts < 8) requestAnimationFrame(run)
        return
      }
      if (targetIndex < 2 && (!preview || preview.scrollTop <= 8)) return
      if (preview) {
        const previewRect = preview.getBoundingClientRect()
        const targetRect = target.getBoundingClientRect()
        const alreadyVisible =
          targetRect.top >= previewRect.top + 16 && targetRect.bottom <= previewRect.bottom - 8
        if (alreadyVisible) return
        const nextTop = preview.scrollTop + targetRect.top - previewRect.top - 16
        preview.scrollTo({ top: Math.max(0, nextTop), behavior })
        return
      }
      target.scrollIntoView({ behavior, block: 'start' })
    }
    setTimeout(() => setTimeout(() => requestAnimationFrame(run), 0), 0)
  }

  return { scrollToLatestPanel, scrollToPanelIndex }
}
