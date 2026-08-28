import { describe,expect,it } from 'vitest'
import { advanceGenerationToken,invalidateGenerationToken,replaceRefValue } from './generationToken'
describe('generationToken', () => {
  it('advances and returns the current generation', () => {
    const token = { current: 3 }

    expect(advanceGenerationToken(token)).toBe(4)
    expect(token.current).toBe(4)
  })

  it('invalidates an existing generation', () => {
    const token = { current: 7 }

    invalidateGenerationToken(token)

    expect(token.current).toBe(8)
  })

  it('replaces a mutable ref value', () => {
    const ref = { current: ['old'] }

    replaceRefValue(ref, ['new'])

    expect(ref.current).toEqual(['new'])
  })
})
