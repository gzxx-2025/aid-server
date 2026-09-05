import { describe, expect, it } from 'vitest'
import {
  FORGOT_PASSWORD_CARD_CLASS,
  FORGOT_PASSWORD_FORM_CLASS,
  FORGOT_PASSWORD_PAGE_CLASS,
  FORGOT_PASSWORD_SUBMIT_CLASS
} from './forgotPasswordLayout'

describe('forgot password layout tokens', () => {
  it('centers the page stack in the home main route', () => {
    expect(FORGOT_PASSWORD_PAGE_CLASS).toContain('h-full')
    expect(FORGOT_PASSWORD_PAGE_CLASS).toContain('min-h-full')
    expect(FORGOT_PASSWORD_PAGE_CLASS).toContain('flex-1')
    expect(FORGOT_PASSWORD_PAGE_CLASS).toContain('items-center')
    expect(FORGOT_PASSWORD_PAGE_CLASS).toContain('justify-center')
  })

  it('uses 8px radius on card and submit, and 80px field-to-button gap', () => {
    expect(FORGOT_PASSWORD_CARD_CLASS).toContain('rounded-lg')
    expect(FORGOT_PASSWORD_CARD_CLASS).toContain('items-center')
    expect(FORGOT_PASSWORD_CARD_CLASS).toContain('justify-center')
    expect(FORGOT_PASSWORD_FORM_CLASS).toContain('gap-20')
    expect(FORGOT_PASSWORD_SUBMIT_CLASS).toContain('auth-dark-submit')
    expect(FORGOT_PASSWORD_SUBMIT_CLASS).toContain('auth-dark-submit--grad')
    expect(FORGOT_PASSWORD_SUBMIT_CLASS).not.toContain('mt-')
  })
})
