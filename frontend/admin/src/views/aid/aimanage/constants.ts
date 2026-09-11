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

/** 常用像素尺寸。这里只提供编辑候选，不代表模型自动支持。 */
export const PIXEL_SIZE_OPTIONS = [
  '256x256',
  '640x360', '360x640',
  '512x512',
  '854x480', '480x854',
  '960x540', '540x960',
  '768x768', '768x1024', '1024x768',
  '1024x1024', '1024x1536', '1536x1024',
  '1280x720', '720x1280',
  '1536x1536',
  '1920x1080', '1080x1920',
  '2048x2048', '2048x3072', '3072x2048',
  '2560x1440', '1440x2560',
  '3072x3072',
  '3840x2160', '2160x3840',
  '4096x4096'
];

/** P 档与 K 档语义不同，后台分组展示且不做全局等价换算。 */
export const RESOLUTION_P_OPTIONS = [
  '360P', '480P', '540P', '576P', '720P', '768P', '900P', '1080P', '1440P', '2160P'
];
export const RESOLUTION_K_OPTIONS = ['1K', '2K', '3K', '4K'];

export const DURATION_CHOICES = Array.from({ length: 30 }, (_, index) => index + 1);

export const PRESET_SIZE: Record<string, string[]> = {
  image: [...PIXEL_SIZE_OPTIONS, ...RESOLUTION_P_OPTIONS, ...RESOLUTION_K_OPTIONS],
  video: [...PIXEL_SIZE_OPTIONS, ...RESOLUTION_P_OPTIONS, ...RESOLUTION_K_OPTIONS]
};

export const PRESET_ASPECT: Record<string, string[]> = {
  image: ['1:1', '2:3', '3:2', '3:4', '4:3', '7:9', '9:7', '9:16', '9:21', '16:9', '21:9'],
  video: ['16:9', '9:16', '1:1', '4:3', '3:4']
};

export const PRESET_DURATION = [...DURATION_CHOICES];

/** 统一尺寸展示，不改写保存给上游的原始枚举值。 */
export function formatSizeLabel(value: string): string {
  const text = value.trim();
  const pixel = text.match(/^(\d+)\s*[x×*]\s*(\d+)$/i);
  if (pixel) return `${pixel[1]}×${pixel[2]}`;
  const resolution = text.match(/^(\d+)\s*([pk])$/i);
  return resolution ? `${resolution[1]}${resolution[2].toUpperCase()}` : text;
}

export type SizeOptionGroup = 'pixel' | 'p' | 'k' | 'other';

export function classifySizeOption(value: string): SizeOptionGroup {
  const label = formatSizeLabel(value);
  if (/^\d+×\d+$/.test(label)) return 'pixel';
  if (/^\d+P$/.test(label)) return 'p';
  if (/^\d+K$/.test(label)) return 'k';
  return 'other';
}

export function compareSizeOptions(left: string, right: string): number {
  const groupOrder: Record<SizeOptionGroup, number> = { pixel: 0, p: 1, k: 2, other: 3 };
  const leftLabel = formatSizeLabel(left);
  const rightLabel = formatSizeLabel(right);
  const leftGroup = classifySizeOption(left);
  const rightGroup = classifySizeOption(right);
  if (leftGroup !== rightGroup) return groupOrder[leftGroup] - groupOrder[rightGroup];
  if (leftGroup === 'pixel') {
    const [lw, lh] = leftLabel.split('×').map(Number);
    const [rw, rh] = rightLabel.split('×').map(Number);
    return lw * lh - rw * rh || lw - rw || lh - rh;
  }
  if (leftGroup === 'p' || leftGroup === 'k') {
    return Number.parseInt(leftLabel, 10) - Number.parseInt(rightLabel, 10);
  }
  return leftLabel.localeCompare(rightLabel, 'zh-CN', { numeric: true });
}

/** 建空 capabilityModel（结构与原 Vue 版一致） */
export function makeEmptyCapabilityModel(): CapabilityModel {
  return {
    sizeOptions: [],
    aspectRatioOptions: [],
    durationOptions: [],
    outputFormatOptions: [],
    outputFpsOptions: [],
    audioModeOptions: [],
    audioTypes: [],
    builtInVoiceOptions: [],
    audioFormatOptions: [],
    audioSampleRateOptions: [],
    emotionOptions: [],
    voiceSampleFormats: [],
    seedanceTaskTypeOptions: [],
    allowedScenes: [],
    allowCustomWH: false,
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    inputImageFormats: [],
    inputVideoFormats: [],
    inputAudioFormats: [],
    inputDocumentFormats: [],
    maxInputMediaTotalFileSizeMb: null,
    inputMediaMaxUrlLength: null,
    inputImageMinDimensionPixels: null,
    inputImageMaxDimensionPixels: null,
    inputImageMinPixels: null,
    inputImageMaxPixels: null,
    inputImageMinAspectRatio: null,
    inputImageMaxAspectRatio: null,
    inputVideoMinDimensionPixels: null,
    inputVideoMaxDimensionPixels: null,
    inputVideoMinPixels: null,
    inputVideoMaxPixels: null,
    inputVideoMinAspectRatio: null,
    inputVideoMaxAspectRatio: null,
    inputVideoMinFps: null,
    inputVideoMaxFps: null,
    inputImageHighCountThreshold: null,
    inputImageHighCountMaxDimensionPixels: null,
    inputMediaAllowedMessageRoles: [],
    maxPromptCharacters: null,
    maxPromptCharactersCjk: null,
    minInputVideoDurationSeconds: null,
    minInputAudioDurationSeconds: null,
    maxReferenceImages: null,
    minReferenceImages: null,
    referenceImageFormats: [],
    referenceImageMinPixels: null,
    referenceImageMaxPixels: null,
    minOutputPixels: null,
    maxOutputPixels: null,
    minOutputAspectRatio: null,
    maxOutputAspectRatio: null,
    supportsBase64Image: false,
    base64ImageEnabled: false,
    supportsAudio: false,
    upstreamAudioField: undefined,
    upstreamResolutionMap: {},
    supportsReferenceAudio: false,
    minReferenceAudios: null,
    referenceAudioRequiresGeneratedAudio: true,
    referenceAudioRequiresVisualInput: false,
    maxReferenceAudios: null,
    referenceAudioMinDurationSeconds: null,
    referenceAudioMaxDurationSeconds: null,
    referenceAudioMaxTotalDurationSeconds: null,
    referenceAudioFormats: [],
    supportsVideoInput: false,
    minReferenceVideos: null,
    maxReferenceVideos: null,
    referenceVideoFormats: [],
    referenceVideoMinPixels: null,
    referenceVideoMaxPixels: null,
    sceneRules: {
      textOnly: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false
      },
      textToImage: { supportsAspectRatio: false, supportsSizePreset: false },
      imageToImage: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        aspectRatioFollowInput: false
      },
      textToVideo: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false
      },
      imageToVideo: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false,
        aspectRatioFollowInput: false
      },
      startEndToVideo: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false,
        aspectRatioFollowInput: false
      },
      referenceToVideo: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false,
        aspectRatioFollowInput: false
      },
      videoToVideo: {
        supportsAspectRatio: false,
        supportsSizePreset: false,
        supportsDuration: false,
        aspectRatioFollowInput: false
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
