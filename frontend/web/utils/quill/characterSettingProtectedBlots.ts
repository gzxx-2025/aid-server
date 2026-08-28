/**
 * 角色设定弹窗内不可编辑片段：小节标题、基本信息字段名（原子 Embed）。
 */
import { EmbedBlot } from 'parchment'
import type QuillType from 'quill'
let registered = false

export function registerCharacterSettingProtectedBlots(Quill: typeof QuillType): void {
  if (registered) return

  class CharacterSettingKeyBlot extends EmbedBlot {
    static override blotName = 'characterSettingKey'
    static override className = 'scp-char-setting-key'
    static override tagName = 'SPAN'

    static override create(value: unknown) {
      const node = super.create()
      node.textContent = typeof value === 'string' ? value : ''
      return node
    }

    static override value(domNode: HTMLElement): string {
      return domNode.textContent ?? ''
    }
  }

  class CharacterSettingSectionBlot extends EmbedBlot {
    static override blotName = 'characterSettingSection'
    static override className = 'scp-char-setting-section'
    static override tagName = 'SPAN'

    static override create(value: unknown) {
      const node = super.create()
      node.textContent = typeof value === 'string' ? value : ''
      return node
    }

    static override value(domNode: HTMLElement): string {
      return domNode.textContent ?? ''
    }
  }

  Quill.register(CharacterSettingKeyBlot)
  Quill.register(CharacterSettingSectionBlot)
  registered = true
}

/** @deprecated 使用 registerCharacterSettingProtectedBlots */
export function registerCharacterSettingKeyBlot(Quill: typeof QuillType): void {
  registerCharacterSettingProtectedBlots(Quill)
}
