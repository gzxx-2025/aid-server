import { request } from '@/utils/request';
import type { ApiResponse } from '@/types/common';

/** 配置连通性测试结果（后端 ConfigTestResult） */
export interface ConfigTestResult {
  /** 是否连通成功 */
  success: boolean;
  /** ≤30字中文结论 */
  message: string;
  /** 详细信息（仅超管可见，可能为 null） */
  details?: string | null;
  /** 耗时（毫秒） */
  elapsedMs: number;
  /** 实际命中的厂商，可空 */
  provider?: string | null;
}

/** 图片内容安全审查结果（后端完整审查结果） */
export interface ModerationResult {
  /** 处置建议：Pass / Review / Block */
  suggestion?: string | null;
  /** 命中主标签 */
  label?: string | null;
  /** 命中子标签 */
  subLabel?: string | null;
  /** 命中分值 */
  score?: number | null;
  /** IMS 请求ID */
  requestId?: string | null;
  /** 图片 MD5 */
  fileMd5?: string | null;
  /** 原始返回 JSON 字符串 */
  rawJson?: string | null;
  /** 是否发生错误 */
  error?: boolean | null;
  /** 错误信息 */
  errorMessage?: string | null;
}

/** 图片审查配置（GET 读取脱敏 / POST 整组保存） */
export interface ImageModerationConfig {
  /** 总开关 */
  enabled: boolean;
  /** 厂商（预留多厂商，当前 tencent） */
  provider: string;
  /** 腾讯云地域 */
  tencentRegion: string;
  /** 腾讯云 SecretId */
  tencentSecretId: string;
  /** 腾讯云 SecretKey（含 **** 视为未改） */
  tencentSecretKey: string;
  /** COS 模式优先用 FileUrl */
  prioritizeFileUrl: boolean;
  /** 审查时机：AFTER_UPLOAD / BEFORE_UPLOAD */
  moderationStage: string;
  /** Review 建议时是否拦截 */
  blockOnSuggestionReview: boolean;
  /** 审查异常时是否放行 */
  failOpenOnError: boolean;
  /** 是否记录通过的图片 */
  logPassed: boolean;
  /** 日志保留天数 */
  logRetentionDays: number;
}

/** testKey 取值 */
export type ConfigTestKey =
  | 'alipay'
  | 'smtp'
  | 'oss'
  | 'sms'
  | 'wxpay'
  | 'image-moderation'
  | 'ai-model'
  | 'ai-provider';

/** 统一配置连通性测试 */
export function runConfigTest(
  testKey: ConfigTestKey,
  payload: Record<string, any>
): Promise<ApiResponse<ConfigTestResult>> {
  return request({
    url: '/system/config/test',
    method: 'post',
    data: { testKey, payload }
  });
}

/**
 * 图片内容安全审查测试（multipart：file 可选 + payloadJson 文本）。
 *
 * 端点：POST /aidconfig/imgmoderation/test （沿用 aidconfig:aidconfig:edit 权限，
 * 作为 /manager/aidconfig 页面内嵌「图片审查」区块的连通性测试入口）。
 */
export function testImageModeration(
  file: File | null,
  payload: Record<string, any>
): Promise<ApiResponse<ModerationResult>> {
  const formData = new FormData();
  if (file) formData.append('file', file, file.name);
  formData.append('payloadJson', JSON.stringify(payload));
  return request({
    url: '/aidconfig/imgmoderation/test',
    method: 'post',
    headers: { 'Content-Type': 'multipart/form-data' },
    data: formData
  });
}

/**
 * 读取图片审查配置（密钥脱敏）。
 *
 * 端点：GET /aidconfig/imgmoderation/config
 */
export function getImageModerationConfig(): Promise<ApiResponse<ImageModerationConfig>> {
  return request({
    url: '/aidconfig/imgmoderation/config',
    method: 'get'
  });
}

/**
 * 整组保存图片审查配置。
 *
 * 端点：POST /aidconfig/imgmoderation/config
 * 密钥字段提交脱敏串（含 ****）视为未修改，后端会保留原值。
 */
export function saveImageModerationConfig(
  body: ImageModerationConfig
): Promise<ApiResponse<any>> {
  return request({
    url: '/aidconfig/imgmoderation/config',
    method: 'post',
    data: body
  });
}
