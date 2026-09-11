import type { ModelParameter } from './modelDefinition';

const definitions: Record<string, [string, ModelParameter['type']]> = {
  prompt: ['提示词', 'string'], options: ['协议扩展参数', 'object'], messages: ['对话消息', 'array'],
  size: ['输出规格', 'string'], negativePrompt: ['反向提示词', 'string'], referenceImageUrl: ['参考图片', 'string'],
  expectedImageCount: ['生成图片数量', 'integer'], imageUrl: ['首帧图片', 'string'], durationSeconds: ['输出时长（秒）', 'integer'],
  aspectRatio: ['画面比例', 'string'], audio: ['生成声音', 'boolean'], bgm: ['生成背景音乐', 'boolean'],
  audioType: ['声音类型', 'string'], voiceId: ['视频音色', 'string'], referenceAudios: ['参考音频列表', 'array'],
  referenceVideoRecordIds: ['参考视频记录', 'array'], ttsText: ['配音文本', 'string'], voiceCode: ['音色', 'string'],
  language: ['语言', 'string'], emotion: ['情绪', 'string'], emotionScale: ['情绪强度', 'integer'],
  speechRate: ['语速', 'integer'], loudnessRate: ['音量', 'integer'], pitch: ['音调', 'integer'],
  audioFormat: ['输出音频格式', 'string'], sampleRate: ['采样率', 'integer'], enableTimestamp: ['时间戳', 'boolean'],
  reasoningEnabled: ['启用思考', 'boolean'], reasoningLevel: ['思考强度', 'string'],
  reasoningBudgetTokens: ['思考预算', 'integer'], includeReasoning: ['返回思考', 'boolean']
};
const fields: Record<string, string[]> = {
  text: ['prompt', 'messages', 'options', 'reasoningEnabled', 'reasoningLevel', 'reasoningBudgetTokens', 'includeReasoning'],
  image: ['prompt', 'size', 'negativePrompt', 'referenceImageUrl', 'expectedImageCount', 'options'],
  video: ['prompt', 'imageUrl', 'durationSeconds', 'aspectRatio', 'audio', 'bgm', 'audioType', 'voiceId', 'referenceAudios', 'referenceVideoRecordIds', 'options'],
  audio: ['ttsText', 'voiceCode', 'language', 'emotion', 'emotionScale', 'speechRate', 'loudnessRate', 'pitch', 'audioFormat', 'sampleRate', 'enableTimestamp', 'options']
};

export function requestFieldOptions(modelType: string) {
  return (fields[modelType] || []).map((name) => ({ value: name, label: `${definitions[name][0]}（${name}）` }));
}

export function requestFieldDefinition(name: string): ModelParameter {
  const [label, type] = definitions[name] || [name, 'string'];
  return { name, label, type, properties: type === 'object' ? [] : undefined,
    items: type === 'array' ? { name: 'item', label: '列表项', type: name === 'referenceVideoRecordIds' ? 'integer' : 'object', properties: [] } : undefined };
}
