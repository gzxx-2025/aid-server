/**
 * @vitest-environment jsdom
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/stores/loginModal', () => {
  const state = {
    open: false,
    redirect: null as string | null
  }
  return {
    useLoginModalStore: {
      getState: () => state
    },
    openLoginModal: vi.fn((options?: { redirect?: string | null }) => {
      state.open = true
      if (options && 'redirect' in options) {
        state.redirect = options.redirect ?? null
      }
    }),
    closeLoginModal: vi.fn(() => {
      state.open = false
      state.redirect = null
    })
  }
})

vi.mock('@/stores/user', () => ({
  useUserStore: {
    getState: () => ({
      token: '',
      user: null,
      logout: vi.fn()
    })
  }
}))

import { closeLoginModal, openLoginModal, useLoginModalStore } from '@/stores/loginModal'
import {
  AID_LEAVE_AUTH_ROUTE_EVENT,
  logoutToPublicHome,
  redirectToLogin,
  requireLogin
} from './authLoginNavigation'

describe('authLoginNavigation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const state = useLoginModalStore.getState() as { open: boolean; redirect: string | null }
    state.open = false
    state.redirect = null
    window.history.replaceState(null, '', '/')
  })

  it('requireLogin opens modal with current path as redirect', () => {
    window.history.replaceState(null, '', '/faq')
    requireLogin()
    expect(openLoginModal).toHaveBeenCalledWith({ redirect: '/faq' })
  })

  it('requireLogin leaves auth routes via event instead of /login', () => {
    window.history.replaceState(null, '', '/works')
    const leave = vi.fn()
    window.addEventListener(AID_LEAVE_AUTH_ROUTE_EVENT, leave)
    requireLogin({ redirect: '/works' })
    expect(openLoginModal).toHaveBeenCalledWith({ redirect: '/works' })
    expect(leave).toHaveBeenCalledTimes(1)
    window.removeEventListener(AID_LEAVE_AUTH_ROUTE_EVENT, leave)
  })

  it('requireLogin is a no-op on /login compatibility route', () => {
    window.history.replaceState(null, '', '/login?redirect=%2Fworks')
    requireLogin()
    expect(openLoginModal).not.toHaveBeenCalled()
  })

  it('redirectToLogin never navigates to /login', () => {
    window.history.replaceState(null, '', '/create/story-script')
    const hrefBefore = window.location.href
    redirectToLogin()
    expect(window.location.href).toBe(hrefBefore)
    expect(window.location.pathname).toBe('/create/story-script')
    expect(openLoginModal).toHaveBeenCalled()
  })

  it('logoutToPublicHome closes modal and navigates home', () => {
    const navigate = vi.fn()
    logoutToPublicHome(navigate)
    expect(closeLoginModal).toHaveBeenCalled()
    expect(navigate).toHaveBeenCalledWith('/')
  })
})
