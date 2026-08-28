import { create } from 'zustand';

export interface DictOption {
  value: string;
  label: string;
  elTagType?: string;
  elTagClass?: string;
  raw?: any;
}

export interface DictEntry {
  key: string;
  value: DictOption[];
}

interface DictState {
  dict: DictEntry[];
  setDict: (entry: DictEntry) => void;
  getDict: (key: string) => DictOption[] | null;
  removeDict: (key: string) => void;
  clean: () => void;
}

export const useDictStore = create<DictState>((set, get) => ({
  dict: [],
  setDict: ({ key, value }) => {
    if (!key) return;
    const exist = get().dict.find((d) => d.key === key);
    if (exist) {
      set((s) => ({
        dict: s.dict.map((d) => (d.key === key ? { key, value } : d))
      }));
    } else {
      set((s) => ({ dict: [...s.dict, { key, value }] }));
    }
  },
  getDict: (key) => {
    const hit = get().dict.find((d) => d.key === key);
    return hit ? hit.value : null;
  },
  removeDict: (key) => set((s) => ({ dict: s.dict.filter((d) => d.key !== key) })),
  clean: () => set({ dict: [] })
}));
