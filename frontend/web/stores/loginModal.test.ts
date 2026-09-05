import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  closeLoginModal,
  enterForgotPasswordFlow,
  openLoginModal,
  useLoginModalStore
} from './loginModal'

describe('loginModal store', () => {
  beforeEach(() => {
    useLoginModalStore.setState({
      open: false,
      tab: 'code',
      inviteCode: '',
      redirect: null
    })
  })

  it('opens with invite code and pending redirect', () => {
    openLoginModal({ inviteCode: 'ABCD1234EXTRA', redirect: '/works', tab: 'password' })
    const state = useLoginModalStore.getState()
    expect(state.open).toBe(true)
    expect(state.tab).toBe('password')
    expect(state.inviteCode).toBe('ABCD1234')
    expect(state.redirect).toBe('/works')
  })

  it('keeps previous invite when reopened without a new code', () => {
    openLoginModal({ inviteCode: 'INVITE01', redirect: '/' })
    closeLoginModal()
    openLoginModal({ tab: 'wechat' })
    const state = useLoginModalStore.getState()
    expect(state.open).toBe(true)
    expect(state.tab).toBe('wechat')
    expect(state.inviteCode).toBe('INVITE01')
    expect(state.redirect).toBe(null)
  })

  it('resets tab and redirect on close', () => {
    openLoginModal({ tab: 'password', redirect: '/works' })
    closeLoginModal()
    const state = useLoginModalStore.getState()
    expect(state.open).toBe(false)
    expect(state.tab).toBe('code')
    expect(state.redirect).toBe(null)
  })

  it('clears redirect when reopen passes redirect null', () => {
    openLoginModal({ redirect: '/works' })
    openLoginModal({ tab: 'password', redirect: null })
    expect(useLoginModalStore.getState().redirect).toBe(null)
    expect(useLoginModalStore.getState().open).toBe(true)
  })

  it('sanitizes forgot-password redirect to home', () => {
    openLoginModal({ redirect: '/forgot-password' })
    expect(useLoginModalStore.getState().redirect).toBe('/')
  })

  it('enterForgotPasswordFlow closes modal and replaces route', () => {
    openLoginModal({ tab: 'password', redirect: '/works' })
    const replace = vi.fn()
    enterForgotPasswordFlow({ replace })
    const state = useLoginModalStore.getState()
    expect(state.open).toBe(false)
    expect(state.redirect).toBe(null)
    expect(replace).toHaveBeenCalledWith('/forgot-password')
  })
})
