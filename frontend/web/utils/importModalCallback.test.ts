import { describe, expect, it, vi } from 'vitest'
import { invokeImportModalCallback } from './importModalCallback'

describe('invokeImportModalCallback', () => {
  it('accepts synchronous and asynchronous void callbacks', async () => {
    expect(await invokeImportModalCallback(() => undefined, 'text')).toEqual({ accepted: true })
    expect(await invokeImportModalCallback(async () => undefined, 'text')).toEqual({ accepted: true })
  })

  it('keeps the modal open when the importer rejects the payload', async () => {
    expect(await invokeImportModalCallback(() => false, 'text')).toEqual({ accepted: false })
  })

  it('returns callback errors without throwing through the click handler', async () => {
    const error = new Error('save failed')
    const callback = vi.fn(async () => {
      throw error
    })

    expect(await invokeImportModalCallback(callback, 'text')).toEqual({
      accepted: false,
      error
    })
  })
})
