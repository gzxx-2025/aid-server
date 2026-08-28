import { create } from 'zustand';

export interface TagView {
  path: string;
  fullPath: string;
  name?: string;
  title: string;
  affix?: boolean;
  query?: Record<string, any>;
  meta?: Record<string, any>;
}

interface TagsViewState {
  visitedViews: TagView[];
  cachedViews: string[];
  addView: (view: TagView) => void;
  delView: (view: TagView) => Promise<void>;
  delOthersViews: (view: TagView) => Promise<void>;
  delAllViews: () => Promise<void>;
  delLeftViews: (view: TagView) => Promise<void>;
  delRightViews: (view: TagView) => Promise<void>;
  updateVisitedView: (view: TagView) => void;
  addAffixTags: (tags: TagView[]) => void;
}

export const useTagsViewStore = create<TagsViewState>((set, get) => ({
  visitedViews: [],
  cachedViews: [],
  addView: (view) => {
    const { visitedViews, cachedViews } = get();
    const exists = visitedViews.find((v) => v.path === view.path);
    if (exists) {
      // 更新
      set({
        visitedViews: visitedViews.map((v) =>
          v.path === view.path ? { ...v, ...view } : v
        )
      });
    } else {
      set({ visitedViews: [...visitedViews, view] });
    }
    if (view.name && !cachedViews.includes(view.name)) {
      if (!view.meta?.noCache) {
        set({ cachedViews: [...cachedViews, view.name] });
      }
    }
  },
  delView: async (view) => {
    set((s) => ({
      visitedViews: s.visitedViews.filter((v) => v.path !== view.path),
      cachedViews: view.name
        ? s.cachedViews.filter((n) => n !== view.name)
        : s.cachedViews
    }));
  },
  delOthersViews: async (view) => {
    set((s) => ({
      visitedViews: s.visitedViews.filter((v) => v.affix || v.path === view.path),
      cachedViews: view.name ? [view.name] : []
    }));
  },
  delAllViews: async () => {
    set((s) => ({
      visitedViews: s.visitedViews.filter((v) => v.affix),
      cachedViews: []
    }));
  },
  delLeftViews: async (view) => {
    const { visitedViews } = get();
    const idx = visitedViews.findIndex((v) => v.path === view.path);
    if (idx === -1) return;
    set({
      visitedViews: visitedViews.filter((v, i) => i >= idx || v.affix)
    });
  },
  delRightViews: async (view) => {
    const { visitedViews } = get();
    const idx = visitedViews.findIndex((v) => v.path === view.path);
    if (idx === -1) return;
    set({
      visitedViews: visitedViews.filter((v, i) => i <= idx || v.affix)
    });
  },
  updateVisitedView: (view) => {
    set((s) => ({
      visitedViews: s.visitedViews.map((v) =>
        v.path === view.path ? { ...v, ...view } : v
      )
    }));
  },
  addAffixTags: (tags) => {
    set((s) => {
      const exists = new Set(s.visitedViews.map((v) => v.path));
      const merged = [
        ...tags.filter((t) => !exists.has(t.path)).map((t) => ({ ...t, affix: true })),
        ...s.visitedViews
      ];
      return { visitedViews: merged };
    });
  }
}));
