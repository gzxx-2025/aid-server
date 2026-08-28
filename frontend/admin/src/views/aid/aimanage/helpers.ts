import type { CapabilityModel, InputPricing, Model, ParamMapping, Sku, SkuEditData } from './types';
import { makeEmptyCapabilityModel, inferMeterType } from './constants';
import { extractUnmanagedCapability, mergeManagedCapability } from './capabilityMerge';

/** 模型行表格"能力"列单行紧凑摘要 */
export function buildCapSummary(row: Model): string {
  const parts: string[] = [];
  if (row.defaultSizeCode) parts.push(row.defaultSizeCode);
  if (row.defaultAspectRatio) parts.push(row.defaultAspectRatio);
  if (row.defaultDurationSeconds) parts.push(row.defaultDurationSeconds + 's');
  if (row.maxOutputCount && row.maxOutputCount > 1) parts.push('×' + row.maxOutputCount);
  return parts.length ? parts.join(' · ') : '—';
}

/** 从行数据解析 meterType（用于列表展示，与后端 BillingAmountCalculatorImpl.resolveMeterType 一致） */
export function resolveMeterType(row: Model): string {
  if (row.billingRuleJson) {
    try {
      const rule = JSON.parse(row.billingRuleJson);
      if (rule.meterType) return rule.meterType;
    } catch {
      /* ignore */
    }
  }
  return inferMeterType(row.modelType);
}

/** capabilityJson 字符串 → capabilityModel */
export function parseCapabilityJsonToModel(jsonStr?: string | null): CapabilityModel {
  const m = makeEmptyCapabilityModel();
  if (!jsonStr) return m;
  let obj: any;
  try {
    obj = JSON.parse(jsonStr);
  } catch {
    return m;
  }
  m.preservedCapability = extractUnmanagedCapability(obj);
  m.supportsReasoning = obj.supportsReasoning === true;
  m.supportsReasoningDisable = obj.supportsReasoningDisable === true;
  m.returnsReasoningContent = obj.returnsReasoningContent === true;
  m.supportsReasoningBudget = obj.supportsReasoningBudget === true;
  m.defaultReasoningEnabled = obj.defaultReasoningEnabled === true;
  m.reasoningApiStyle = typeof obj.reasoningApiStyle === 'string' ? obj.reasoningApiStyle : undefined;
  m.outputTokenApiField = typeof obj.outputTokenApiField === 'string' ? obj.outputTokenApiField : undefined;
  m.allowedReasoningLevels = Array.isArray(obj.allowedReasoningLevels)
    ? obj.allowedReasoningLevels.map(String).filter(Boolean) : [];
  if (Array.isArray(obj.sizeOptions)) m.sizeOptions = obj.sizeOptions.slice();
  if (Array.isArray(obj.aspectRatioOptions)) m.aspectRatioOptions = obj.aspectRatioOptions.slice();
  if (Array.isArray(obj.durationOptions)) m.durationOptions = obj.durationOptions.slice();
  if (typeof obj.allowCustomWH === 'boolean') m.allowCustomWH = obj.allowCustomWH;
  // 单次最多参考图张数（四态语义）：
  //   缺省/null → null（不设上限，回退厂商默认）
  //   -1 → 无限；0 → 禁止参考图；N(>0) → 上限 N
  //   非法值（非整数、< -1）一律归一为 null
  if (obj.maxReferenceImages === null || obj.maxReferenceImages === undefined) {
    m.maxReferenceImages = null;
  } else {
    const n = Number(obj.maxReferenceImages);
    m.maxReferenceImages = Number.isFinite(n) && n >= -1 ? Math.trunc(n) : null;
  }
  // 最少参考图张数：缺省/null → null（不要求带图）；N>=0 → 按值保留；
  // 非法值（非数字、负数）一律归一为 null，与后端 readMinFromCapabilityJson 的容错口径一致
  if (obj.minReferenceImages === null || obj.minReferenceImages === undefined) {
    m.minReferenceImages = null;
  } else {
    const n = Number(obj.minReferenceImages);
    m.minReferenceImages = Number.isFinite(n) && n >= 0 ? Math.trunc(n) : null;
  }
  // Base64 传图：能力位（官方是否支持）+ 运营开关，缺省均 false
  m.supportsBase64Image = obj.supportsBase64Image === true;
  m.base64ImageEnabled = obj.base64ImageEnabled === true;
  // 音画同出用户开关：仅 video 有意义，缺省 false
  m.supportsAudio = obj.supportsAudio === true;
  m.upstreamAudioField = ['generate_audio', 'audio', 'none'].includes(obj.upstreamAudioField)
    ? obj.upstreamAudioField : undefined;
  m.upstreamResolutionMap = {};
  if (obj.upstreamResolutionMap && typeof obj.upstreamResolutionMap === 'object'
    && !Array.isArray(obj.upstreamResolutionMap)) {
    for (const [source, target] of Object.entries(obj.upstreamResolutionMap)) {
      const normalizedSource = source.trim();
      const normalizedTarget = typeof target === 'string' ? target.trim() : '';
      if (normalizedSource && normalizedTarget) {
        m.upstreamResolutionMap[normalizedSource] = normalizedTarget;
      }
    }
  }
  m.supportsReferenceAudio = obj.supportsReferenceAudio === true;
  m.referenceAudioRequiresGeneratedAudio = obj.referenceAudioRequiresGeneratedAudio !== false;
  if (obj.maxReferenceAudios === null || obj.maxReferenceAudios === undefined) {
    m.maxReferenceAudios = null;
  } else {
    const maxReferenceAudios = Number(obj.maxReferenceAudios);
    m.maxReferenceAudios = Number.isFinite(maxReferenceAudios) && maxReferenceAudios >= -1
      ? Math.trunc(maxReferenceAudios) : null;
  }
  m.referenceAudioMinDurationSeconds = Number.isFinite(Number(obj.referenceAudioMinDurationSeconds))
    ? Math.max(0, Math.trunc(Number(obj.referenceAudioMinDurationSeconds))) : null;
  m.referenceAudioMaxDurationSeconds = Number.isFinite(Number(obj.referenceAudioMaxDurationSeconds))
    ? Math.max(0, Math.trunc(Number(obj.referenceAudioMaxDurationSeconds))) : null;
  m.referenceAudioMaxTotalDurationSeconds = Number.isFinite(Number(obj.referenceAudioMaxTotalDurationSeconds))
    ? Math.max(0, Math.trunc(Number(obj.referenceAudioMaxTotalDurationSeconds))) : null;
  m.referenceAudioFormats = Array.isArray(obj.referenceAudioFormats)
    ? obj.referenceAudioFormats.map((v: unknown) => String(v).trim().toLowerCase()).filter(Boolean) : [];
  const sr = obj.sceneRules || {};
  (['textOnly', 'textToImage', 'imageToImage', 'textToVideo', 'imageToVideo'] as const).forEach(
    (k) => {
      if (sr[k] && typeof sr[k] === 'object') {
        (m.sceneRules as any)[k] = Object.assign({}, (m.sceneRules as any)[k], sr[k]);
      }
    }
  );
  return m;
}

