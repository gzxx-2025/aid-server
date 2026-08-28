export const LANGUAGE_OPTIONS = [
  { code: 'zh-CN', name: '中文' },
  { code: 'en-US', name: '英文' },
  { code: 'ja-JP', name: '日文' }
];

export const GENDER_OPTIONS = [
  { code: 'female', name: '女' },
  { code: 'male', name: '男' },
  { code: 'neutral', name: '中性' }
];

export const AGE_RANGE_OPTIONS = [
  { code: 'child', name: '儿童' },
  { code: 'teen', name: '少年' },
  { code: 'young', name: '青年' },
  { code: 'adult', name: '成年' },
  { code: 'middle', name: '中年' },
  { code: 'elderly', name: '老年' }
];

export function resolveEnumLabel(
  kind: 'language' | 'gender' | 'ageRange',
  code?: string
): string {
  const source =
    kind === 'language' ? LANGUAGE_OPTIONS :
    kind === 'gender' ? GENDER_OPTIONS :
    AGE_RANGE_OPTIONS;
  const hit = source.find((x) => x.code === code);
  return hit ? hit.name : code || '-';
}

/**
 * 供应商情感编码 → 中文显示名（纯展示翻译，与后端 VoiceEmotionCapability 同表）。
 * 情感能力以供应商声明为唯一标准（模型 capabilityJson.emotions），系统无全局情感配置；
 * 未收录的编码原样展示，不阻断新供应商接入。
 */
export const EMOTION_LABELS: Record<string, string> = {
  happy: '开心', sad: '悲伤', angry: '愤怒', fearful: '恐惧', disgusted: '厌恶',
  surprised: '惊讶', calm: '中性', fluent: '生动', whisper: '低语',
  fear: '恐惧', hate: '厌恶', excited: '激动', coldness: '冷漠', neutral: '中性',
  depressed: '沮丧', 'lovey-dovey': '撒娇', shy: '害羞', comfort: '安慰鼓励',
  tension: '咆哮/焦急', tender: '温柔', storytelling: '讲故事', radio: '情感电台',
  magnetic: '磁性', advertising: '广告营销', 'vocal-fry': '气泡音', ASMR: '低语(ASMR)',
  news: '新闻播报', entertainment: '娱乐八卦', dialect: '方言',
  chat: '对话/闲聊', warm: '温暖', affectionate: '深情', authoritative: '权威'
};

/** 情感编码翻译：未收录原样返回 */
export function resolveEmotionLabel(code?: string): string {
  if (!code) return '-';
  return EMOTION_LABELS[code] || code;
}

/** 从模型 capabilityJson（JSON 字符串）解析供应商声明的情感编码列表；缺失/异常返回空数组 */
export function parseModelEmotions(capabilityJson?: string | null): string[] {
  if (!capabilityJson) return [];
  try {
    const obj = JSON.parse(capabilityJson);
    if (Array.isArray(obj?.emotions)) {
      return obj.emotions.filter((x: any) => typeof x === 'string' && x.trim()).map((x: string) => x.trim());
    }
  } catch { /* 解析失败视为未声明 */ }
  return [];
}

export function isNeverOffline(t?: string | null): boolean {
  if (!t) return true;
  return String(t).startsWith('9999');
}

export function isAlreadyOffline(t?: string | null): boolean {
  if (!t || isNeverOffline(t)) return false;
  const ts = new Date(t).getTime();
  return !isNaN(ts) && ts <= Date.now();
}

export function isOfflineSoon(t?: string | null): boolean {
  if (!t || isNeverOffline(t)) return false;
  const ts = new Date(t).getTime();
  if (isNaN(ts) || ts <= Date.now()) return false;
  return ts - Date.now() <= 30 * 86400 * 1000;
}
