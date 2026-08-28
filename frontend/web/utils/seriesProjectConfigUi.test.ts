/// <reference types="vite/client" />

import { describe,expect,it } from 'vitest'
import shellSource from '../components/create/CreateFlowShell.tsx?raw'
import formSource from '../components/steps/CreateFirstStepFormBody.tsx?raw'
import modalStateSource from '../hooks/useCreateFlowGlobalSettingModal.ts?raw'
// 源码守护：原断言目标 CreateFlowShell.vue / CreateFirstStepFormBody.vue /
// composables/useCreateFlowGlobalSettingModal.ts，断言按迁移后的 React 写法等价改写
describe('series project config locked view', () => {
  it('opens the project config instead of returning when storyboards exist', () => {
    expect(shellSource).toContain("access.mode === 'content-locked'")
    expect(shellSource).toContain('if (!hydrated || activeProjectIdRef.current !== pid) return')
    expect(shellSource).toContain('globalSetting.openGlobalSettingModal()')
    expect(shellSource).not.toContain('showSeriesProjectConfigBlockedTip')
  })

  it('scopes the content lock to the guarded modal session', () => {
    // 原 watch(showGlobalSettingModal) 关闭后解除内容锁 → React prev 比较 effect
    expect(shellSource).toContain(
      'if (prev && !showGlobalSettingModal) setSeriesProjectConfigContentLocked(false)'
    )
    expect(shellSource).toContain(
      'save: () => handleGlobalSettingConfirm({ navigateAfterSave: false })'
    )
    expect(shellSource).toContain('contentConfigLocked: seriesProjectConfigContentLocked')
  })

  it('locks content-affecting controls while keeping the form visible', () => {
    expect(formSource).toContain('{contentConfigLocked && (')
    expect(formSource).toContain('disabled={contentConfigLocked}')
    expect(formSource).toContain('forceStyleLocked={contentConfigLocked}')
  })

  it('does not submit locked content configuration fields', () => {
    expect(modalStateSource).toContain('if (!contentConfigLocked)')
    expect(modalStateSource).toContain('aspectRatio: finalGlobalSetting.aspectRatio')
    const payloadStart = modalStateSource.indexOf('const updatePayload')
    const editableBranchStart = modalStateSource.indexOf('if (!contentConfigLocked)', payloadStart)
    expect(payloadStart).toBeGreaterThan(-1)
    expect(editableBranchStart).toBeGreaterThan(payloadStart)
    const lockedPayload = modalStateSource.slice(payloadStart, editableBranchStart)
    expect(lockedPayload).toContain('projectName: finalTitle')
    expect(lockedPayload).not.toContain('projectDesc')
    expect(lockedPayload).not.toContain('aspectRatio')
    expect(modalStateSource).toContain('...useCreationStore.getState().formData.globalSetting')
    expect(modalStateSource).toContain(
      'if (useCreationStore.getState().currentProjectId !== pid) return'
    )
  })
})
