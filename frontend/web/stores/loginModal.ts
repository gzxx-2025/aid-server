import { create } from 'zustand'
import { sanitizeLoginRedirect } from '~/utils/authRequiredPath'

export type LoginModalTab = 'code' | 'wechat' | 'password'

export interface OpenLoginModalOptions {
  tab?: LoginModalTab
  inviteCode?: string
  /** 传 null 显式清空回跳；不传则保留上次（邀请码续开等） */
  redirect?: string | null
}

interface LoginModalState {
  open: boolean
  tab: LoginModalTab
  inviteCode: string
  redirect: string | null
  openModal: (options?: OpenLoginModalOptions) => void
  closeModal: () => void
  setTab: (tab: LoginModalTab) => void
  setInviteCode: (inviteCode: string) => void
}

function resolveRedirect(
  options: OpenLoginModalOptions | undefined,
  prev: string | null
): string | null {
  if (!options || options.redirect === undefined) return prev
  if (options.redirect == null) return null
  const next = sanitizeLoginRedirect(options.redirect)
  return next === '/' ? '/' : next
}

export const useLoginModalStore = create<LoginModalState>((set) => ({
  open: false,
  tab: 'code',
  inviteCode: '',
  redirect: null,

  openModal(options) {
    set((state) => ({
      open: true,
      tab: options?.tab ?? state.tab ?? 'code',
      inviteCode:
        options?.inviteCode != null && String(options.inviteCode).trim()
          ? String(options.inviteCode).trim().slice(0, 8)
          : state.inviteCode,
      redirect: resolveRedirect(options, state.redirect)
    }))
  },

  closeModal() {
    set({ open: false, tab: 'code', redirect: null })
  },

  setTab(tab) {
    set({ tab })
  },

  setInviteCode(inviteCode) {
    set({ inviteCode: String(inviteCode || '').trim().slice(0, 8) })
  }
}))

export function openLoginModal(options?: OpenLoginModalOptions) {
  useLoginModalStore.getState().openModal(options)
}

export function closeLoginModal() {
  useLoginModalStore.getState().closeModal()
}

/** 关登录弹窗并进入找回密码独立页（replace，避免历史栈残留「登录中」状态） */
export function enterForgotPasswordFlow(router: { replace: (href: string) => void }) {
  closeLoginModal()
  router.replace('/forgot-password')
}
