/** @vitest-environment jsdom */
import axios, { AxiosError, type AxiosAdapter } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from './api'
import { applyApiCryptoFromPublicConfig } from './apiCrypto'
import { requireLogin } from './authLoginNavigation'
import { useLoginModalStore } from '@/stores/loginModal'
import { useUserStore } from '@/stores/user'

describe('API authentication navigation', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
    localStorage.clear()
    useUserStore.getState().logout()
    useLoginModalStore.getState().closeModal()
    applyApiCryptoFromPublicConfig({ enabled: false, publicKey: '' })
  })

  it('guest home banner requests open login without reloading or navigating to /login', async () => {
    const adapter = vi.fn<AxiosAdapter>()
    const href = window.location.href
    for (let attempt = 0; attempt < 3; attempt++) {
      await expect(api.post('/api/user/home/banner/list', {}, { adapter }))
        .rejects.toSatisfy(axios.isCancel)
    }
    expect(adapter).not.toHaveBeenCalled()
    expect(window.location.href).toBe(href)
    expect(useLoginModalStore.getState().open).toBe(true)
  })

  it('home banner requests preserve the protected page saved for after login', async () => {
    requireLogin({ redirect: '/works?tab=recent' })
    await expect(api.post('/api/user/home/banner/list')).rejects.toSatisfy(axios.isCancel)
    expect(useLoginModalStore.getState().redirect).toBe('/works?tab=recent')
  })

  it.each([500, 502, 503])('SEO proxy HTTP %s does not clear the session or open login', async (status) => {
    useUserStore.getState().setToken('existing-token')
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Proxy failed', 'ERR_BAD_RESPONSE', config, {}, {
        config, status, statusText: 'Proxy failed', headers: {}, data: 'Internal Server Error'
      })
    }
    await expect(api.get('/seo/public/meta', { adapter })).rejects.toThrow('Proxy failed')
    expect(localStorage.getItem('token')).toBe('existing-token')
    expect(useUserStore.getState().token).toBe('existing-token')
    expect(useLoginModalStore.getState().open).toBe(false)
    expect(window.location.pathname).toBe('/')
  })

  it('network failure does not become an authentication failure', async () => {
    useUserStore.getState().setToken('existing-token')
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config, {})
    }
    await expect(api.get('/seo/public/meta', { adapter })).rejects.toThrow('Network Error')
    expect(localStorage.getItem('token')).toBe('existing-token')
    expect(useLoginModalStore.getState().open).toBe(false)
    expect(window.location.pathname).toBe('/')
  })

  it('401 clears stored and in-memory tokens and opens login on the current public page', async () => {
    useUserStore.getState().setToken('expired-token')
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, {}, {
        config, status: 401, statusText: 'Unauthorized', headers: {}, data: {}
      })
    }
    await expect(api.get('/api/user/profile', { adapter })).rejects.toThrow('Unauthorized')
    expect(localStorage.getItem('token')).toBeNull()
    await vi.waitFor(() => expect(useUserStore.getState().token).toBe(''))
    expect(useLoginModalStore.getState().open).toBe(true)
    expect(window.location.pathname).toBe('/')
  })
})
