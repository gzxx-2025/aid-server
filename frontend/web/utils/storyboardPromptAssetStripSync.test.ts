import { describe, expect, it } from 'vitest'
import {
  extractPromptAudioRefIdentityKeysFromHtml,
  findAudioIndexesLostFromPrompt,
  findStripIndexesLostFromPrompt,
  pruneResolvedPromptAssetsForRemovedImage
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

  it('removes linked imported images when the whole prompt is cleared', () => {
    expect(
      findStripIndexesLostFromPrompt({
        images: [{ id: 7, name: '角色甲', url: '/role.png' }],
        prevKeys: new Set(['id:7', 'name:角色甲']),
        nextKeys: new Set(),
        promptIsEmpty: true
      })
    ).toEqual([0])
  })

  it('distinguishes duplicate image names by stable id', () => {
    expect(
      findStripIndexesLostFromPrompt({
        images: [
          { id: 7, name: '同名参考图', url: '/first.png' },
          { id: 8, name: '同名参考图', url: '/second.png' }
        ],
        prevKeys: new Set(['id:7', 'id:8', 'name:同名参考图']),
        nextKeys: new Set(['id:8', 'name:同名参考图'])
      })
    ).toEqual([0])
  })

  it('prunes only the removed stable id when resolved assets have duplicate names', () => {
    const assets = [
      { assetId: '7', assetType: 'other' as const, name: '同名参考图', imageIndex: 1, label: '@同名参考图' },
      { assetId: '8', assetType: 'other' as const, name: '同名参考图', imageIndex: 2, label: '@同名参考图' }
    ]
    expect(
      pruneResolvedPromptAssetsForRemovedImage(assets, { id: 7, name: '同名参考图' })
    ).toEqual([assets[1]])
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

  it('removes linked audio when the whole prompt is cleared', () => {
    expect(
      findAudioIndexesLostFromPrompt({
        audios: [{ referenceAudioId: 12, name: '音频-旁白', url: '/voice.mp3' }],
        prevKeys: new Set(['audio-id:12', 'audio-name:音频-旁白']),
        nextKeys: new Set(),
        promptIsEmpty: true
      })
    ).toEqual([0])
  })
})
