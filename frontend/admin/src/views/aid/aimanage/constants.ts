import type { CapabilityModel, ScheduleStrategy } from './types';

// ==================== 文本协议下拉选项 ====================

/** 模型协议下拉选项（aid_ai_model.protocol） */
export const TEXT_PROTOCOL_OPTIONS = [
  {
    value: 'openai-compatible-text',
    label: 'OpenAI 兼容（推荐）',
    desc: 'OpenAI 原生 / 火山方舟 / 阿里百炼 / DeepSeek / Kimi / 智谱 / Grok / Together 等'
  },
  {
    value: 'gemini-text',
    label: 'Google Gemini',
    desc: 'Google Gemini 原生协议（generateContent）'
  }
];

// ==================== 思考模式预设 ====================

/**
 * 思考模式关闭预设：按厂商映射到 extra_body JSON。
 * 业务约定：默认关闭、不允许开启。多家厂商默认都开了思考，token 计费会虚高 30%~50%。
 */
export const THINKING_DISABLE_PRESETS: Record<string, Record<string, any>> = {
  // 火山方舟（Doubao Seed 系列）
  volcengine: { thinking: { type: 'disabled' } },
  ark: { thinking: { type: 'disabled' } },
  bytedance: { thinking: { type: 'disabled' } },
  // 阿里百炼（Qwen3 系列）
  dashscope: { enable_thinking: false },
  alibaba: { enable_thinking: false },
  aliyun: { enable_thinking: false }
};

/**
 * 根据厂商编码取思考关闭预设；找不到则返回 null（让运营自己写自定义 JSON）。
 */
export function getThinkingDisablePreset(providerCode?: string): Record<string, any> | null {
  if (!providerCode) return null;
  const key = providerCode.toLowerCase();
  return THINKING_DISABLE_PRESETS[key] || null;
}

export const COMMON_PROVIDERS = [
  'alibaba',
  'volcengine',
  'openai',
  'google',
  'tencent',
  'baidu',
  'replicate'
];

export const DURATION_CHOICES = [5, 10, 15, 30];

export const PRESET_SIZE: Record<string, string[]> = {
  image: ['1K', '2K', '4K'],
  video: ['720P', '1080P']
};

export const PRESET_ASPECT: Record<string, string[]> = {
  image: ['1:1', '2:3', '3:2', '3:4', '4:3', '7:9', '9:7', '9:16', '9:21', '16:9', '21:9'],
  video: ['16:9', '9:16', '1:1', '4:3', '3:4']
};

export const PRESET_DURATION = [5, 10, 15, 30];

/** 建空 capabilityModel（结构与原 Vue 版一致） */
export function makeEmptyCapabilityModel(): CapabilityModel {
  return {
    sizeOptions: [],
    aspectRatioOptions: [],
    durationOptions: [],
    allowCustomWH: false,
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    inputImageFormats: [],
    inputVideoFormats: [],
    inputAudioFormats: [],
    inputDocumentFormats: [],
    maxReferenceImages: null,
    minReferenceImages: null,
    supportsBase64Image: false,
    base64ImageEnabled: false,
    supportsAudio: false,
    upstreamAudioField: undefined,
    upstreamResolutionMap: {},
    supportsReferenceAudio: false,
    referenceAudioRequiresGeneratedAudio: true,
    maxReferenceAudios: null,
    referenceAudioMinDurationSeconds: null,
    referenceAudioMaxDurationSeconds: null,
    referenceAudioMaxTotalDurationSeconds: null,
    referenceAudioFormats: [],
    sceneRules: {
      textOnly: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false
      },
      textToImage: { supportsAspectRatio: true, supportsSizePreset: true },
      imageToImage: {
        supportsAspectRatio: true,
        supportsSizePreset: true,
        aspectRatioFollowInput: true
      },
      textToVideo: {
        supportsAspectRatio: true,
        supportsSizePreset: true,
        supportsDuration: true
      },
      imageToVideo: {
        supportsAspectRatio: true,
        supportsSizePreset: true,
        supportsDuration: true,
        aspectRatioFollowInput: true
      }
    }
  };
}

export function makeDefaultScheduleStrategy(): ScheduleStrategy {
  return {
    dispatchMode: 'POLL_ONLY',
    supportsCallback: false,
    firstPollDelaySeconds: 5,
    baseIntervalSeconds: 5,
    maxIntervalSeconds: 30,
    backoffFactor: 1.5,
    maxRetryCount: 120,
    maxLifeSeconds: 3600,
    progressTimeoutSeconds: 600
  };
}

/** 按模型类型推断默认 meterType */
export function inferMeterType(modelType?: string): string {
  if (modelType === 'text') return 'TOKEN';
  if (modelType === 'image') return 'PER_IMAGE';
  if (modelType === 'video') return 'SKU_PACKAGE';
  return 'SKU_PACKAGE';
}

export function commonParamNamesByType(modelType?: string): string[] {
  if (modelType === 'image')
    return ['size', 'aspectRatio', 'outputCount', 'seed', 'negativePrompt', 'prompt', 'referenceImages'];
  if (modelType === 'video')
    return ['size', 'aspectRatio', 'duration', 'seed', 'negativePrompt', 'prompt', 'imageUrl'];
  if (modelType === 'text')
    return ['prompt', 'maxTokens', 'temperature', 'topP', 'seed'];
  return [];
}