/** capabilityModel + 顶层 default* → capabilityJson 对象 */
export function buildCapabilityJsonObject(form: Model, cap: CapabilityModel): Record<string, any> {
  const t = form.modelType;
  if (t === 'text') {
    const supportsReasoning = cap.supportsReasoning === true;
    return mergeManagedCapability(cap.preservedCapability, {
      supportsReasoning,
      supportsReasoningDisable: supportsReasoning && cap.supportsReasoningDisable === true,
      returnsReasoningContent: supportsReasoning && cap.returnsReasoningContent === true,
      supportsReasoningBudget: supportsReasoning && cap.supportsReasoningBudget === true,
      defaultReasoningEnabled: supportsReasoning && cap.defaultReasoningEnabled === true,
      reasoningApiStyle: supportsReasoning ? cap.reasoningApiStyle || undefined : undefined,
      outputTokenApiField: supportsReasoning ? cap.outputTokenApiField || undefined : undefined,
      allowedReasoningLevels: supportsReasoning ? cap.allowedReasoningLevels || [] : [],
      sceneRules: { textOnly: { ...cap.sceneRules.textOnly } }
    });
  }
  const obj: Record<string, any> = {
    sizeOptions: cap.sizeOptions.slice(),
    defaultSize: form.defaultSizeCode || cap.sizeOptions[0] || '',
    aspectRatioOptions: cap.aspectRatioOptions.slice(),
    defaultAspectRatio: form.defaultAspectRatio || cap.aspectRatioOptions[0] || ''
  };
  if (t === 'image') {
    obj.allowCustomWH = !!cap.allowCustomWH;
    obj.sceneRules = {
      textToImage: { ...cap.sceneRules.textToImage },
      imageToImage: { ...cap.sceneRules.imageToImage }
    };
  } else if (t === 'video') {
    obj.durationOptions = cap.durationOptions.slice();
    obj.defaultDurationSeconds = form.defaultDurationSeconds || cap.durationOptions[0] || null;
    obj.sceneRules = {
      textToVideo: { ...cap.sceneRules.textToVideo },
      imageToVideo: { ...cap.sceneRules.imageToVideo }
    };
  }
  // 单次最多参考图张数（四态）：留空=null 不写键（厂商默认兜底）；-1=无限；0=禁止；N=上限
  // 只要运营显式设置了值（含 -1 和 0），就写入该键
  if (cap.maxReferenceImages != null) {
    const n = Math.trunc(Number(cap.maxReferenceImages));
    if (Number.isFinite(n) && n >= -1) {
      obj.maxReferenceImages = n;
    }
  }
  // 最少参考图张数：留空=null 不写键（不要求带图）；N>=1 写入，后端建任务前按此拦截缺图请求
  // 0 也允许显式写入（明确声明"不要求带图"，与缺省语义一致但可读性更好）
  if (cap.minReferenceImages != null) {
    const n = Math.trunc(Number(cap.minReferenceImages));
    if (Number.isFinite(n) && n >= 0) {
      obj.minReferenceImages = n;
    }
  }
  // Base64 传图：官方支持才写能力位；仅在支持时才写启用开关（不支持时强制丢弃 enabled，防脏数据）
  if (cap.supportsBase64Image === true) {
    obj.supportsBase64Image = true;
    if (cap.base64ImageEnabled === true) {
      obj.base64ImageEnabled = true;
    }
  }
  // 音画同出：video 必写布尔位；true=C 端可开关，false=禁止选择
  if (t === 'video') {
    obj.supportsAudio = cap.supportsAudio === true;
    if (form.protocol === 'configurable-async-video'
      && cap.supportsAudio === true && cap.upstreamAudioField) {
      obj.upstreamAudioField = cap.upstreamAudioField;
    }
    if (form.protocol === 'configurable-async-video') {
      const resolutionMap: Record<string, string> = {};
      for (const [source, target] of Object.entries(cap.upstreamResolutionMap || {})) {
        const configuredSource = cap.sizeOptions.find((option) => option.trim().toLowerCase() === source.trim().toLowerCase());
        const normalizedTarget = target.trim();
        if (configuredSource && normalizedTarget) {
          resolutionMap[configuredSource] = normalizedTarget;
        }
      }
      if (Object.keys(resolutionMap).length) {
        obj.upstreamResolutionMap = resolutionMap;
      }
    }
    obj.supportsReferenceAudio = cap.supportsReferenceAudio === true;
    if (cap.supportsReferenceAudio === true) {
      obj.referenceAudioRequiresGeneratedAudio = cap.referenceAudioRequiresGeneratedAudio !== false;
      const maxReferenceAudios = Math.trunc(Number(cap.maxReferenceAudios ?? 0));
      obj.maxReferenceAudios = Number.isFinite(maxReferenceAudios) && maxReferenceAudios >= -1
        ? maxReferenceAudios : 0;
      obj.referenceAudioMinDurationSeconds = Math.max(0, Math.trunc(Number(cap.referenceAudioMinDurationSeconds ?? 0)));
      obj.referenceAudioMaxDurationSeconds = Math.max(0, Math.trunc(Number(cap.referenceAudioMaxDurationSeconds ?? 0)));
      obj.referenceAudioMaxTotalDurationSeconds = Math.max(0, Math.trunc(Number(cap.referenceAudioMaxTotalDurationSeconds ?? 0)));
      obj.referenceAudioFormats = Array.from(new Set(
        cap.referenceAudioFormats.map((v) => v.trim().toLowerCase()).filter(Boolean)
      ));
    }
  }
  const merged = mergeManagedCapability(cap.preservedCapability, obj);
  if (t === 'video' && cap.upstreamAudioField === 'none') {
    delete merged.forceGenerateAudio;
  }
  if (t === 'video' && form.protocol === 'configurable-async-video'
    && obj.upstreamResolutionMap && Object.keys(obj.upstreamResolutionMap).length) {
    delete merged.upstreamResolution;
  }
  return merged;
}

