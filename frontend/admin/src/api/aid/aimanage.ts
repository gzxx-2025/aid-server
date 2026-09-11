import { request } from '@/utils/request'
import { getToken } from '@/utils/auth'

// 查询型 POST 同样按业务键合并，强制刷新也不绕过正在进行的请求。
const operationFlights = new Map<string, Promise<any>>()
const operationResults = new Map<string, { at: number; value: any }>()
function providerOperationQuery(id: number, kind: 'balance' | 'tasks', values: Record<string, any>, force = false) {
  const entries = Object.entries(values).filter(([, value]) => value !== undefined).sort(([a], [b]) => a.localeCompare(b))
  const key = JSON.stringify([getToken(), id, kind, entries])
  const running = operationFlights.get(key)
  if (running) return running
  const cached = operationResults.get(key)
  if (!force && cached && Date.now() - cached.at < 1500) return Promise.resolve(cached.value)
  const query = Object.fromEntries(entries)
  const run = request({
    url: `/aid/aidprovider/${id}/operations/${kind}`,
    method: kind === 'balance' ? 'get' : 'post',
    ...(kind === 'balance' ? { params: query } : { data: query, headers: { repeatSubmit: false } })
  }).then((value) => {
    for (const [oldKey, item] of operationResults) if (Date.now() - item.at >= 1500) operationResults.delete(oldKey)
    if (operationResults.size >= 100) operationResults.clear()
    operationResults.set(key, { at: Date.now(), value })
    return value
  }).finally(() => operationFlights.delete(key))
  operationFlights.set(key, run)
  return run
}

// ==================== 服务商接口 ====================

export function listProvider(query) {
  return request({ url: '/aid/aidprovider/list', method: 'get', params: query })
}

export function getProvider(id) {
  return request({ url: '/aid/aidprovider/' + id, method: 'get' })
}

export function addProvider(data) {
  return request({ url: '/aid/aidprovider', method: 'post', data })
}

export function updateProvider(data) {
  return request({ url: '/aid/aidprovider', method: 'put', data })
}

/** 只切换服务商启停状态，避免触发完整配置编辑校验。 */
export function updateProviderStatus(data: { id: number; status: '0' | '1' }) {
  return request({ url: '/aid/aidprovider/status', method: 'put', data })
}

export function delProvider(id) {
  return request({ url: '/aid/aidprovider/' + id, method: 'delete' })
}

/** 服务端动态声明供应商支持的后台扩展能力。 */
export function getProviderOperationCapabilities(id: number) {
  return request({ url: `/aid/aidprovider/${id}/operations/capabilities`, method: 'get' })
}

export function getProviderBalance(id: number, params?: Record<string, any>, force = false) {
  return providerOperationQuery(id, 'balance', params || {}, force)
}

export function listProviderUpstreamTasks(id: number, data?: Record<string, any>, force = false) {
  return providerOperationQuery(id, 'tasks', data || {}, force)
}

// ==================== TokenDance 账户接口 ====================

export interface TokenDanceAuthorizationView {
  authorizationRef: string;
  providerId: number;
  mode: 'CALLBACK' | 'HEADLESS';
  status: 'PENDING' | 'EXCHANGING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';
  authorizationUrl?: string;
  credentialVersion?: number;
  failureReason?: string;
  expiresAt?: string;
  exchangedAt?: string;
}

export interface TokenDanceCredentialView {
  providerId: number;
  bound: boolean;
  credentialVersion?: number;
  credentialHint?: string;
  authorizedBy?: string;
  authorizedAt?: string;
}

export interface TokenDanceBalanceView {
  providerId: number;
  credentialVersion: number;
  balance: number;
  totalCredits: number;
  creditsUsed: number;
  unit: string;
  sourceUnit: string;
  queriedAt: string;
}

export interface TokenDancePaymentView {
  id: number;
  providerId: number;
  credentialVersion: number;
  amount: number;
  status: string;
  paymentUrl?: string;
  alipayUrl?: string;
  expiredAt?: string;
  paidAt?: string;
  lastQueryTime?: string;
  balanceRefreshStatus?: string;
  failureReason?: string;
}

export function getTokenDanceCredential(providerId: number) {
  return request<TokenDanceCredentialView>({
    url: `/aid/tokendance/providers/${providerId}/credential`, method: 'get'
  })
}

export function startTokenDanceAuthorization(providerId: number, data: { mode: 'CALLBACK' | 'HEADLESS'; keyName?: string; callbackUrl?: string }) {
  return request<TokenDanceAuthorizationView>({
    url: `/aid/tokendance/providers/${providerId}/oauth/authorizations`, method: 'post', data
  })
}

