import { request } from '@/utils/request';

/** 单个类型的上传限制（对应后台「文件存储 → 上传大小限制」一行） */
export interface UploadTypeLimit {
  /** 类型名称，如 图片 / 视频 / 音频 */
  name: string;
  /** 该类型单文件大小上限（MB） */
  maxSizeMb: number;
  /** 该类型允许的扩展名（小写、不含点） */
  extensions: string[];
}

/** 当前生效的上传限制（分类型 + 全局兜底） */
export interface UploadLimits {
  /** 分类型限制列表；为空表示未配置分类型，走全局兜底 */
  typeLimits: UploadTypeLimit[];
  /** 全局兜底单文件上限（MB） */
  globalMaxSizeMb: number;
  /** 全局兜底允许扩展名（逗号分隔） */
  globalAllowedExtensions: string;
}

// 模块级缓存：一次页面生命周期内只拉取一次，避免每次上传都请求后端
let cache: UploadLimits | null = null;
let loaded = false;
let inflight: Promise<UploadLimits | null> | null = null;

/** 拉取后台当前生效的上传大小限制（带缓存，失败静默回退，仅提示一次） */
export function loadUploadLimits(): Promise<UploadLimits | null> {
  if (loaded) {
    return Promise.resolve(cache);
  }
  if (inflight) {
    return inflight;
  }
  inflight = request({ url: '/oss/config/upload-limits', method: 'get' })
    .then((res: any) => {
      cache = (res?.data as UploadLimits) || null;
      loaded = true; // 成功后不再重复请求
      return cache;
    })
    .catch(() => {
      loaded = true; // 失败也标记，避免每次上传反复请求/反复弹错；刷新页面会重试
      return null;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/**
 * 根据文件名后缀，从后台配置解析该文件允许的单文件大小上限（MB）。
 * - 命中某个分类型：返回该类型的上限；
 * - 配了分类型但未命中该后缀：返回 undefined（大小交由后端按「文件类型」判定，前端不误拦大小）；
 * - 未配置分类型：返回全局兜底上限（>0 时）。
 * 返回 undefined 表示「后台无对应限制」，调用方应回退到组件自身的兜底值。
 *
 * @param fileName 文件名
 * @param limits 后台上传限制（可为 null）
 * @returns 允许的最大 MB，或 undefined
 */
export function resolveMaxSizeMb(
  fileName: string,
  limits: UploadLimits | null
): number | undefined {
  if (!limits) {
    return undefined;
  }
  const ext = (fileName.split('.').pop() || '').toLowerCase();
  const typeLimits = limits.typeLimits || [];
  if (typeLimits.length > 0) {
    for (const t of typeLimits) {
      if ((t.extensions || []).some((e) => e.toLowerCase() === ext)) {
        return t.maxSizeMb;
      }
    }
    // 配了分类型但没命中：类型是否允许由后端判定，前端不做大小拦截
    return undefined;
  }
  // 未配置分类型：回退全局兜底上限
  return limits.globalMaxSizeMb > 0 ? limits.globalMaxSizeMb : undefined;
}