/** paramMappings 表格行 → paramMappingJson 对象 */
export function buildParamMappingJsonObject(rows: ParamMapping[]): Record<string, any> {
  const out: Record<string, any> = {};
  for (const r of rows) {
    if (!r.paramName || !r.provider) continue;
    if (!out[r.paramName]) out[r.paramName] = {};
    out[r.paramName][r.provider] = r.providerParamName || '';
  }
  return out;
}

/** paramMappingJson 字符串 → 表格行 */
export function parseParamMappingJsonToRows(jsonStr?: string | null): ParamMapping[] {
  const rows: ParamMapping[] = [];
  if (!jsonStr) return rows;
  let obj: any;
  try {
    obj = JSON.parse(jsonStr);
  } catch {
    return rows;
  }
  if (!obj || typeof obj !== 'object') return rows;
  Object.keys(obj).forEach((paramName) => {
    const inner = obj[paramName];
    if (inner && typeof inner === 'object') {
      Object.keys(inner).forEach((provider) => {
        rows.push({
          paramName,
          provider,
          providerParamName: inner[provider] || ''
        });
      });
    }
  });
  return rows;
}

/** billingRuleJson 字符串 → { meterType, skuEditData } */
export function parseBillingRuleJson(json: string): {
  meterType?: string;
  skuEditData: SkuEditData;
} {
  const result: { meterType?: string; skuEditData: SkuEditData } = {
    skuEditData: {
      charToTokenRatio: 2,
      usagePricingMode: 'AGGREGATE',
      allowExtraCharge: false,
      skuList: []
    }
  };
  if (!json) return result;
  try {
    const rule = JSON.parse(json);
    if (!rule || typeof rule !== 'object' || Array.isArray(rule)) return result;
    result.skuEditData.preservedBillingRule = cloneJsonRecord(rule);
    if (rule.meterType) result.meterType = rule.meterType;
    if (rule.settleRule?.charToTokenRatio)
      result.skuEditData.charToTokenRatio = rule.settleRule.charToTokenRatio;
    result.skuEditData.usagePricingMode = rule.settleRule?.usagePricingMode === 'BUCKETED'
      ? 'BUCKETED' : 'AGGREGATE';
    result.skuEditData.allowExtraCharge = rule.settleRule?.allowExtraCharge === true;
    // 规则级输入媒体计费（图片/视频输入附加费）
    result.skuEditData.inputPricing = normalizeInputPricing(rule.inputPricing);
    if (Array.isArray(rule.skus)) {
      result.skuEditData.skuList = rule.skus.map((s: any) => ({
        preservedSku: cloneJsonRecord(s),
        skuCode: s.skuCode || '',
        skuName: s.skuName || '',
        meterType: s.meterType || null,
        enabled: s.enabled !== false,
        priority: s.priority != null ? s.priority : 1,
        match: s.match ? { ...s.match } : {},
        price: s.price != null ? s.price : null,
        pricePerSecond: s.pricePerSecond != null ? s.pricePerSecond : null,
        pricePerChar: s.pricePerChar != null ? s.pricePerChar : null,
        inputPricePerMillion: s.inputPricePerMillion != null ? s.inputPricePerMillion : null,
        outputPricePerMillion: s.outputPricePerMillion != null ? s.outputPricePerMillion : null,
        cachedInputPricePerMillion: s.cachedInputPricePerMillion != null ? s.cachedInputPricePerMillion : null,
        cacheWritePricePerMillion: s.cacheWritePricePerMillion != null ? s.cacheWritePricePerMillion : null,
        reasoningPricePerMillion: s.reasoningPricePerMillion != null ? s.reasoningPricePerMillion : null,
        inputPricing: normalizeInputPricing(s.inputPricing),
        remark: s.remark || ''
      }));
    }
  } catch {
    /* ignore */
  }
  return result;
}