export function completeTokenDanceAuthorization(providerId: number, data: { authorizationRef: string; code: string }) {
  return request<TokenDanceAuthorizationView>({
    url: `/aid/tokendance/providers/${providerId}/oauth/authorizations/complete`, method: 'post', data
  })
}

export function getTokenDanceAuthorization(providerId: number, authorizationRef: string) {
  return request<TokenDanceAuthorizationView>({
    url: `/aid/tokendance/providers/${providerId}/oauth/authorizations/${authorizationRef}`, method: 'get'
  })
}

export function revokeTokenDanceCredential(providerId: number) {
  return request({ url: `/aid/tokendance/providers/${providerId}/credential/revoke`, method: 'post' })
}

export function getTokenDanceBalance(providerId: number, force = false) {
  return request<TokenDanceBalanceView>({
    url: `/aid/tokendance/providers/${providerId}/balance`, method: 'get', params: { force }
  })
}

export function createTokenDancePayment(providerId: number, data: { amount: number; userConfirmed: true; requestId: string }) {
  return request<TokenDancePaymentView>({
    url: `/aid/tokendance/providers/${providerId}/payments`, method: 'post', data
  })
}

export function getTokenDancePaymentStatus(providerId: number, paymentId: number) {
  return request<TokenDancePaymentView>({
    url: `/aid/tokendance/providers/${providerId}/payments/${paymentId}/status`, method: 'post'
  })
}

// ==================== TokenDance 在线模型目录 ====================

export type TokenDanceCatalogCompatibilityStatus =
  | 'AID_INCOMPATIBLE'
  | 'IMPORTABLE'
  | 'UPGRADE_REQUIRED'
  | 'PROTOCOL_UNSUPPORTED'
  | 'CAPABILITY_UNSUPPORTED'
  | 'PRICING_INCOMPLETE'
  | 'CATALOG_UNVERIFIED'
  | 'DEPRECATED'
  | 'IMPORTED'
  | 'UPDATE_AVAILABLE'

export interface TokenDanceCatalogCompatibility {
  status: TokenDanceCatalogCompatibilityStatus;
  importable: boolean;
  selectable: boolean;
  reason: string;
  minimumAppVersion: string;
  currentAppVersion: string;
  capabilityContractVersion: number;
  billingContractVersion: number;
  requiredFeatures: string[];
  missingFeatures: string[];
}

export interface TokenDanceCatalogStatus {
  catalogId: 'tokendance';
  catalogVersion: string;
  publishedAt: string;
  loadedAt: string;
  source: 'REMOTE' | 'CACHE' | 'BUNDLED';
  sourceUrl?: string;
  manifestUrl?: string;
  currentAppVersion: string;
  minimumAppVersion: string;
  modelCount: number;
  stale: boolean;
  checkedAt?: string;
  checkError?: string;
}

export interface TokenDanceCatalogArchitecture {
  modality?: string;
  input_modalities?: string[];
  output_modalities?: string[];
}

export interface TokenDanceCatalogPlan {
  rate: string | number;
  unit?: string;
  range?: Record<string, unknown>;
}

export interface TokenDanceCatalogSkuCost {
  rate: string | number;
  unit?: string;
  description?: string;
  specs?: Record<string, unknown>;
}

export interface TokenDanceCatalogCostItem {
  id: string;
  mode?: string;
  plans?: TokenDanceCatalogPlan[];
  skus?: TokenDanceCatalogSkuCost[];
}

export interface TokenDanceCapabilityEvidence {
  modelId: string;
  protocol: string;
  evidenceStatus: string;
  coverageStatus: string;
  verificationStatus: string;
  capabilityTemplate?: Record<string, unknown>;
  requirements?: Record<string, unknown>[];
  evidence?: Array<{ title: string; url: string }>;
  activationBlockers?: string[];
  autoActivationAllowed?: boolean;
}

export interface TokenDanceCatalogSummary {
  modelId: string;
  name: string;
  sourceUrl?: string;
  fetchedAt?: string;
  bestEndpointSlug?: string;
  supportedProtocols: string[];
  architecture?: TokenDanceCatalogArchitecture;
  contextLength?: number;
  description?: string;
  costItemCount: number;
  priceType?: 'model_call_cost';
  currency?: string;
  verificationStatus?: string;
  pricingComplete?: boolean;
  pricingCompleteByProtocol?: Record<string, boolean>;
  catalogVersion?: string;
  catalogSource?: 'REMOTE' | 'CACHE' | 'BUNDLED';
  compatibilityByProtocol?: Record<string, TokenDanceCatalogCompatibility>;
}

