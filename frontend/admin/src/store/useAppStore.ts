import { create } from 'zustand';
import Cookies from 'js-cookie';

type Device = 'desktop' | 'mobile';

interface AppState {
  sidebarOpened: boolean;
  sidebarHide: boolean;
  withoutAnimation: boolean;
  device: Device;
  size: 'large' | 'middle' | 'small';
  toggleSidebar: () => void;
  closeSidebar: (withoutAnimation?: boolean) => void;
  setDevice: (device: Device) => void;
  setSidebarHide: (hide: boolean) => void;
  setSize: (s: AppState['size']) => void;
}

const initialSidebar = Cookies.get('sidebarStatus')
  ? !!Number(Cookies.get('sidebarStatus'))
  : true;

export const useAppStore = create<AppState>((set) => ({
  sidebarOpened: initialSidebar,
  sidebarHide: false,
  withoutAnimation: false,
  device: 'desktop',
  size: (Cookies.get('size') as any) || 'middle',
  toggleSidebar: () =>
    set((s) => {
      if (s.sidebarHide) return s;
      const next = !s.sidebarOpened;
      Cookies.set('sidebarStatus', next ? '1' : '0');
      return { sidebarOpened: next, withoutAnimation: false };
    }),
  closeSidebar: (withoutAnimation = false) => {
    Cookies.set('sidebarStatus', '0');
    set({ sidebarOpened: false, withoutAnimation });
  },
  setDevice: (device) => set({ device }),
  setSidebarHide: (hide) => set({ sidebarHide: hide }),
  setSize: (s) => {
    Cookies.set('size', s);
    set({ size: s });
  }
}));
