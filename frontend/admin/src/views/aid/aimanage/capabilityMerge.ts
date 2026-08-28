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
  'referenceAudioMinDurationSeconds', 'referenceAudioMaxDurationSeconds',
  'referenceAudioMaxTotalDurationSeconds', 'referenceAudioFormats', 'sceneRules'
]);

const MANAGED_SCENE_BRANCHES = new Set([
  'textOnly', 'textToImage', 'imageToImage', 'textToVideo', 'imageToVideo'
]);

const isObject = (value: unknown): value is JsonObject =>
  !!value && typeof value === 'object' && !Array.isArray(value);

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));

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
