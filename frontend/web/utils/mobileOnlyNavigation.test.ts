import { describe, expect, it } from 'vitest'
import {
  MOBILE_ONLY_PATH,
  isMobileUserAgent,
  shouldForceMobileOnlyPath,
  shouldLeaveMobileOnlyPath
} from './mobileOnlyNavigation'

describe('mobileOnlyNavigation', () => {
  it('detects common mobile user agents', () => {
    expect(isMobileUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)')).toBe(true)
    expect(isMobileUserAgent('Mozilla/5.0 (Linux; Android 14; Pixel 8)')).toBe(true)
    expect(isMobileUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0')).toBe(false)
  })

  it('forces every path except /mobile onto the mobile-only page', () => {
    expect(shouldForceMobileOnlyPath('/')).toBe(true)
    expect(shouldForceMobileOnlyPath('/case/12')).toBe(true)
    expect(shouldForceMobileOnlyPath('/works')).toBe(true)
    expect(shouldForceMobileOnlyPath(MOBILE_ONLY_PATH)).toBe(false)
  })

  it('sends desktop visitors away from /mobile', () => {
    expect(shouldLeaveMobileOnlyPath(MOBILE_ONLY_PATH, false)).toBe(true)
    expect(shouldLeaveMobileOnlyPath(MOBILE_ONLY_PATH, true)).toBe(false)
    expect(shouldLeaveMobileOnlyPath('/', false)).toBe(false)
  })
})