/** JSON 规则深拷贝，隔离表单编辑与原始保留对象。 */
function cloneJsonRecord(raw: unknown): Record<string, any> | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  try {
    return JSON.parse(JSON.stringify(raw)) as Record<string, any>;
  } catch {
    return undefined;
  }
}

/** 解析 inputPricing 对象（image/video 两段），非法或全空返回 null */
export function normalizeInputPricing(raw: any): InputPricing | null {
  if (!raw || typeof raw !== 'object') return null;
  const out: InputPricing = {};
  if (raw.image && typeof raw.image === 'object') {
    out.image = {
      unitPrice: raw.image.unitPrice != null ? Number(raw.image.unitPrice) : null,
      maxCount: raw.image.maxCount != null ? Number(raw.image.maxCount) : null
    };
  }
  if (raw.video && typeof raw.video === 'object') {
    out.video = {
      unitPrice: raw.video.unitPrice != null ? Number(raw.video.unitPrice) : null,
      maxSeconds: raw.video.maxSeconds != null ? Number(raw.video.maxSeconds) : null,
      maxCount: raw.video.maxCount != null ? Number(raw.video.maxCount) : null
    };
  }
  return out.image || out.video ? out : null;
}

/** inputPricing → 序列化对象（剔除空段与空字段），全空返回 undefined 以便 JSON 省略该键 */
function mergeInputPricing(preserved: unknown, current?: InputPricing | null): any {
  const out = cloneJsonRecord(preserved) || {};
  mergeInputPricingSegment(out, 'image', current?.image, ['unitPrice', 'maxCount']);
  mergeInputPricingSegment(out, 'video', current?.video, ['unitPrice', 'maxSeconds', 'maxCount']);
  return Object.keys(out).length > 0 ? out : undefined;
}