export interface TokenDanceCatalogDetail extends Omit<TokenDanceCatalogSummary, 'costItemCount'> {
  items: TokenDanceCatalogCostItem[];
  capabilityEvidence?: Record<string, TokenDanceCapabilityEvidence>;
}

export interface TokenDanceCatalogPreview {
  modelId: string;
  protocol: string;
  localModelCode: string;
  capability: Record<string, unknown>;
  billingRule: Record<string, unknown>;
  pricingComplete: boolean;
  warnings: string[];
  verificationStatus: string;
  importAction: 'CREATE_DISABLED' | 'PRESERVE_EXISTING';
  existingModelId?: number;
  configVersion?: number;
  capabilityChanged?: boolean;
  billingChanged?: boolean;
  canRepairEmptyPricing?: boolean;
  capabilityEvidence?: TokenDanceCapabilityEvidence;
  catalogVersion?: string;
  compatibility?: TokenDanceCatalogCompatibility;
}

export interface TokenDanceCatalogSelection {
  modelId: string;
  protocol: string;
}

export interface TokenDanceCatalogImportResult extends TokenDanceCatalogSelection {
  localModelId: number;
  status: 'CREATED_DISABLED' | 'UNCHANGED';
}

export function listTokenDanceCatalog(providerId: number, keyword?: string) {
  return request<TokenDanceCatalogSummary[]>({
    url: '/aid/tokendance/catalog', method: 'get', params: { providerId, ...(keyword ? { keyword } : {}) }
  })
}

export function getTokenDanceCatalogStatus() {
  return request<TokenDanceCatalogStatus>({ url: '/aid/tokendance/catalog/status', method: 'get' })
}

export function refreshTokenDanceCatalog() {
  return request<TokenDanceCatalogStatus>({ url: '/aid/tokendance/catalog/refresh', method: 'post' })
}

export function getTokenDanceCatalogDetail(modelId: string) {
  return request<TokenDanceCatalogDetail>({
    url: `/aid/tokendance/catalog/${encodeURIComponent(modelId)}`, method: 'get'
  })
}

export function previewTokenDanceCatalogModel(providerId: number, modelId: string, protocol: string) {
  return request<TokenDanceCatalogPreview>({
    url: `/aid/tokendance/catalog/${encodeURIComponent(modelId)}/preview`,
    method: 'get', params: { providerId, protocol }
  })
}

export function importTokenDanceCatalogModels(
  providerId: number,
  catalogVersion: string,
  selections: TokenDanceCatalogSelection[]
) {
  return request<TokenDanceCatalogImportResult[]>({
    url: '/aid/tokendance/catalog/import', method: 'post', data: { providerId, catalogVersion, selections }
  })
}

export function repairTokenDanceEmptyPricing(providerId: number, modelId: string, protocol: string, configVersion: number) {
  return request({
    url: `/aid/tokendance/catalog/${encodeURIComponent(modelId)}/repair-empty-pricing`,
    method: 'post', data: { providerId, protocol, configVersion }
  })
}

// ==================== 模型接口 ====================

export function listModel(query) {
  return request({ url: '/aid/aidmodel/list', method: 'get', params: query })
}

export interface ModelPoolBindingPool {
  id: number;
  funcName: string;
  funcCode: string;
  modelType: string;
  generateMode?: string | null;
  status: string;
  configurationValid: boolean;
  modelIds: number[];
}

export interface ModelPoolBindingModel {
  id: number;
  modelCode: string;
  modelName: string;
  modelType: string;
  generateMode?: string | null;
  status: string;
  poolIds: number[];
}

export interface ModelPoolBindingSnapshot {
  pools: ModelPoolBindingPool[];
  models: ModelPoolBindingModel[];
}

export interface ModelPoolBindingChangeResult {
  requestedModelCount: number;
  requestedPoolCount: number;
  changedPoolCount: number;
  changedRelationCount: number;
  unchangedRelationCount: number;
  snapshot: ModelPoolBindingSnapshot;
}

const poolBindingRequests = new Map<string, Promise<any>>();

function normalizeRequestIds(ids: number[]) {
  return Array.from(new Set(ids.filter((id) => Number.isInteger(id) && id > 0))).sort((a, b) => a - b);
}

