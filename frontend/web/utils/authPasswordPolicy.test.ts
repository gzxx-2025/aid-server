import { describe, expect, it } from 'vitest'
import {
  AUTH_PASSWORD_RULE_HINT,
  hasAuthPasswordCharset,
  validateAuthPasswordChange,
  validateAuthPasswordValue
} from './authPasswordPolicy'

describe('authPasswordPolicy', () => {
  it('requires upper, lower and digit', () => {
    expect(hasAuthPasswordCharset('Abc12')).toBe(true)
    expect(hasAuthPasswordCharset('abc12')).toBe(false)
    expect(hasAuthPasswordCharset('ABC12')).toBe(false)
    expect(hasAuthPasswordCharset('Abcde')).toBe(false)
  })

  it('validates length and charset on the new password', () => {
    expect(validateAuthPasswordValue('')).toBe('empty')
    expect(validateAuthPasswordValue('Ab1')).toBe('too_short')
    expect(validateAuthPasswordValue(`${'Ab1'.padEnd(21, 'x')}`)).toBe('too_long')
    expect(validateAuthPasswordValue('abcdef')).toBe('weak_charset')
    expect(validateAuthPasswordValue('Abc12')).toBe(null)
  })

  it('validates confirm match and optional old password', () => {
    expect(
      validateAuthPasswordChange({ newPassword: 'Abc12', confirmPassword: 'Abc13' })
    ).toBe('mismatch')
    expect(
      validateAuthPasswordChange({
        newPassword: 'Abc12',
        confirmPassword: 'Abc12',
        oldPassword: 'Abc12'
      })
    ).toBe('same_as_old')
    expect(
      validateAuthPasswordChange({
        newPassword: 'Abc12',
        confirmPassword: 'Abc12',
        oldPassword: 'Old12'
      })
    ).toBe(null)
    expect(AUTH_PASSWORD_RULE_HINT).toContain('大写字母')
  })
})
