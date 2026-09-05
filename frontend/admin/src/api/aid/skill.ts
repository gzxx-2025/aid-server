import { request } from '@/utils/request';
import type { ApiResponse } from '@/types/common';

export interface SkillRequestOptions {
  /** 只绕过已完成结果的短时缓存；同业务键的进行中请求仍会合并。 */
  force?: boolean;
}

function createRequestCoordinator(ttlMs: number) {
  const pending = new Map<string, { promise: Promise<unknown>; revision: number }>();
  const completed = new Map<string, { expiresAt: number; value: unknown }>();
  const revisions = new Map<string, number>();

  const prune = () => {
    const now = Date.now();
    for (const [key, cached] of completed) {
      if (cached.expiresAt <= now) {
        completed.delete(key);
        if (!pending.has(key)) revisions.delete(key);
      }
    }
    while (completed.size > 200) {
      const oldestKey = completed.keys().next().value as string | undefined;
      if (oldestKey == null) break;
      completed.delete(oldestKey);
      if (!pending.has(oldestKey)) revisions.delete(oldestKey);
    }
  };

  const run = <T>(key: string, operation: () => Promise<T>, options?: SkillRequestOptions): Promise<T> => {
    prune();
    const inFlight = pending.get(key);
    if (inFlight) {
      return inFlight.promise as Promise<T>;
    }

    const cached = completed.get(key);
    if (!options?.force && cached && cached.expiresAt > Date.now()) {
      return Promise.resolve(cached.value as T);
    }
    if (cached) completed.delete(key);

    const revision = revisions.get(key) || 0;
    const promise = operation();
    pending.set(key, { promise, revision });
    promise.then((value) => {
      if ((revisions.get(key) || 0) === revision) {
        completed.set(key, { expiresAt: Date.now() + ttlMs, value });
      }
    }, () => {
      completed.delete(key);
    });
    const clearPending = () => {
      if (pending.get(key)?.promise === promise) {
        pending.delete(key);
        if (!completed.has(key)) revisions.delete(key);
      }
    };
    promise.then(clearPending, clearPending);
    return promise;
  };

  const invalidate = (predicate?: (key: string) => boolean) => {
    const keys = new Set([...completed.keys(), ...pending.keys()]);
    for (const key of keys) {
      if (!predicate || predicate(key)) {
        completed.delete(key);
        if (pending.has(key)) revisions.set(key, (revisions.get(key) || 0) + 1);
        else revisions.delete(key);
      }
    }
  };

  const settle = async (predicate?: (key: string) => boolean) => {
    const active = [...pending.entries()]
      .filter(([key]) => !predicate || predicate(key))
      .map(([, slot]) => slot.promise);
    if (active.length) await Promise.allSettled(active);
  };

  return { run, invalidate, settle };
}

function normalizedJson(value: unknown) {
  return JSON.stringify(value);
}

const readRequests = createRequestCoordinator(800);
const mutationInflight = new Map<string, { fingerprint: string; promise: Promise<ApiResponse<unknown>> }>();

function skillReadPredicate(skillId?: number) {
  return (key: string) => skillId == null || key.startsWith('skills:')
    || key.startsWith('dependencies:')
    || key.startsWith('version:')
    || key === 'models'
    || key.includes(`:skill:${skillId}:`);
}

function invalidateSkillReads(skillId?: number) {
  readRequests.invalidate(skillReadPredicate(skillId));
}

function coordinatedMutation<T>(
  resourceKey: string,
  fingerprint: string,
  operation: () => Promise<ApiResponse<T>>,
  skillId?: number
) {
  const existing = mutationInflight.get(resourceKey);
  if (existing) {
    return existing.fingerprint === fingerprint
      ? existing.promise as Promise<ApiResponse<T>>
      : Promise.reject(new Error('同一 Skill 正在执行其他修改，请稍后重试'));
  }
  const pending = operation().then(async (response) => {
    await readRequests.settle(skillReadPredicate(skillId));
    invalidateSkillReads(skillId);
    return response;
  }).finally(() => {
    if (mutationInflight.get(resourceKey)?.promise === pending) mutationInflight.delete(resourceKey);
  });
  mutationInflight.set(resourceKey, { fingerprint, promise: pending });
  return pending;
}

