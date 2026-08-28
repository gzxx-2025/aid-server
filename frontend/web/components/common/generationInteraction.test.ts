import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function readWorkspaceFile(path: string): string {
  return readFileSync(new URL(`../../${path}`, import.meta.url), 'utf-8')
}

describe('generation interaction contracts', () => {
  it('runs quote-wrapped generation actions directly without a confirmation popover', () => {
    const source = readWorkspaceFile('components/common/BillingQuoteConfirm.tsx')
    expect(source).not.toContain('Popconfirm')
    expect(source).not.toContain('useBillingQuote')
    expect(source).toContain('.then(onConfirm)')
    expect(source).toContain('runningRef.current')
  })

  it('supports setting and unsetting the main result from every history panel', () => {
    const shared = readWorkspaceFile('components/common/HistoryRecordWrap.tsx')
    expect(shared).toContain('dialog-select-nor.svg')
    expect(shared).toContain('dialog-select-sel.svg')
    expect(shared).toContain('onUnsetMain?.()')

    for (const path of [
      'components/steps/EditSceneImageModal.tsx',
      'components/steps/edit-storyboard-image/EditStoryboardImageModal.tsx',
      'components/steps/edit-storyboard-video/VideoStagePanels.tsx',
      'components/steps/edit-storyboard-dubbing/DubbingHistoryPanel.tsx'
    ]) {
      const source = readWorkspaceFile(path)
      expect(source, path).toContain('isMain=')
      expect(source, path).toContain('onUnsetMain=')
    }
  })

  it('uses an explicit tooltip layer inside high-z-index creation modals', () => {
    const source = readWorkspaceFile('components/common/EllipsisTooltip.tsx')
    expect(source).toContain('zIndex={ELLIPSIS_TOOLTIP_Z_INDEX}')
  })
})
