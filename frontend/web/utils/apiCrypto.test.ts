import { describe, expect, it, vi } from 'vitest'

async function loadApiCrypto() {
  vi.resetModules()
  return import('./apiCrypto')
}

describe('api crypto bootstrap', () => {
  it('coalesces the first protected requests until public config is ready', async () => {
    const apiCrypto = await loadApiCrypto()
    let releaseConfig!: () => void
    const configGate = new Promise<void>((resolve) => {
      releaseConfig = resolve
    })
    const refreshHandler = vi.fn(async () => {
      await configGate
      apiCrypto.applyApiCryptoFromPublicConfig(
        { enabled: true, publicKey: 'test-public-key' },
        1_000,
        1_000
      )
    })
    apiCrypto.registerApiCryptoConfigRefreshHandler(refreshHandler)

    const firstRequest = apiCrypto.ensureApiCryptoConfigReady()
    const secondRequest = apiCrypto.ensureApiCryptoConfigReady()

    expect(refreshHandler).toHaveBeenCalledTimes(1)
    releaseConfig()
    await expect(Promise.all([firstRequest, secondRequest])).resolves.toEqual([true, true])
    expect(apiCrypto.shouldEncryptApiPath('/api/user/home/banner/list')).toBe(true)
  })

  it('does not refresh again after a disabled public config is resolved', async () => {
    const apiCrypto = await loadApiCrypto()
    const refreshHandler = vi.fn(async () => {})
    apiCrypto.registerApiCryptoConfigRefreshHandler(refreshHandler)
    apiCrypto.applyApiCryptoFromPublicConfig({ enabled: false, publicKey: '' })

    await expect(apiCrypto.ensureApiCryptoConfigReady()).resolves.toBe(true)
    expect(refreshHandler).not.toHaveBeenCalled()
    expect(apiCrypto.shouldEncryptApiPath('/api/user/home/banner/list')).toBe(false)
  })
})
