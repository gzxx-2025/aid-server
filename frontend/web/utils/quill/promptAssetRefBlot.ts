import { EmbedBlot } from 'parchment'
import type QuillType from 'quill'
import type { PromptAssetRefValue } from '~/utils/storyboardPromptAssetRef'
let registered = false

function parseValue(value: unknown): PromptAssetRefValue | null {
  if (value == null) return null
  if (typeof value === 'object' && !Array.isArray(value)) {
    return value as PromptAssetRefValue
  }
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as PromptAssetRefValue
    } catch {
      return null
    }
  }
  return null
}

function readFromNode(node: HTMLElement): PromptAssetRefValue {
  return {
    assetId: node.dataset.assetId || '',
    assetType: (node.dataset.assetType || 'other') as PromptAssetRefValue['assetType'],
    name: node.dataset.name || '',
    imageIndex: Number(node.dataset.imageIndex) || 0,
    url: node.dataset.url || '',
    label: node.dataset.label || node.dataset.name || ''
  }
}

function renderNode(node: HTMLElement, v: PromptAssetRefValue) {
  node.innerHTML = ''
  node.className = `scp-prompt-asset-ref scp-prompt-asset-ref--${v.assetType}`
  node.setAttribute('contenteditable', 'false')
  node.dataset.assetId = v.assetId
  node.dataset.assetType = v.assetType
  node.dataset.name = v.name
  node.dataset.imageIndex = String(v.imageIndex)
  node.dataset.url = v.url || ''
  node.dataset.label = v.label || `@${v.name}`

  const label = document.createElement('span')
  label.className = 'scp-prompt-asset-ref__label'
  label.textContent = v.label || `@${v.name}`
  node.appendChild(label)
}

export function registerPromptAssetRefBlot(Quill: typeof QuillType): void {
  if (registered) return

  class PromptAssetRefBlot extends EmbedBlot {
    static override blotName = 'promptAssetRef'
    static override className = 'scp-prompt-asset-ref'
    static override tagName = 'SPAN'

    static override create(value: unknown) {
      const node = super.create() as HTMLElement
      const v = parseValue(value)
      if (v) renderNode(node, v)
      return node
    }

    static override value(domNode: HTMLElement): PromptAssetRefValue {
      return readFromNode(domNode)
    }
  }

  Quill.register(PromptAssetRefBlot)
  registered = true
}

export { readFromNode as readPromptAssetRefFromNode }