function mergePoolBindingRequest<T>(key: string, task: () => Promise<T>): Promise<T> {
  const current = poolBindingRequests.get(key);
  if (current) return current as Promise<T>;
  const pending = task();
  poolBindingRequests.set(key, pending);
  void pending.finally(() => {
    if (poolBindingRequests.get(key) === pending) poolBindingRequests.delete(key);
  }).catch(() => undefined);
  return pending;
}

/** 查询模型与模型池关系；相同查询进行中时复用同一请求。 */
export function getModelPoolBindings(modelIds: number[] = []) {
  const normalized = normalizeRequestIds(modelIds);
  const key = `query:${normalized.join(',') || 'all'}`;
  return mergePoolBindingRequest(key, () => request<ModelPoolBindingSnapshot>({
    url: '/aid/aidmodel/pool-bindings/query', method: 'post', data: { modelIds: normalized }
  }));
}

function changeModelPoolBindings(operation: 'bind' | 'unbind', modelIds: number[], poolIds: number[]) {
  const normalizedModels = normalizeRequestIds(modelIds);
  const normalizedPools = normalizeRequestIds(poolIds);
  const key = `${operation}:${normalizedModels.join(',')}:${normalizedPools.join(',')}`;
  return mergePoolBindingRequest(key, () => request<ModelPoolBindingChangeResult>({
    url: `/aid/aidmodel/pool-bindings/${operation}`,
    method: 'post',
    data: { modelIds: normalizedModels, poolIds: normalizedPools }
  }));
}

/** 批量绑定模型池；重复关系由服务端幂等保留。 */
export function bindModelsToPools(modelIds: number[], poolIds: number[]) {
  return changeModelPoolBindings('bind', modelIds, poolIds);
}

/** 批量移出模型池；重复关系由服务端幂等忽略。 */
export function unbindModelsFromPools(modelIds: number[], poolIds: number[]) {
  return changeModelPoolBindings('unbind', modelIds, poolIds);
}

export function getModel(id) {
  return request({ url: '/aid/aidmodel/' + id, method: 'get' })
}

export function addModel(data) {
  return request({ url: '/aid/aidmodel', method: 'post', data })
}

export function updateModel(data) {
  return request({ url: '/aid/aidmodel', method: 'put', data })
}

export type ModelSkuCoverageStatus =
  | 'NOT_APPLICABLE'
  | 'COMPLETE'
  | 'GAPS'
  | 'CONFLICTS'
  | 'REVIEW_REQUIRED'

export interface ModelSkuCoverageReport {
  status: ModelSkuCoverageStatus;
  checkedCombinations: number;
  missingCombinations: Record<string, unknown>[];
  conflicts: string[];
  warnings: string[];
}

/** 只读检查模型草稿的有限SKU覆盖，不创建任务、价格或计费记录。 */
export function checkModelSkuCoverage(data: Record<string, unknown>) {
  return request<ModelSkuCoverageReport>({ url: '/aid/aidmodel/sku-coverage', method: 'post', data })
}

export function delModel(id) {
  return request({ url: '/aid/aidmodel/' + id, method: 'delete' })
}

/**
 * v2.59+：后台 12 模型管理页面专用 —— 按 funcCode 查可用模型池
 * 走后台 GET /aid/aidmodel/listByFunc，不经过 C 端加密链路
 */
export function listModelByFunc(funcCode: string) {
  return request({
    url: '/aid/aidmodel/listByFunc',
    method: 'get',
    params: { funcCode }
  })
}

// ==================== 真实模型总览接口 ====================

/** 真实模型总览行（同一真实模型下的单个展示模型） */
export interface RealModelItem {
  id: number;
  configVersion?: number;
  modelCode: string;
  modelName: string;
  realModelCode?: string;
  modelType: string;
  generateMode?: string;
  providerId?: number;
  providerName?: string;
  status: string;
  priority?: number;
}

/** 真实模型总览分组（按真实上游模型名聚合） */
export interface RealModelGroup {
  realModelCode: string;
  modelType: string;
  activeCount: number;
  totalCount: number;
  models: RealModelItem[];
}

/** 真实模型总览：按真实上游模型名聚合，支持关键字搜索 */
export function realModelOverview(keyword?: string) {
  return request<RealModelGroup[]>({
    url: '/aid/aidmodel/realModelOverview',
    method: 'get',
    params: keyword ? { keyword } : {}
  })
}

// ==================== 计费试算接口 ====================

export function billingPreview(data) {
  return request({ url: '/aid/billing/preview', method: 'post', data })
}
