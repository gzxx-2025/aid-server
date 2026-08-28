import { create } from 'zustand';

import { login as apiLogin, logout as apiLogout, getInfo } from '@/api/login';
import { getToken, setToken, removeToken } from '@/utils/auth';
import { isHttp, isEmpty } from '@/utils/validate';
import { registerLogoutHandler } from '@/utils/request';
import defAva from '@/assets/images/profile.png';

interface UserState {
  token?: string;
  id: number | string;
  name: string;
  nickName: string;
  avatar: string;
  roles: string[];
  permissions: string[];
  login: (params: { username: string; password: string; code?: string; uuid?: string; entryCode?: string }) => Promise<void>;
  fetchInfo: () => Promise<any>;
  logout: () => Promise<void>;
  fedLogout: () => Promise<void>;
  reset: () => void;
  setAvatar: (avatar: string) => void;
}

const initial = {
  token: getToken(),
  id: '',
  name: '',
  nickName: '',
  avatar: '',
  roles: [] as string[],
  permissions: [] as string[]
};

export const useUserStore = create<UserState>((set, get) => ({
  ...initial,
  login: async ({ username, password, code, uuid, entryCode }) => {
    const res: any = await apiLogin({ username: username.trim(), password, code, uuid, entryCode });
    setToken(res.token);
    set({ token: res.token });
  },
  fetchInfo: async () => {
    const res: any = await getInfo();
    const user = res.user || {};
    let avatar = user.avatar || '';
    if (!isHttp(avatar)) {
      avatar = isEmpty(avatar) ? defAva : import.meta.env.VITE_APP_BASE_API + avatar;
    }
    if (res.roles && res.roles.length > 0) {
      set({ roles: res.roles, permissions: res.permissions || [] });
    } else {
      set({ roles: ['ROLE_DEFAULT'], permissions: [] });
    }
    set({
      id: user.userId,
      name: user.userName,
      nickName: user.nickName,
      avatar
    });
    return res;
  },
  logout: async () => {
    try {
      await apiLogout();
    } catch (e) {
      // ignore
    }
    removeToken();
    set({ ...initial, token: undefined });
  },
  fedLogout: async () => {
    removeToken();
    set({ ...initial, token: undefined });
  },
  reset: () => set({ ...initial, token: undefined }),
  setAvatar: (avatar: string) => set({ avatar })
}));

// 让 request 拦截器在 401 时能触发前端登出
registerLogoutHandler(async () => {
  await useUserStore.getState().fedLogout();
});
