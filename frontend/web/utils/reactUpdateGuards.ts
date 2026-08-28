export interface PreviewExportBusyState {
  exporting: boolean
  segmentsDownloading: boolean
}

interface PreviewExportBusyLike {
  exporting?: boolean
  segmentsDownloading?: boolean
}

/** Retain the current object when a bridge re-registers without changing UI state. */
export function retainPreviewExportBusyState(
  current: PreviewExportBusyState,
  bridge: PreviewExportBusyLike | null
): PreviewExportBusyState {
  const exporting = Boolean(bridge?.exporting)
  const segmentsDownloading = Boolean(bridge?.segmentsDownloading)
  if (
    current.exporting === exporting &&
    current.segmentsDownloading === segmentsDownloading
  ) {
    return current
  }
  return { exporting, segmentsDownloading }
}

/** Position listeners may fire for unrelated nested scrolling; unchanged geometry is not state. */
export function retainFloatingPosition(
  current: Record<string, string>,
  next: { left: string; top: string }
): Record<string, string> {
  return current.left === next.left && current.top === next.top ? current : next
}
