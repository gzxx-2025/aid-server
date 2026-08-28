/** @vitest-environment node */

import { afterEach, describe, expect, it } from 'vitest'
import { resolveStackedModalZIndex } from './stackedModalZIndex'

const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')

afterEach(() => {
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else Reflect.deleteProperty(globalThis, 'window')
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

function mockModalWrapStyles(styles: Array<Partial<CSSStyleDeclaration>>) {
  const elements = styles.map(() => ({}) as Element)
  const styleByElement = new Map(elements.map((element, index) => [element, styles[index]]))
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { querySelectorAll: () => elements }
  })
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { getComputedStyle: (element: Element) => styleByElement.get(element) }
  })
}

describe('resolveStackedModalZIndex', () => {
  it('returns one stack step above the highest visible modal wrap', () => {
    mockModalWrapStyles([
      { zIndex: '1100', display: 'block', visibility: 'visible' },
      { zIndex: '1450', display: 'block', visibility: 'visible' },
      { zIndex: '9000', display: 'none', visibility: 'visible' },
      { zIndex: 'auto', display: 'block', visibility: 'visible' }
    ])

    expect(resolveStackedModalZIndex()).toBe(1550)
  })

  it('uses the configured base when no modal is mounted', () => {
    mockModalWrapStyles([])
    expect(resolveStackedModalZIndex(2000, 50)).toBe(2050)
  })
})
