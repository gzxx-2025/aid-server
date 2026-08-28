/**
 * 通用 JS 方法封装（从 ruoyi 迁移，保持接口一致）
 */
import dayjs from 'dayjs';

type TimeInput = Date | string | number | null | undefined;

/** 日期格式化 */
export function parseTime(time: TimeInput, pattern?: string): string | null {
  if (!time) return null;
  const format = pattern || 'YYYY-MM-DD HH:mm:ss';
  let value: any = time;
  if (typeof time === 'string' && /^[0-9]+$/.test(time)) {
    value = parseInt(time, 10);
  }
  if (typeof value === 'number' && value.toString().length === 10) {
    value = value * 1000;
  }
  const day = dayjs(value);
  if (!day.isValid()) return null;
  return day.format(format);
}

/** 添加日期范围 */
export function addDateRange(
  params: Record<string, any>,
  dateRange: [string, string] | string[] | undefined | null,
  propName?: string
) {
  const search: Record<string, any> = params;
  search.params =
    typeof search.params === 'object' && search.params !== null && !Array.isArray(search.params)
      ? search.params
      : {};
  const range: string[] = Array.isArray(dateRange) ? dateRange : [];
  if (typeof propName === 'undefined') {
    search.params.beginTime = range[0];
    search.params.endTime = range[1];
  } else {
    search.params['begin' + propName] = range[0];
    search.params['end' + propName] = range[1];
  }
  return search;
}

/** 回显数据字典 */
export function selectDictLabel(
  datas: Array<{ value: string | number; label: string }> | undefined | null,
  value: string | number | undefined
): string {
  if (value === undefined || value === null || !datas) return '';
  const actions: string[] = [];
  datas.some((dict) => {
    if (String(dict.value) === String(value)) {
      actions.push(dict.label);
      return true;
    }
    return false;
  });
  if (actions.length === 0) actions.push(String(value));
  return actions.join('');
}

/** 回显字典（多值） */
export function selectDictLabels(
  datas: Array<{ value: string | number; label: string }> | undefined | null,
  value: string | string[] | undefined,
  separator = ','
): string {
  if (value === undefined || value === null || !datas || (Array.isArray(value) && value.length === 0)) return '';
  const raw = Array.isArray(value) ? value.join(separator) : value;
  const temp = String(raw).split(separator);
  const actions: string[] = [];
  temp.forEach((item) => {
    const hit = datas.find((d) => String(d.value) === item);
    actions.push(hit ? hit.label : item);
  });
  return actions.join(separator);
}

/** 转 params */
export function tansParams(params: Record<string, any>): string {
  let result = '';
  for (const propName of Object.keys(params)) {
    const value = params[propName];
    const part = encodeURIComponent(propName) + '=';
    if (value !== null && value !== '' && typeof value !== 'undefined') {
      if (typeof value === 'object') {
        for (const key of Object.keys(value)) {
          if (value[key] !== null && value[key] !== '' && typeof value[key] !== 'undefined') {
            const subParams = `${propName}[${key}]`;
            const subPart = encodeURIComponent(subParams) + '=';
            result += subPart + encodeURIComponent(value[key]) + '&';
          }
        }
      } else {
        result += part + encodeURIComponent(value) + '&';
      }
    }
  }
  return result;
}

/** 构造树型结构 */
export function handleTree<T extends Record<string, any>>(
  data: T[],
  id: string = 'id',
  parentId: string = 'parentId',
  children: string = 'children'
): T[] {
  const config = { id, parentId, childrenList: children };
  const childrenListMap: Record<string, any> = {};
  const tree: T[] = [];
  for (const d of data) {
    const k = d[config.id];
    childrenListMap[k] = d;
    if (!d[config.childrenList]) {
      (d as any)[config.childrenList] = [];
    }
  }
  for (const d of data) {
    const pId = d[config.parentId];
    const parentObj = childrenListMap[pId];
    if (!parentObj) {
      tree.push(d);
    } else {
      parentObj[config.childrenList].push(d);
    }
  }
  return tree;
}

/** undefined/null 转空串 */
export function parseStrEmpty(str: any): string {
  if (!str || str === 'undefined' || str === 'null') return '';
  return String(str);
}

/** blob 校验 */
export function blobValidate(data: Blob): boolean {
  return data.type !== 'application/json';
}

/** 获取项目资源路径 */
/** 拼接部署上下文路径的站内绝对地址（管理端可部署在 /admin/ 等子路径） */
export function resolveAppUrl(path: string): string {
  const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '');
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${base}${suffix}`;
}

export function getNormalPath(p: string): string {
  if (!p || p.length === 0 || p === 'undefined') return p;
  const res = p.replace('//', '/');
  if (res[res.length - 1] === '/') return res.slice(0, -1);
  return res;
}
