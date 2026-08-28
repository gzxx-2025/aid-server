import { describe, expect, it } from 'vitest'
import {
  extractPromptAudioRefIdentityKeysFromHtml,
  findAudioIndexesLostFromPrompt,
  findStripIndexesLostFromPrompt
} from './storyboardPromptAssetStripSync'

describe('prompt reference to imported strip sync', () => {
  it('removes the last referenced image when prompt text still remains', () => {
    expect(
      findStripIndexesLostFromPrompt({
        images: [{ id: 7, name: '角色甲', url: '/role.png' }],
        prevKeys: new Set(['id:7', 'name:角色甲']),
        nextKeys: new Set(),
        promptIsEmpty: false
      })
    ).toEqual([0])
  })

  it('keeps imported images for the explicit whole-prompt clear behavior', () => {
    expect(
      findStripIndexesLostFromPrompt({
        images: [{ id: 7, name: '角色甲', url: '/role.png' }],
        prevKeys: new Set(['id:7', 'name:角色甲']),
        nextKeys: new Set(),
        promptIsEmpty: true
      })
    ).toEqual([])
  })

  it('detects a removed audio ref by normalized name', () => {
    const previous = extractPromptAudioRefIdentityKeysFromHtml(
      '镜头推进 @音频1[音频-小天]'
    )
    expect(
      findAudioIndexesLostFromPrompt({
        audios: [{ referenceAudioId: 12, name: '小天', url: '/voice.mp3' }],
        prevKeys: previous,
        nextKeys: new Set(),
        promptIsEmpty: false
      })
    ).toEqual([0])
  })

  it('does not remove an audio that remains referenced', () => {
    const keys = extractPromptAudioRefIdentityKeysFromHtml('对白 @音频1[音频-小天]')
    expect(
      findAudioIndexesLostFromPrompt({
        audios: [{ referenceAudioId: 12, name: '音频-小天', url: '/voice.mp3' }],
        prevKeys: keys,
        nextKeys: keys,
        promptIsEmpty: false
      })
    ).toEqual([])
  })
})
