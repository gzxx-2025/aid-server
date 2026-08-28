import { describe, expect, it } from 'vitest'
import type { UserModelListItem } from '~/types/business-api'
import { resolveImageGenParamsFromAgentDefaults } from './modelCapability'

const imageModel = {
  modelCode: 'image-model',
  capability: {
    sizeOptions: ['1k', '2k'],
    aspectRatioOptions: ['16:9', '1:1'],
    defaultSize: '2k',
    defaultAspectRatio: '16:9'
  }
} as UserModelListItem

describe('resolveImageGenParamsFromAgentDefaults', () => {
  it('keeps GET agent defaults even when they differ from model defaults/options', () => {
    expect(
      resolveImageGenParamsFromAgentDefaults(
        { resolution: '1024x1024', aspectRatio: '1:1' },
        imageModel
      )
    ).toEqual({ resolution: '1024x1024', aspectRatio: '1:1' })
  })

  it('falls back per missing field without replacing the other agent default', () => {
    expect(
      resolveImageGenParamsFromAgentDefaults(
        { resolution: '', aspectRatio: '1:1' },
        imageModel
      )
    ).toEqual({ resolution: '2k', aspectRatio: '1:1' })
  })
})
