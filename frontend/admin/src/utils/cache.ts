const sessionCache = {
  set(key: string, value: any) {
    if (!sessionStorage) return;
    if (key != null && value != null) {
      sessionStorage.setItem(key, value);
    }
  },
  get(key: string): string | null {
    if (!sessionStorage) return null;
    return sessionStorage.getItem(key);
  },
  setJSON(key: string, value: any) {
    if (value != null) sessionStorage.setItem(key, JSON.stringify(value));
  },
  getJSON(key: string) {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  },
  remove(key: string) {
    sessionStorage.removeItem(key);
  }
};

const localCache = {
  set(key: string, value: any) {
    if (key != null && value != null) localStorage.setItem(key, value);
  },
  get(key: string) {
    return localStorage.getItem(key);
  },
  setJSON(key: string, value: any) {
    if (value != null) localStorage.setItem(key, JSON.stringify(value));
  },
  getJSON(key: string) {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  },
  remove(key: string) {
    localStorage.removeItem(key);
  }
};

export default {
  session: sessionCache,
  local: localCache
};
