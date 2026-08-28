import { create } from 'zustand';

import { checkUpgrade, getUpgradeStatus, UpgradeStatus } from '@/api/aidconfig/upgrade';

interface UpgradeState {
  /** 升级状态快照（左上角版本面板与升级页共享，任一处刷新全局联动） */
  status: UpgradeStatus | null;
  /** 被动加载中（读后端缓存） */
  loading: boolean;
  /** 主动检查更新中（强制回源） */
  checking: boolean;
  /** 加载升级状态；force=true 时强制回源检查更新 */
  loadStatus: (force?: boolean) => Promise<UpgradeStatus | null>;
}

let requestSequence = 0;
let passiveRequest: Promise<UpgradeStatus | null> | null = null;
let forceRequest: Promise<UpgradeStatus | null> | null = null;

export const useUpgradeStore = create<UpgradeState>((set) => ({
  status: null,
  loading: false,
  checking: false,
  loadStatus: (force = false) => {
    // 强制检查期间，页面轮询和侧栏初始化共用其结果，避免旧缓存响应覆盖回源结果。
    if (!force && forceRequest) {
      return forceRequest;
    }
    if (!force && passiveRequest) {
      return passiveRequest;
    }
    if (force) {
      // 旧被动请求继续自行结束，但不再向后续调用者复用。
      passiveRequest = null;
    }

    const requestId = ++requestSequence;
    set(force
      ? { checking: true, loading: false }
      : { loading: true });

    const request = (async () => {
      try {
        const res = force ? await checkUpgrade() : await getUpgradeStatus();
        const status = res.data || null;
        // 只允许最后启动的请求写入，避免旧缓存请求覆盖强制检查结果。
        if (requestId === requestSequence) {
          set({ status });
        }
        return status;
      } finally {
        if (requestId === requestSequence) {
          set({ checking: false, loading: false });
        }
      }
    })();

    if (force) {
      const trackedForceRequest = request.finally(() => {
        if (forceRequest === trackedForceRequest) {
          forceRequest = null;
        }
      });
      forceRequest = trackedForceRequest;
      return trackedForceRequest;
    }

    const trackedPassiveRequest = request.finally(() => {
      if (passiveRequest === trackedPassiveRequest) {
        passiveRequest = null;
      }
    });
    passiveRequest = trackedPassiveRequest;
    return trackedPassiveRequest;
  }
}));