export interface SkillListQuery {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  status?: '0' | '1';
}

export interface AdminSkillSummary {
  id: number;
  skillCode: string;
  name: string;
  description?: string;
  capabilityDescription?: string;
  iconUrl?: string;
  ownerType: string;
  visibility: string;
  invocationScope: string;
  currentVersionId?: number | null;
  status: '0' | '1';
  delFlag: '0' | '1';
  modelCode: string;
  reasoningPolicy: 'DISABLED' | 'OPTIONAL' | 'REQUIRED';
  updateTime?: string;
}

export interface SkillIdentitySavePayload {
  id: number;
  name: string;
  description?: string;
  capabilityDescription?: string;
  iconUrl?: string;
  status: '0' | '1';
}

export interface SkillRunItem {
  id: number;
  userId: number;
  skillId: number;
  skillVersionId: number;
  projectId: number;
  episodeId: number;
  skillConfigHash: string;
  modelCode: string;
  invokeSource: string;
  clientRequestId: string;
  generation: number;
  status: string;
  stage?: string;
  actionMode?: string;
  qualityMode?: string;
  clientRequestDigest?: string;
  executionSnapshotDigest?: string;
  resolvedConfigDigest?: string;
  rootRunId?: number;
  parentRunId?: number;
  inputJson?: string;
  outputJson?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  tasks: SkillRunTaskItem[];
}

export interface SkillRunTaskItem {
  stepId: number;
  stepSeq: number;
  stepKey: string;
  stepExecutionId: string;
  skillId: number;
  skillVersionId: number;
  actionMode: string;
  workflowAttempt: number;
  orchestrationStatus: string;
  mediaTaskId?: number;
  mediaStatus?: string;
  billingStatus?: string;
  /** Decimal string returned by the audit API; do not coerce to IEEE-754 number. */
  actualCost?: string;
}

export type SkillRunSummary = Omit<SkillRunItem,
  'clientRequestDigest' | 'executionSnapshotDigest' | 'resolvedConfigDigest' | 'rootRunId'
  | 'parentRunId' | 'inputJson' | 'outputJson' | 'errorMessage' | 'tasks'> & {
  durationMillis?: number;
};

export interface SkillRunQuery {
  pageNum: number;
  pageSize: number;
  skillId?: number;
  userId?: number;
  status?: string;
}

export interface SkillTextModelOption {
  modelCode: string;
  modelName?: string;
  capabilityJson?: string;
  capability?: Record<string, unknown>;
  modelLogo?: string;
  providerName?: string;
  providerLogo?: string;
  billing?: Record<string, unknown>;
  status?: string;
  delFlag?: string;
  available: boolean;
  unavailableReason?: string;
}

export interface SkillDraftResource {
  resourceKey: string;
  resourceType: string;
  mimeType: string;
  content: string;
  routeJson: string;
}

export interface SkillDraftRelation {
  relationKey: string;
  childSkillId: number;
  childVersionId: number;
  requiredFlag: boolean;
}

export interface SkillPackagePayload {
  skillId: number;
  baseVersionId?: number;
  modelCode: string;
  defaultModelCode: string;
  selectableModelCodes: string[];
  systemPrompt: string;
  inputSchemaJson: string;
  outputSchemaJson: string;
  definitionJson?: string;
  maxOutputTokens: number;
  contextWindowTokens: number;
  safetyMarginTokens: number;
  resources: SkillDraftResource[];
  relations: SkillDraftRelation[];
}

export interface SkillDraftDetail extends SkillPackagePayload {
  draftId?: number;
  baseVersionCode?: string;
  skillCode: string;
  executorType: string;
  invocationScope: string;
  draftDigest?: string;
  updateTime?: string;
}

export interface SkillVersionSummary {
  id: number;
  skillId: number;
  versionCode: string;
  publishStatus: string;
  packageDigest: string;
  status: '0' | '1';
  current: boolean;
  createBy?: string;
  createTime?: string;
}

export interface SkillVersionListQuery {
  skillId: number;
  pageNum: number;
  pageSize: number;
}

export interface SkillVersionResource extends SkillDraftResource {
  id: number;
  objectKey: string;
  contentDigest: string;
  sizeBytes: number;
}

export interface SkillVersionRelation extends SkillDraftRelation {
  id: number;
  childSkillCode: string;
  childVersionCode: string;
}

