/// <reference types="vite/client" />

import { describe,expect,it } from 'vitest'
import agentPickerModalSource from '../components/steps/AgentPickerModal.tsx?raw'
import modelSelectDropdownSource from '../components/steps/ModelSelectDropdown.tsx?raw'
import projectGenConfigModalSource from '../components/steps/ProjectGenConfigModal.tsx?raw'
import upscaleModelPopoverSource from '../components/steps/UpscaleModelPopover.tsx?raw'
import type { UserModelListItem } from '../types/business-api'
import { shouldShowModelFreeBadge } from './modelFreeStatus'
import {
mapUserModelListItemToModelOption,
mapUserModelListItemToPickerOption
} from './userModelOption'
function model(overrides: Partial<UserModelListItem>): UserModelListItem {
  return {
    id: 1,
    modelCode: 'test-model',
    modelName: '测试模型',
    modelType: 'image',
    ...overrides
  }
}

describe('model free badge visibility', () => {
  it('shows only for models explicitly marked free', () => {
    expect(shouldShowModelFreeBadge({ isFree: true })).toBe(true)
    expect(shouldShowModelFreeBadge({ isFree: false })).toBe(false)
    expect(shouldShowModelFreeBadge({})).toBe(false)
    expect(shouldShowModelFreeBadge({ isFree: false, costCredits: 0 } as UserModelListItem))
      .toBe(false)
  })

  it('preserves explicit free status in shared dropdown and picker mappers', () => {
    expect(mapUserModelListItemToModelOption(model({ isFree: true })).isFree).toBe(true)
    expect(mapUserModelListItemToPickerOption(model({ isFree: true })).isFree).toBe(true)
    expect(mapUserModelListItemToModelOption(model({ isFree: false, costCredits: 0 })).isFree)
      .toBe(false)
  })

  it('uses the shared badge across all model selection surfaces', () => {
    const files = [
      ['ModelSelectDropdown.tsx', modelSelectDropdownSource],
      ['AgentPickerModal.tsx', agentPickerModalSource],
      ['UpscaleModelPopover.tsx', upscaleModelPopoverSource],
      ['ProjectGenConfigModal.tsx', projectGenConfigModalSource]
    ]
    for (const [file, source] of files) {
      expect(source, file).toContain('ModelFreeBadge')
      expect(source, file).toContain('shouldShowModelFreeBadge')
    }
  })
})
