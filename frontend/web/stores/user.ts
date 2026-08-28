import { create } from 'zustand'
import type { User } from '~/types'
import type { UserBalanceFromApi } from '~/types/business-api'
import { clearAuthLoginChannel } from '~/utils/authLoginChannel'
import { userBalance,userProfile } from '~/utils/businessApi'
import { mapUserInfoFromApiToUser } from '~/utils/userProfile'
const isClient = () => typeof window !== 'undefined'

interface UserStoreState {
  user: User | null
  token: string
  profileLoading: boolean
  hydrateFromStorage: () => void
  setUser: (user: User) => void
  setToken: (token: string) => void
  login: (user: User, token: string) => void
  logout: () => void
  /** 拉取最新个人信息（余额等实时字段以接口为准） */
  fetchProfile: () => Promise<User | null>
  /** 快捷刷新账户积分（POST /api/user/balance），仅 patch 余额相关字段 */
  fetchBalance: () => Promise<UserBalanceFromApi | null>
}

export const useUserStore = create<UserStoreState>((set, get) => ({
  user: null,
  token: '',
  profileLoading: false,

  hydrateFromStorage() {
    if (!isClient()) return
    const token = localStorage.getItem('token') || ''
    const rawUser = localStorage.getItem('user-info')
    let user: User | null = null
    if (rawUser) {
      try {
        user = JSON.parse(rawUser) as User
      } catch {
        user = null
      }
    }
    set({ token, user })
  },

  setUser(user) {
    set({ user })
    if (isClient()) {
      localStorage.setItem('user-info', JSON.stringify(user))
    }
  },

  setToken(token) {
    set({ token })
    if (isClient()) {
      localStorage.setItem('token', token)
    }
  },

  login(user, token) {
    get().setUser(user)
    get().setToken(token)
  },

  logout() {
    set({ user: null, token: '', profileLoading: false })
    clearAuthLoginChannel()
    if (isClient()) {
      localStorage.removeItem('token')
      localStorage.removeItem('user-info')
    }
  },

  async fetchProfile() {
    if (!get().token) return null
    set({ profileLoading: true })
    try {
      const info = await userProfile()
      const mapped = mapUserInfoFromApiToUser(info, {
        fallbackEmail: get().user?.email,
        role: get().user?.role
      })
      get().setUser(mapped)
      return mapped
    } catch {
      return null
    } finally {
      set({ profileLoading: false })
    }
  },

  async fetchBalance() {
    if (!get().token) return null
    try {
      const data = await userBalance()
      const user = get().user
      if (user) {
        get().setUser({
          ...user,
          balance: data.balance,
          frozenBalance: data.frozenBalance,
          totalRecharge: data.totalRecharge,
          totalConsumption: data.totalConsumption
        })
      }
      return data
    } catch {
      return null
    }
  }
}))

/** 与原 Pinia getters 对齐的派生读取 */
export const selectIsLoggedIn = (s: UserStoreState) => !!s.token
export const selectUserName = (s: UserStoreState) => s.user?.username || '未登录'
export const selectUserRole = (s: UserStoreState) => s.user?.role || 'user'
