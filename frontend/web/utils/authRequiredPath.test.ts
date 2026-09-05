import { describe, expect, it } from 'vitest'
import {
  isAuthRequiredPath,
  isForgotPasswordPath,
  readInviteCodeFromSearch,
  sanitizeLoginRedirect
} from './authRequiredPath'

describe('auth required path', () => {
  it('protects works / assets / create / invite but keeps homepage public', () => {
    expect(isAuthRequiredPath('/')).toBe(false)
    expect(isAuthRequiredPath('/about')).toBe(false)
    expect(isAuthRequiredPath('/forgot-password')).toBe(false)
    expect(isAuthRequiredPath('/works')).toBe(true)
    expect(isAuthRequiredPath('/create/story-script')).toBe(true)
    expect(isAuthRequiredPath('/invite')).toBe(true)
  })

  it('recognizes forgot-password as a public auth flow path', () => {
    expect(isForgotPasswordPath('/forgot-password')).toBe(true)
    expect(isForgotPasswordPath('/forgot-password/')).toBe(true)
    expect(isForgotPasswordPath('/')).toBe(false)
  })

  it('rejects open redirects, login self-loops, and forgot-password bounce targets', () => {
    expect(sanitizeLoginRedirect('/works')).toBe('/works')
    expect(sanitizeLoginRedirect('https://evil.example')).toBe('/')
    expect(sanitizeLoginRedirect('//evil.example')).toBe('/')
    expect(sanitizeLoginRedirect('/login?redirect=/works')).toBe('/')
    expect(sanitizeLoginRedirect('/forgot-password')).toBe('/')
  })

  it('reads invite codes from either query key', () => {
    expect(readInviteCodeFromSearch(new URLSearchParams('invite=HELLO123'))).toBe('HELLO123')
    expect(readInviteCodeFromSearch(new URLSearchParams('inviteCode=TOOLONGXX'))).toBe('TOOLONGX')
  })
})
