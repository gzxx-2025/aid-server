import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { AppState } from '~/types'

interface AppStoreState extends AppState {
  toggleTheme: () => void
  toggleSidebar: () => void
}

export const useAppStore = create<AppStoreState>()(
  persist(
    (set, get) => ({
      theme: 'dark',
      sidebarCollapsed: false,

      toggleTheme() {
        set({ theme: get().theme === 'light' ? 'dark' : 'light' })
      },

      toggleSidebar() {
        set({ sidebarCollapsed: !get().sidebarCollapsed })
      }
    }),
    {
      name: 'app-store',
      partialize: (s) => ({ theme: s.theme, sidebarCollapsed: s.sidebarCollapsed })
    }
  )
)

export const selectIsDarkMode = (s: AppStoreState) => s.theme === 'dark'