export interface SkillVersionDetail {
  id: number;
  skillId: number;
  skillCode: string;
  versionCode: string;
  visibility: string;
  invocationScope: string;
  publishStatus: string;
  executorType: string;
  modelCode: string;
  modelConfigJson?: string;
  defaultModelCode: string;
  selectableModelCodes: string[];
  packageDigest: string;
  manifestJson: string;
  inputSchemaJson: string;
  outputSchemaJson: string;
  systemPrompt: string;
  definitionJson?: string;
  maxOutputTokens: number;
  contextWindowTokens: number;
  safetyMarginTokens: number;
  status: '0' | '1';
  current: boolean;
  createBy?: string;
  createTime?: string;
  resources: SkillVersionResource[];
  relations: SkillVersionRelation[];
}

export interface SkillValidationIssue {
  field: string;
  message: string;
}

export interface SkillValidationResult {
  valid: boolean;
  draftDigest?: string;
  errors: SkillValidationIssue[];
  warnings: SkillValidationIssue[];
}

export interface SkillDependencyVersionOption {
  id: number;
  versionCode: string;
  current: boolean;
}

export interface SkillDependencyOption {
  skillId: number;
  skillCode: string;
  name: string;
  currentVersionId?: number | null;
}

export interface SkillDependencyListQuery {
  skillId: number;
  pageNum: number;
  pageSize: number;
  keyword?: string;
}

export interface SkillDependencyVersionListQuery {
  parentSkillId: number;
  childSkillId: number;
  pageNum: number;
  pageSize: number;
  keyword?: string;
}

export interface SkillDependencyLabel {
  childSkillId: number;
  childSkillCode: string;
  childSkillName: string;
  childVersionId: number;
  childVersionCode: string;
  current: boolean;
}

export interface SkillDraftSavePayload extends SkillPackagePayload {
  draftId?: number;
  draftDigest?: string;
}

export function listSkills(data: SkillListQuery, options?: SkillRequestOptions) {
  const requestKey = `skills:${JSON.stringify([
    data.pageNum,
    data.pageSize,
    data.keyword || '',
    data.status || ''
  ])}`;
  return readRequests.run(requestKey,
    () => request<AdminSkillSummary[]>({ url: '/aid/skill/list', method: 'post', data }), options);
}

export function editSkillIdentity(data: SkillIdentitySavePayload) {
  return coordinatedMutation(`skill:${data.id}`, `identity-edit:${normalizedJson(data)}`,
    () => request({ url: '/aid/skill/identity/edit', method: 'post', data }), data.id);
}

export function updateSkillStatus(id: number, status: '0' | '1') {
  return coordinatedMutation(`skill:${id}`, `status:${status}`,
    () => request({ url: '/aid/skill/status', method: 'post', data: { id, status } }), id);
}

export function removeSkill(id: number) {
  return coordinatedMutation(`skill:${id}`, 'remove',
    () => request({ url: '/aid/skill/remove', method: 'post', data: { id } }), id);
}

export function restoreSkill(id: number) {
  return coordinatedMutation(`skill:${id}`, 'restore',
    () => request({ url: '/aid/skill/restore', method: 'post', data: { id } }), id);
}

export function listSkillTextModels(options?: SkillRequestOptions) {
  return readRequests.run('models',
    () => request<SkillTextModelOption[]>({ url: '/aid/skill/model/options', method: 'post', data: {} }), options);
}

export function listSkillRuns(data: SkillRunQuery, options?: SkillRequestOptions) {
  const requestKey = `runs:skill:${data.skillId ?? 'all'}:${JSON.stringify([
    data.pageNum, data.pageSize, data.userId ?? '', data.status || ''
  ])}`;
  return readRequests.run(requestKey,
    () => request<SkillRunSummary[]>({ url: '/aid/skill/run/list', method: 'post', data }), options);
}

export function getSkillRun(id: number, options?: SkillRequestOptions) {
  return readRequests.run(`run:${id}`,
    () => request<SkillRunItem>({ url: '/aid/skill/run/detail', method: 'post', data: { id } }), options);
}

