import { useEffect, useState } from 'react';
import { useDictStore } from '@/store/useDictStore';
import { getDicts } from '@/api/system/dict/data';

interface UseDictResult {
  [key: string]: Array<{ value: string; label: string; elTagType?: string; raw?: any }>;
}

/**
 * 批量加载字典，与原 Vue 项目的 this.getDicts 行为一致
 */
export function useDict(...dictTypes: string[]): UseDictResult {
  const [dict, setDict] = useState<UseDictResult>({});
  const storeSet = useDictStore((s) => s.setDict);
  const storeGet = useDictStore.getState().getDict;

  useEffect(() => {
    let canceled = false;
    const loaders = dictTypes.map(async (type) => {
      const cached = storeGet(type);
      if (cached) return [type, cached] as const;
      try {
        const res: any = await getDicts(type);
        const value = (res.data || []).map((d: any) => ({
          label: d.dictLabel,
          value: d.dictValue,
          elTagType: d.listClass,
          elTagClass: d.cssClass,
          raw: d
        }));
        storeSet({ key: type, value });
        return [type, value] as const;
      } catch (e) {
        console.error(`[useDict] 加载字典 "${type}" 失败:`, e);
        return [type, []] as const;
      }
    });
    Promise.all(loaders).then((pairs) => {
      if (canceled) return;
      const next: UseDictResult = {};
      pairs.forEach(([k, v]) => (next[k] = v as any));
      setDict(next);
    });
    return () => {
      canceled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dictTypes.join(',')]);

  return dict;
}
