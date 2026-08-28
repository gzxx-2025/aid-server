import { describe, expect, it } from 'vitest'
import {
  isAcceptedImportAsset,
  isImageAsset,
  isScriptAsset,
  isVisibleImportAsset
} from './importScriptModalState'

describe('import script asset acceptance', () => {
  const image = { type: 'image', url: 'https://cdn.example.com/character.png' }
  const video = { type: 'video', url: 'https://cdn.example.com/shot.mp4' }
  const script = { type: 'script', content: 'scene one' }

  it('shows every asset card in script mode but only accepts text scripts', () => {
    expect(isVisibleImportAsset(image, 'script')).toBe(true)
    expect(isVisibleImportAsset(video, 'script')).toBe(true)
    expect(isVisibleImportAsset(script, 'script')).toBe(true)
    expect(isAcceptedImportAsset(image, 'script')).toBe(false)
    expect(isAcceptedImportAsset(video, 'script')).toBe(false)
    expect(isAcceptedImportAsset(script, 'script')).toBe(true)
  })

  it('continues filtering cards for media-specific import modes', () => {
    expect(isVisibleImportAsset(image, 'image')).toBe(true)
    expect(isVisibleImportAsset(video, 'image')).toBe(false)
    expect(isVisibleImportAsset(video, 'video')).toBe(true)
    expect(isVisibleImportAsset(script, 'video')).toBe(false)
  })

  it('treats all as browsing scope rather than permission to import media', () => {
    expect(isVisibleImportAsset(image, 'all')).toBe(true)
    expect(isVisibleImportAsset(video, 'all')).toBe(true)
    expect(isAcceptedImportAsset(image, 'all')).toBe(false)
    expect(isAcceptedImportAsset(video, 'all')).toBe(false)
    expect(isAcceptedImportAsset(script, 'all')).toBe(true)
  })

  it('requires a usable image URL in image mode', () => {
    expect(isImageAsset({ type: 'image' })).toBe(false)
    expect(isImageAsset(image)).toBe(true)
  })

  it('accepts txt file records but rejects binary-looking file records', () => {
    expect(isScriptAsset({ type: 'file', name: 'screenplay.txt' })).toBe(true)
    expect(isScriptAsset({ type: 'file', name: 'screenplay.pdf' })).toBe(false)
  })

  it('keeps folders navigable for every import mode', () => {
    expect(isAcceptedImportAsset({ type: 'folder' }, 'script')).toBe(true)
    expect(isAcceptedImportAsset({ type: 'folder' }, 'image')).toBe(true)
  })
})