function mergeInputPricingSegment(
  target: Record<string, any>,
  segmentName: 'image' | 'video',
  current: Record<string, any> | null | undefined,
  managedKeys: string[]
) {
  const preservedSegment = cloneJsonRecord(target[segmentName]);
  const hasCurrentManagedValue = managedKeys.some((key) => {
    const value = current?.[key];
    return key === 'unitPrice' ? value != null : value != null && Number(value) > 0;
  });
  if (!hasCurrentManagedValue) {
    const remaining = preservedSegment || {};
    managedKeys.forEach((key) => delete remaining[key]);
    // 清空已知字段只删除受管键；段内仍有供应商扩展时继续无损保留。
    if (Object.keys(remaining).length > 0) target[segmentName] = remaining;
    else delete target[segmentName];
    return;
  }
  const next = preservedSegment || {};
  managedKeys.forEach((key) => delete next[key]);
  if (current?.unitPrice != null) next.unitPrice = Number(current.unitPrice);
  if (current?.maxCount != null && Number(current.maxCount) > 0) next.maxCount = Number(current.maxCount);
  if (current?.maxSeconds != null && Number(current.maxSeconds) > 0) next.maxSeconds = Number(current.maxSeconds);
  target[segmentName] = next;
}

/** skuEditData + modelForm → billingRuleJson 字符串 */
export function buildBillingRuleJson(
  form: Model,
  skuData: SkuEditData,
  isTokenBilling: boolean
): string {
  const isText = form.modelType === 'text';
  const chargeType = isText ? 'TEXT' : (form.modelType || '').toUpperCase();
  const meterType = form.meterType || inferMeterType(form.modelType);
  const skus = skuData.skuList.map((s) => {
    const sku: any = cloneJsonRecord(s.preservedSku) || {};
    sku.skuCode = s.skuCode;
    sku.skuName = s.skuName;
    if (s.meterType) sku.meterType = s.meterType;
    else delete sku.meterType;
    sku.enabled = s.enabled;
    sku.priority = s.priority;
    sku.match = JSON.parse(JSON.stringify(s.match || {}));
    sku.remark = s.remark || '';
    [
      'price', 'pricePerSecond', 'pricePerChar', 'inputPricePerMillion',
      'outputPricePerMillion', 'cachedInputPricePerMillion',
      'cacheWritePricePerMillion', 'reasoningPricePerMillion'
    ].forEach((key) => delete sku[key]);
    const skuMeterType = s.meterType || (isTokenBilling ? 'TOKEN' : meterType);
    if (skuMeterType === 'TOKEN') {
      sku.inputPricePerMillion = Number(s.inputPricePerMillion) || 0;
      sku.outputPricePerMillion = Number(s.outputPricePerMillion) || 0;
      if (s.cachedInputPricePerMillion != null) sku.cachedInputPricePerMillion = Number(s.cachedInputPricePerMillion);
      if (s.cacheWritePricePerMillion != null) sku.cacheWritePricePerMillion = Number(s.cacheWritePricePerMillion);
      if (s.reasoningPricePerMillion != null) sku.reasoningPricePerMillion = Number(s.reasoningPricePerMillion);
    } else {
      sku.price = Number(s.price) || 0;
      // 按秒计费：每秒单价必须随 SKU 落库，否则结算兜底会用 price/durationMax 反推出错价
      if (skuMeterType === 'PER_SECOND' && s.pricePerSecond != null && Number(s.pricePerSecond) > 0) {
        sku.pricePerSecond = Number(s.pricePerSecond);
      }
      // 按字符计费（TTS）：每字符单价
      if (skuMeterType === 'PER_CHAR' && s.pricePerChar != null && Number(s.pricePerChar) > 0) {
        sku.pricePerChar = Number(s.pricePerChar);
      }
    }
    // SKU 级输入媒体计费覆盖（如视频输入单价随分辨率变化）
    const skuInput = mergeInputPricing(sku.inputPricing, s.inputPricing);
    if (skuInput) sku.inputPricing = skuInput;
    else delete sku.inputPricing;
    return sku;
  });
  const preservedRule = cloneJsonRecord(skuData.preservedBillingRule);
  const rule: any = preservedRule || {
    preHold: true,
    matchStrategy: 'FIRST_HIT',
    params: [],
    settleRule: {
      settleMode: 'REFUND_ONLY',
      usageSource: 'PROVIDER_USAGE',
      allowRefund: true
    }
  };
  rule.mode = 'SKU';
  rule.meterType = meterType;
  rule.chargeType = chargeType;
  rule.skus = skus;
  const settleRule = cloneJsonRecord(rule.settleRule) || {};
  settleRule.charToTokenRatio = skuData.charToTokenRatio || 2;
  settleRule.allowExtraCharge = skuData.allowExtraCharge === true;
  settleRule.usagePricingMode = skuData.usagePricingMode || 'AGGREGATE';
  rule.settleRule = settleRule;
  // 规则级输入媒体计费（图片/视频输入附加费默认值）
  const ruleInput = mergeInputPricing(rule.inputPricing, skuData.inputPricing);
  if (ruleInput) rule.inputPricing = ruleInput;
  else delete rule.inputPricing;
  return JSON.stringify(rule);
}

