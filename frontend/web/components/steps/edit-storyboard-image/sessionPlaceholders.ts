import type { ModalImageGenSessionTab } from '~/utils/storyboardImageModalGenSession'
import { STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE } from './types'

export function isModalOverlaySessionTab(tab?: ModalImageGenSessionTab): boolean {
  return tab === 'upscale' || tab === 'multiangle' || tab === 'ninegrid'
}

export function removeLocalGeneratingPlaceholders(images: any[]): any[] {
  return images.filter((img) => !img?._localGeneratingPlaceholder)
}

export function appendLocalGeneratingPlaceholder(next: any[], sid: number): any[] {
  return [
    ...next,
    {
      id: `local-generating-${sid}-${Date.now()}`,
      url: '',
      thumbnail: '',
      title: STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE,
      _generating: true,
      _localGeneratingPlaceholder: true
    }
  ]
}