export function listSkillVersions(data: SkillVersionListQuery, options?: SkillRequestOptions) {
  const requestKey = `versions:skill:${data.skillId}:page:${data.pageNum}:size:${data.pageSize}`;
  return readRequests.run(requestKey,
    () => request<SkillVersionSummary[]>({
      url: '/aid/skill/version/list', method: 'post', data
    }).then((response) => response as ApiResponse<SkillVersionSummary[]> & {
      currentVersionId: number | null;
    }), options);
}

export function getSkillVersion(id: number, options?: SkillRequestOptions) {
  return readRequests.run(`version:${id}`,
    () => request<SkillVersionDetail>({
      url: '/aid/skill/version/detail', method: 'post', data: { id }
    }), options);
}

export function getSkillDraft(skillId: number, baseVersionId?: number, options?: SkillRequestOptions) {
  return readRequests.run(`draft:skill:${skillId}:base:${baseVersionId ?? 'current'}`,
    () => request<SkillDraftDetail>({
      url: '/aid/skill/draft/detail', method: 'post', data: { skillId, baseVersionId }
    }), options);
}

export function listSkillDependencyOptions(data: SkillDependencyListQuery, options?: SkillRequestOptions) {
  const keyword = data.keyword?.trim() || '';
  const normalized = { ...data, keyword: keyword || undefined };
  const requestKey = `dependencies:skills:${normalizedJson(normalized)}`;
  return readRequests.run(requestKey,
    () => request<SkillDependencyOption[]>({
      url: '/aid/skill/dependency/options', method: 'post', data: normalized
    }), options);
}

export function listSkillDependencyVersionOptions(
  data: SkillDependencyVersionListQuery, options?: SkillRequestOptions
) {
  const keyword = data.keyword?.trim() || '';
  const normalized = { ...data, keyword: keyword || undefined };
  const requestKey = `dependencies:versions:${normalizedJson(normalized)}`;
  return readRequests.run(requestKey,
    () => request<SkillDependencyVersionOption[]>({
      url: '/aid/skill/dependency/version/options', method: 'post',
      data: normalized
    }), options);
}

export function getSkillDependencyLabels(
  parentSkillId: number, versionIds: number[], options?: SkillRequestOptions
) {
  const normalizedIds = [...new Set(versionIds)].sort((left, right) => left - right).slice(0, 16);
  const data = { parentSkillId, versionIds: normalizedIds };
  return readRequests.run(`dependencies:labels:${normalizedJson(data)}`,
    () => request<SkillDependencyLabel[]>({
      url: '/aid/skill/dependency/labels', method: 'post', data
    }), options);
}

export function saveSkillDraft(data: SkillDraftSavePayload) {
  const resourceKey = data.draftId ? `draft:${data.draftId}` : `skill:${data.skillId}`;
  return coordinatedMutation(resourceKey, `draft-save:${normalizedJson(data)}`,
    () => request<SkillDraftDetail>({ url: '/aid/skill/draft/save', method: 'post', data }), data.skillId);
}

export function discardSkillDraft(skillId: number, draftId: number, draftDigest: string) {
  const data = { draftId, draftDigest };
  return coordinatedMutation(`draft:${draftId}`, `draft-discard:${draftDigest}`,
    () => request({ url: '/aid/skill/draft/discard', method: 'post', data }), skillId);
}

export function validateSkillDraft(data: SkillPackagePayload, options?: SkillRequestOptions) {
  return readRequests.run(`draft-validate:skill:${data.skillId}:${normalizedJson(data)}`,
    () => request<SkillValidationResult>({
      url: '/aid/skill/draft/validate', method: 'post', data
    }), options);
}

export function publishSkillDraft(draftId: number, draftDigest: string, versionCode: string) {
  const data = { draftId, draftDigest, versionCode };
  return coordinatedMutation(`draft:${draftId}`, `draft-publish:${draftDigest}:${versionCode}`,
    () => request<SkillVersionDetail>({ url: '/aid/skill/draft/publish', method: 'post', data }));
}

export function activateSkillVersion(
  skillId: number, versionId: number, expectedCurrentVersionId: number | null
) {
  const data = { skillId, versionId, expectedCurrentVersionId };
  return coordinatedMutation(`skill:${skillId}`, `version-activate:${normalizedJson(data)}`,
    () => request({
      url: '/aid/skill/version/activate', method: 'post', data
    }), skillId);
}
