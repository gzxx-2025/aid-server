import { describe, expect, it } from 'vitest'
import openImagePreviewModalSource from './openImagePreviewModal.tsx?raw'

describe('openImagePreviewModal stacking', () => {
  it('computes z-index from currently open modals instead of a fixed 1100', () => {
    expect(openImagePreviewModalSource).toContain('resolveStackedModalZIndex')
    expect(openImagePreviewModalSource).not.toMatch(/zIndex:\s*1100/)
  })
})