/**
 * 从 scheduleStrategyJson 中解析并发上限 maxConcurrency（供应商行与模型行共用同一键名）。
 * 约定：<=0 / 不配 / 字段为空 → 不限制（返回 null）。
 */
export function parseMaxConcurrency(jsonStr?: string | null): number | null {
  if (!jsonStr) return null;
  try {
    const obj = JSON.parse(jsonStr);
    const v = Number(obj?.maxConcurrency);
    return Number.isFinite(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

/**
 * 把 maxConcurrency 合并写入 scheduleStrategyJson，保留原 JSON 里的其它键。
 * value 为 null / undefined / <=0 时表示"不限制"，会移除该键。
 * 返回的字符串：若合并后为空对象则返回 null。
 */
export function mergeMaxConcurrency(
  jsonStr: string | null | undefined,
  value: number | null | undefined
): string | null {
  let obj: Record<string, any> = {};
  if (jsonStr && String(jsonStr).trim()) {
    try {
      const parsed = JSON.parse(jsonStr);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        obj = parsed;
      }
    } catch {
      /* 原值非法 JSON：丢弃重建，避免污染 */
    }
  }
  if (value != null && Number(value) > 0) {
    obj.maxConcurrency = Number(value);
  } else {
    delete obj.maxConcurrency;
  }
  // 清除已废弃的并发键：并发上限统一收口到 maxConcurrency，
  // 若不删则历史 JSON 里的旧键会被原样回写，留下"看着有配置但没人读"的死数据
  delete obj.providerConcurrency;
  delete obj.modelConcurrency;
  return Object.keys(obj).length ? JSON.stringify(obj) : null;
}

/** 新建一条空 SKU */
export function makeEmptySku(isTokenBilling: boolean, priority: number): Sku {
  return {
    skuCode: '',
    skuName: '',
    enabled: true,
    priority,
    match: isTokenBilling ? { inputTokensMin: 0, inputTokensMax: 32000 } : {},
    price: null,
    pricePerSecond: null,
    pricePerChar: null,
    inputPricePerMillion: null,
    outputPricePerMillion: null,
    cachedInputPricePerMillion: null,
    cacheWritePricePerMillion: null,
    reasoningPricePerMillion: null,
    remark: ''
  };
}
