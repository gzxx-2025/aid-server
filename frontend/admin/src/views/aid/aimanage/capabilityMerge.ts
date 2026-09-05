type JsonObject = Record<string, any>;

const MANAGED_TOP_LEVEL_FIELDS = new Set([
  'sizeOptions', 'defaultSize', 'aspectRatioOptions', 'defaultAspectRatio',
  'durationOptions', 'defaultDurationSeconds', 'allowCustomWH',
  'maxReferenceImages', 'minReferenceImages',
  'supportsBase64Image', 'base64ImageEnabled',
  'supportsAudio', 'upstreamAudioField', 'upstreamResolutionMap', 'supportsReferenceAudio',
  'referenceAudioRequiresGeneratedAudio', 'maxReferenceAudios',
  'supportsReasoning', 'supportsReasoningDisable', 'returnsReasoningContent',
  'supportsReasoningBudget', 'defaultReasoningEnabled', 'reasoningApiStyle',
  'outputTokenApiField', 'allowedReasoningLevels',
  'defaultReasoningLevel', 'defaultReasoningBudgetTokens', 'maxReasoningBudgetTokens', 'inputModalities', 'outputModalities',
  'supportsImageInput', 'supportsVideoInput', 'supportsAudioInput', 'supportsDocumentInput',
  'maxInputImages', 'maxInputVideos', 'maxInputAudios', 'maxInputDocuments',
  'inputImageFormats', 'inputVideoFormats', 'inputAudioFormats', 'inputDocumentFormats',
  'maxInputImageFileSizeMb', 'maxInputVideoFileSizeMb', 'maxInputAudioFileSizeMb',
  'maxInputDocumentFileSizeMb', 'maxInputVideoDurationSeconds', 'maxInputAudioDurationSeconds',
  'maxInputDocumentPages', 'contextWindowTokens', 'maxOutputTokens', 'supportsReasoningContent',
  'referenceAudioMinDurationSeconds', 'referenceAudioMaxDurationSeconds',
  'referenceAudioMaxTotalDurationSeconds', 'referenceAudioFormats', 'sceneRules'
]);

const MANAGED_SCENE_BRANCHES = new Set([
  'textOnly', 'textToImage', 'imageToImage', 'textToVideo', 'imageToVideo'
]);

const isObject = (value: unknown): value is JsonObject =>
  !!value && typeof value === 'object' && !Array.isArray(value);

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));

/** 解析新旧 capabilityJson 的输入模态；旧数据缺少数组时兼容四个布尔能力位。 */
export function parseInputModalities(raw: unknown): string[] {
  const source = isObject(raw) ? raw : {};
  const values = Array.isArray(source.inputModalities)
    ? source.inputModalities
    : [
        'TEXT',
        source.supportsImageInput === true ? 'IMAGE' : '',
        source.supportsVideoInput === true ? 'VIDEO' : '',
        source.supportsAudioInput === true ? 'AUDIO' : '',
        source.supportsDocumentInput === true ? 'DOCUMENT' : ''
      ];
  return Array.from(new Set([
    'TEXT',
    ...values.map((value) => String(value).trim().toUpperCase()).filter(Boolean)
  ]));
}

/** 将编辑器的输入模态统一转换为 capabilityJson 的受管能力位。 */
export function buildInputSupportFields(inputModalities: unknown): JsonObject {
  const normalized = parseInputModalities({ inputModalities });
  return {
    inputModalities: normalized,
    supportsImageInput: normalized.includes('IMAGE'),
    supportsVideoInput: normalized.includes('VIDEO'),
    supportsAudioInput: normalized.includes('AUDIO'),
    supportsDocumentInput: normalized.includes('DOCUMENT')
  };
}

/** 提取图形编辑器不管理的能力字段，供保存时无损合并。 */
export function extractUnmanagedCapability(raw: unknown): JsonObject {
  if (!isObject(raw)) return {};
  const preserved: JsonObject = {};
  for (const [key, value] of Object.entries(raw)) {
    if (!MANAGED_TOP_LEVEL_FIELDS.has(key)) preserved[key] = clone(value);
  }
  if (isObject(raw.sceneRules)) {
    const unknownBranches: JsonObject = {};
    for (const [key, value] of Object.entries(raw.sceneRules)) {
      if (!MANAGED_SCENE_BRANCHES.has(key)) unknownBranches[key] = clone(value);
    }
    if (Object.keys(unknownBranches).length) preserved.sceneRules = unknownBranches;
  }
  return preserved;
}

/** 未管理字段原样保留，图形编辑器产出的受管字段始终覆盖；sceneRules 按分支合并。 */
export function mergeManagedCapability(preserved: unknown, managed: JsonObject): JsonObject {
  const base = isObject(preserved) ? clone(preserved) : {};
  const preservedSceneRules = isObject(base.sceneRules) ? base.sceneRules : {};
  const managedSceneRules = isObject(managed.sceneRules) ? managed.sceneRules : {};
  const result = { ...base, ...managed };
  const sceneRules = { ...preservedSceneRules, ...managedSceneRules };
  if (Object.keys(sceneRules).length) result.sceneRules = sceneRules;
  else delete result.sceneRules;
  return result;
}
