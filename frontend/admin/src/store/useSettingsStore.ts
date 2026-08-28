import { create } from 'zustand';

export interface SettingsState {
  title: string;
  theme: string;
  sideTheme: 'theme-dark' | 'theme-light';
  showSettings: boolean;
  navType: 1 | 2 | 3;
  tagsView: boolean;
  tagsIcon: boolean;
  fixedHeader: boolean;
  sidebarLogo: boolean;
  dynamicTitle: boolean;
  footerVisible: boolean;
  footerContent: string;
  setTitle: (title: string) => void;
  change: (key: keyof SettingsState, value: any) => void;
}

const stored = (() => {
  try {
    return JSON.parse(localStorage.getItem('layout-setting') || '{}') || {};
  } catch {
    return {};
  }
})();

const defaults = {
  title: '',
  theme: '#2563EB',
  sideTheme: 'theme-light' as const,
  showSettings: true,
  navType: 1 as const,
  tagsView: true,
  tagsIcon: false,
  fixedHeader: true,
  sidebarLogo: true,
  dynamicTitle: false,
  footerVisible: false,
  footerContent: 'Copyright © 2018-2026. All Rights Reserved.'
};

export const useSettingsStore = create<SettingsState>((set) => ({
  ...defaults,
  ...stored,
  setTitle: (title) => set({ title }),
  change: (key, value) =>
    set((state) => {
      const next = { ...state, [key]: value } as SettingsState;
      const persisted: Record<string, any> = { ...stored, [key]: value };
      localStorage.setItem('layout-setting', JSON.stringify(persisted));
      return next;
    })
}));
