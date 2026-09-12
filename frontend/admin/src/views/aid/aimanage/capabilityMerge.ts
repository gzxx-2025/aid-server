type JsonObject = Record<string, any>;

const MANAGED_TOP_LEVEL_FIELDS = new Set([
  'sizeOptions', 'defaultSize', 'aspectRatioOptions', 'defaultAspectRatio',
  'durationOptions', 'defaultDurationSeconds', 'allowCustomWH',
  'outputFormatOptions', 'defaultOutputFormat', 'outputFpsOptions', 'defaultOutputFps',
  'supportsBgm', 'supportsVoiceId', 'audioTypes', 'supportsVoiceControl',
  'audioModeOptions', 'defaultAudio',
  'audioOperation', 'ttsTextRequired', 'ttsVoiceRequired', 'builtInVoiceOptions',
  'audioFormatOptions', 'defaultAudioFormat', 'audioSampleRateOptions', 'defaultAudioSampleRate',
  'supportsAudioStreaming', 'supportsTimestamp',
  'speechRateMin', 'speechRateMax', 'loudnessRateMin', 'loudnessRateMax', 'pitchMin', 'pitchMax',
  'emotionOptions', 'supportsEmotionScale', 'voiceSampleRequired',
  'voiceSampleFormats', 'voiceSampleMaxFileSizeMb',
  'supportsElements', 'maxElements', 'elementTypeRequired', 'klingScenario', 'videoScenario', 'seedanceTaskTypeOptions',
  'durationMin', 'durationMax', 'maxPromptCharacters', 'maxPromptCharactersCjk', 'strictSceneRules', 'allowedScenes',
  'minOutputPixels', 'maxOutputPixels', 'minOutputAspectRatio', 'maxOutputAspectRatio',
  'maxReferenceImages', 'minReferenceImages',
  'referenceImageFormats', 'referenceImageMaxFileSizeMb',
  'referenceImageMinDimensionPixels', 'referenceImageMaxDimensionPixels',
  'referenceImageMinPixels', 'referenceImageMaxPixels',
  'referenceImageMinAspectRatio', 'referenceImageMaxAspectRatio',
  'supportsBase64Image', 'base64ImageEnabled',
  'supportsAudio', 'upstreamAudioField', 'upstreamResolutionMap', 'supportsReferenceAudio',
  'referenceAudioRequiresGeneratedAudio', 'referenceAudioRequiresVisualInput',
  'minReferenceAudios', 'maxReferenceAudios',
  'supportsReasoning', 'supportsReasoningDisable', 'returnsReasoningContent',
  'supportsReasoningBudget', 'defaultReasoningEnabled', 'reasoningApiStyle',
  'outputTokenApiField', 'allowedReasoningLevels',
  'supportsStreaming', 'supportsToolCalling', 'supportsChatPrefix', 'supportsStructuredOutput',
  'supportsContextCaching', 'supportsBuiltinTools',
  'defaultReasoningLevel', 'defaultReasoningBudgetTokens', 'maxReasoningBudgetTokens', 'inputModalities', 'outputModalities',
  'supportsTextInput', 'supportsImageInput', 'supportsVideoInput', 'supportsAudioInput', 'supportsDocumentInput',
  'maxInputImages', 'maxInputVideos', 'maxInputAudios', 'maxInputDocuments',
  'inputImageFormats', 'inputVideoFormats', 'inputAudioFormats', 'inputDocumentFormats',
  'maxInputImageFileSizeMb', 'maxInputVideoFileSizeMb', 'maxInputAudioFileSizeMb',
  'maxInputMediaTotalFileSizeMb', 'inputMediaMaxUrlLength', 'inputMediaAllowedMessageRoles',
  'maxInputDocumentFileSizeMb', 'minInputVideoDurationSeconds', 'maxInputVideoDurationSeconds',
  'minInputAudioDurationSeconds', 'maxInputAudioDurationSeconds',
  'maxInputVideoTotalDurationSeconds', 'maxInputAudioTotalDurationSeconds',
  'inputImageMinDimensionPixels', 'inputImageMaxDimensionPixels',
  'inputImageHighCountThreshold', 'inputImageHighCountMaxDimensionPixels',
  'inputImageMinPixels', 'inputImageMaxPixels',
  'inputImageMinAspectRatio', 'inputImageMaxAspectRatio',
  'inputVideoMinDimensionPixels', 'inputVideoMaxDimensionPixels',
  'inputVideoMinPixels', 'inputVideoMaxPixels',
  'inputVideoMinAspectRatio', 'inputVideoMaxAspectRatio',
  'inputVideoMinFps', 'inputVideoMaxFps',
  'maxInputDocumentPages', 'contextWindowTokens', 'maxOutputTokens', 'supportsReasoningContent',
  'referenceAudioMinDurationSeconds', 'referenceAudioMaxDurationSeconds',
  'referenceAudioMaxTotalDurationSeconds', 'referenceAudioMaxFileSizeMb', 'referenceAudioFormats',
  'minReferenceVideos', 'maxReferenceVideos', 'referenceVideoFormats',
  'referenceVideoMaxFileSizeMb', 'referenceVideoMinDurationSeconds',
  'referenceVideoMaxDurationSeconds', 'referenceVideoMaxTotalDurationSeconds',
  'referenceVideoMinDimensionPixels', 'referenceVideoMaxDimensionPixels',
  'referenceVideoMinPixels', 'referenceVideoMaxPixels',
  'referenceVideoMinAspectRatio', 'referenceVideoMaxAspectRatio',
  'referenceVideoMinFps', 'referenceVideoMaxFps', 'maxReferenceMaterials',
  'maxInputOutputVideoDurationSeconds', 'sceneRules'
]);

