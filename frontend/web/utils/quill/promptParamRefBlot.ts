import { EmbedBlot } from 'parchment'
import type QuillType from 'quill'
import type { PromptParamRefValue } from '~/utils/storyboardPromptParamRef'
import { readPromptParamRefFromNode } from '~/utils/storyboardPromptParamRef'

let registered = false

function parseValue(value: unknown): PromptParamRefValue | null {
  if (value == null) return null
  if (typeof value === 'object' && !Array.isArray(value)) {
    return value as PromptParamRefValue
  }
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as PromptParamRefValue
    } catch {
      return null
    }
  }
  return null
}

function renderNode(node: HTMLElement, v: PromptParamRefValue) {
  node.innerHTML = ''
  node.className = `scp-prompt-param-ref scp-prompt-param-ref--${v.paramType}`
  node.setAttribute('contenteditable', 'false')
  node.dataset.paramType = v.paramType
  node.dataset.key = v.key
  node.dataset.label = v.label

  const label = document.createElement('span')
  label.className = 'scp-prompt-param-ref__label'
  label.textContent = v.label
  node.appendChild(label)
}

export function registerPromptParamRefBlot(Quill: typeof QuillType): void {
  if (registered) return

  class PromptParamRefBlot extends EmbedBlot {
    static override blotName = 'promptParamRef'
    static override className = 'scp-prompt-param-ref'
    static override tagName = 'SPAN'

    static override create(value: unknown) {
      const node = super.create() as HTMLElement
      const v = parseValue(value)
      if (v) renderNode(node, v)
      return node
    }

    static override value(domNode: HTMLElement): PromptParamRefValue {
      return readPromptParamRefFromNode(domNode)
    }
  }

  Quill.register(PromptParamRefBlot)
  registered = true
}
