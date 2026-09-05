/// <reference types="vite/client" />

import { describe, expect, it } from 'vitest'
import previewSource from '../components/steps/VideoPreview.tsx?raw'
import sidebarSource from '../hooks/useCreateFlowSidebarChrome.ts?raw'
import {
  retainFloatingPosition,
  retainPreviewExportBusyState
} from './reactUpdateGuards'

describe('React update-loop guards', () => {
  it('retains export busy state when a bridge only changes object identity', () => {
    const current = { exporting: false, segmentsDownloading: false }

    expect(
      retainPreviewExportBusyState(current, {
        exporting: false,
        segmentsDownloading: false
      })
    ).toBe(current)
    expect(retainPreviewExportBusyState(current, null)).toBe(current)
    expect(
      retainPreviewExportBusyState(current, {
        exporting: true,
        segmentsDownloading: false
      })
    ).toEqual({ exporting: true, segmentsDownloading: false })
  })

  it('retains an unchanged floating position object', () => {
    const current = { left: '82px', top: '640px' }

    expect(retainFloatingPosition(current, { left: '82px', top: '640px' })).toBe(current)
    expect(retainFloatingPosition(current, { left: '92px', top: '640px' })).toEqual({
      left: '92px',
      top: '640px'
    })
  })

  it('depends on the stable bridge callback and ignores scroll while the menu is closed', () => {
    expect(previewSource).toContain('const registerPreviewExportBridge = shell?.registerPreviewExportBridge')
    expect(previewSource).toContain(
      '[ctx, registerPreviewExportBridge, S.exporting.value, S.segmentsDownloading.value]'
    )
    expect(sidebarSource).toContain('if (!showUserMenuCardRef.current) return')
    expect(sidebarSource).toContain('retainFloatingPosition(current, nextStyle)')
  })
})
