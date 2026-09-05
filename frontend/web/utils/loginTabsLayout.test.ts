import { describe, expect, it } from 'vitest'
import { LOGIN_MODAL_ALT_DIVIDER_CLASS, LOGIN_MODAL_ALT_ROW_CLASS } from './loginModalLayout'

describe('login method tabs layout', () => {
  it('keeps available alternative login methods in a centered horizontal row', () => {
    expect(LOGIN_MODAL_ALT_ROW_CLASS).toContain('flex')
    expect(LOGIN_MODAL_ALT_ROW_CLASS).toContain('items-stretch')
    expect(LOGIN_MODAL_ALT_ROW_CLASS).toContain('justify-center')
    expect(LOGIN_MODAL_ALT_DIVIDER_CLASS).toContain('self-stretch')
  })
})