const MANAGED_SCENE_BRANCHES = new Set([
  'textOnly', 'textToImage', 'imageToImage', 'textToVideo', 'imageToVideo',
  'startEndToVideo', 'referenceToVideo', 'videoToVideo'
]);
const MANAGED_SCENE_FIELDS = new Set([
  'supportsAspectRatio', 'supportsSizePreset', 'supportsDuration', 'aspectRatioFollowInput',
  'requiredInputs', 'requiredAnyOf', 'allowedInputs'
]);

const isObject = (value: unknown): value is JsonObject =>
  !!value && typeof value === 'object' && !Array.isArray(value);

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));

/** 解析新旧 capabilityJson 的输入模态；旧数据缺少数组时兼容四个布尔能力位。 */
export function parseInputModalities(raw: unknown): string[] {
  const source = isObject(raw) ? raw : {};
  const explicit = Array.isArray(source.inputModalities);
  const values = explicit
    ? source.inputModalities
    : [
        source.supportsTextInput === false ? '' : 'TEXT',
        source.supportsImageInput === true ? 'IMAGE' : '',
        source.supportsVideoInput === true ? 'VIDEO' : '',
        source.supportsAudioInput === true ? 'AUDIO' : '',
        source.supportsDocumentInput === true ? 'DOCUMENT' : ''
      ];
  const normalized: string[] = Array.from(new Set<string>((values as unknown[])
    .filter((value): value is string => typeof value === 'string')
    .map((value) => value.trim().toUpperCase()).filter(Boolean)));
  return normalized.length || explicit ? normalized : ['TEXT'];
}

/** 将编辑器的输入模态统一转换为 capabilityJson 的受管能力位。 */
export function buildInputSupportFields(inputModalities: unknown): JsonObject {
  const normalized = parseInputModalities({ inputModalities });
  return {
    inputModalities: normalized,
    supportsTextInput: normalized.includes('TEXT'),
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
      if (!MANAGED_SCENE_BRANCHES.has(key)) {
        unknownBranches[key] = clone(value);
        continue;
      }
      if (!isObject(value)) continue;
      const unknownFields: JsonObject = {};
      for (const [field, fieldValue] of Object.entries(value)) {
        if (!MANAGED_SCENE_FIELDS.has(field)) unknownFields[field] = clone(fieldValue);
      }
      if (Object.keys(unknownFields).length) unknownBranches[key] = unknownFields;
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
  const sceneRules: JsonObject = { ...preservedSceneRules };
  for (const [branch, value] of Object.entries(managedSceneRules)) {
    const preservedBranch = isObject(preservedSceneRules[branch]) ? preservedSceneRules[branch] : {};
    sceneRules[branch] = isObject(value) ? { ...preservedBranch, ...value } : value;
  }
  if (Object.keys(sceneRules).length) result.sceneRules = sceneRules;
  else delete result.sceneRules;
  return result;
}
