/**
 * 接口信封加密（AES-GCM-256 + RSA-OAEP-SHA256），与 `components/steps/接口.md` v2.59.0 对齐。
 */
import type { InternalAxiosRequestConfig } from 'axios'
import { ungzip } from 'pako'
import type { AuthCryptoPublicConfig } from '~/types/business-api'

const AES_GCM_IV_BYTES = 12
const AES_GCM_TAG_BITS = 128

/** public-config 写入浏览器缓存时的本机时间，用于恢复稳定的服务端时差。 */
export const API_CRYPTO_CLIENT_TIME_CACHE_FIELD = '__apiCryptoClientTimeMs' as const

/** 需加密的路径前缀（与后端 include-patterns 一致） */
const INCLUDE_PREFIXES = ['/api/', '/auth/', '/recharge/', '/realAuth/', '/captcha/']

/** 明文豁免（与后端 exclude-patterns 一致） */
const EXCLUDE_EXACT = new Set([
  '/auth/crypto/public-key',
  '/auth/public-config',
  '/captcha/gen'
])

const EXCLUDE_PREFIXES = [
  '/auth/wechat/',
  '/pay/notify/',
  '/api/callback/',
  '/api/user/oss/',
  '/api/user/task/stream/',
  '/api/user/episode/export/segments/zip',
  '/api/user/episode/export/download',
  '/v3/api-docs/',
  '/swagger-ui/',
  '/druid/',
  '/profile/'
]

export interface ApiEncryptedResponse {
  encrypted: true
  iv: string
  gzip?: boolean
  data: string
}

export interface PreparedEncryptedRequest {
  headers: Record<string, string>
  /** POST 请求体：Base64 密文文本 */
  body?: string
  aesKey: CryptoKey
}

let cryptoEnabled = false
let rsaPublicKeyB64: string | null = null
let serverTimeOffsetMs = 0
let rsaPublicKeyPromise: Promise<CryptoKey> | null = null
const requestAesKeyMap = new WeakMap<InternalAxiosRequestConfig, CryptoKey>()
let apiCryptoConfigResolved = false
let refreshApiCryptoConfigHandler: (() => Promise<void>) | null = null
let refreshApiCryptoConfigPromise: Promise<boolean> | null = null

type AxiosConfigWithAes = InternalAxiosRequestConfig & { __aesKey?: CryptoKey }

function b64ToBuf(b64: string): Uint8Array<ArrayBuffer> {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length) as Uint8Array<ArrayBuffer>
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function bufToB64(buf: ArrayBuffer | Uint8Array): string {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf)
  let s = ''
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]!)
  return btoa(s)
}

/** 规范化请求路径（去掉 /url 前缀、query、绝对 URL 的 origin） */
export function normalizeApiPath(url?: string): string {
  if (!url) return ''
  let path = String(url).split('?')[0] || ''
  try {
    if (/^https?:\/\//i.test(path)) path = new URL(path).pathname
  } catch {
    /* keep path */
  }
  path = path.replace(/^\/url(?=\/)/, '')
  if (!path.startsWith('/')) path = `/${path}`
  return path
}

export function isApiCryptoExemptPath(url?: string): boolean {
  const path = normalizeApiPath(url)
  if (!path) return true
  if (EXCLUDE_EXACT.has(path)) return true
  return EXCLUDE_PREFIXES.some((p) => path.startsWith(p))
}

export function isApiCryptoIncludedPath(url?: string): boolean {
  const path = normalizeApiPath(url)
  if (!path) return false
  return INCLUDE_PREFIXES.some((p) => path.startsWith(p))
}

/** 当前请求是否应走信封加密 */
export function shouldEncryptApiPath(url?: string): boolean {
  if (!cryptoEnabled || !rsaPublicKeyB64) return false
  if (isApiCryptoExemptPath(url)) return false
  return isApiCryptoIncludedPath(url)
}

export function isApiCryptoEnabled(): boolean {
  return cryptoEnabled && !!rsaPublicKeyB64
}

/** 注册公开配置刷新器，供请求层初始化配置或在时间戳过期时重新校时。 */
export function registerApiCryptoConfigRefreshHandler(handler: () => Promise<void>): void {
  refreshApiCryptoConfigHandler = handler
}

/** 合并并发校时请求；刷新失败时保留原始接口错误。 */
export async function refreshApiCryptoConfig(): Promise<boolean> {
  if (!refreshApiCryptoConfigHandler) return false
  if (!refreshApiCryptoConfigPromise) {
    refreshApiCryptoConfigPromise = refreshApiCryptoConfigHandler()
      .then(() => true)
      .catch(() => false)
      .finally(() => {
        refreshApiCryptoConfigPromise = null
      })
  }
  return refreshApiCryptoConfigPromise
}

/** 首个受保护请求等待公开配置就绪，避免按默认明文抢跑。 */
export async function ensureApiCryptoConfigReady(): Promise<boolean> {
  if (refreshApiCryptoConfigPromise) return refreshApiCryptoConfigPromise
  if (apiCryptoConfigResolved) return true
  return refreshApiCryptoConfig()
}

export function getApiCryptoTimestampMs(): number {
  return Date.now() + serverTimeOffsetMs
}

export function applyApiCryptoFromPublicConfig(
  crypto?: AuthCryptoPublicConfig | null,
  serverTime?: number,
  clientTimeAtSync?: number
): void {
  const enabled = Boolean(crypto?.enabled && crypto.publicKey)
  cryptoEnabled = enabled
  rsaPublicKeyB64 = enabled ? String(crypto!.publicKey) : null
  rsaPublicKeyPromise = null
  apiCryptoConfigResolved = true
  if (
    typeof serverTime === 'number' &&
    Number.isFinite(serverTime) &&
    typeof clientTimeAtSync === 'number' &&
    Number.isFinite(clientTimeAtSync)
  ) {
    serverTimeOffsetMs = serverTime - clientTimeAtSync
  }
}

async function importRsaPublicKey(): Promise<CryptoKey> {
  if (!rsaPublicKeyB64) throw new Error('RSA 公钥未配置')
  if (!rsaPublicKeyPromise) {
    rsaPublicKeyPromise = crypto.subtle.importKey(
      'spki',
      b64ToBuf(rsaPublicKeyB64),
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['encrypt']
    )
  }
  return rsaPublicKeyPromise
}

function isEncryptedEnvelope(data: unknown): data is ApiEncryptedResponse {
  return (
    !!data &&
    typeof data === 'object' &&
    (data as ApiEncryptedResponse).encrypted === true &&
    typeof (data as ApiEncryptedResponse).iv === 'string' &&
    typeof (data as ApiEncryptedResponse).data === 'string'
  )
}

/**
 * 加密 POST 业务体；GET 或无体请求仅生成响应解密所需的请求头。
 */
export async function prepareEncryptedRequest(options: {
  body?: unknown
  skipBody?: boolean
}): Promise<PreparedEncryptedRequest> {
  if (!(typeof window !== 'undefined')) throw new Error('信封加密仅支持浏览器环境')
  const aesKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
  const rawAes = await crypto.subtle.exportKey('raw', aesKey)
  const rsaPub = await importRsaPublicKey()
  const encKey = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, rsaPub, rawAes)

  const headers: Record<string, string> = {
    'X-Encrypt-Key': bufToB64(encKey),
    'X-Encrypt-Ts': String(getApiCryptoTimestampMs()),
    'Content-Type': 'application/json'
  }

  let body: string | undefined
  if (!options.skipBody) {
    const iv = crypto.getRandomValues(new Uint8Array(AES_GCM_IV_BYTES))
    const plain =
      options.body === undefined || options.body === null
        ? new TextEncoder().encode('{}')
        : new TextEncoder().encode(
            typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
          )
    const cipher = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, tagLength: AES_GCM_TAG_BITS },
      aesKey,
      plain
    )
    headers['X-Encrypt-Iv'] = bufToB64(iv)
    body = bufToB64(cipher)
  }

  return { headers, body, aesKey }
}

/** 将加密结果写入 axios 配置；必须绕过默认 transformRequest，否则 body 会被再包一层 JSON 引号 */
export function applyEncryptedPayloadToAxiosConfig(
  config: InternalAxiosRequestConfig,
  enc: PreparedEncryptedRequest,
  options?: { skipBody?: boolean }
): void {
  if (!options?.skipBody && enc.body != null) {
    config.data = enc.body
    // 与 crypto-demo fetch(body: enc.body) 一致：发送裸 Base64，不能 JSON.stringify
    config.transformRequest = [(data) => (typeof data === 'string' ? data : data)]
  }
  Object.assign(config.headers, enc.headers)
  requestAesKeyMap.set(config, enc.aesKey)
  ;(config as AxiosConfigWithAes).__aesKey = enc.aesKey
}

export function takeAxiosRequestAesKey(config: InternalAxiosRequestConfig): CryptoKey | undefined {
  const configWithAes = config as AxiosConfigWithAes
  const key = requestAesKeyMap.get(config) ?? configWithAes.__aesKey
  requestAesKeyMap.delete(config)
  delete configWithAes.__aesKey
  return key
}

/** 解密服务端 `{ encrypted, iv, gzip, data }` 信封，得到原始业务 JSON */
export async function decryptEncryptedResponse<T = unknown>(
  resp: ApiEncryptedResponse,
  aesKey: CryptoKey
): Promise<T> {
  const iv = b64ToBuf(resp.iv)
  const plainBuf = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv, tagLength: AES_GCM_TAG_BITS },
    aesKey,
    b64ToBuf(resp.data)
  )
  let bytes: Uint8Array = new Uint8Array(plainBuf)
  if (resp.gzip) bytes = ungzip(bytes) as Uint8Array<ArrayBuffer>
  const text = new TextDecoder().decode(bytes)
  return JSON.parse(text) as T
}

/** 若响应为加密信封则解密，否则原样返回 */
export async function maybeDecryptApiPayload<T = unknown>(
  data: unknown,
  aesKey?: CryptoKey | null
): Promise<T> {
  if (!aesKey || !isEncryptedEnvelope(data)) return data as T
  return decryptEncryptedResponse<T>(data, aesKey)
}

/** 从 sessionStorage 恢复加密配置（与 useAuthPublicConfig 缓存键一致） */
export function hydrateApiCryptoFromSessionCache(): void {
  if (!(typeof window !== 'undefined')) return
  try {
    const raw = sessionStorage.getItem('auth:public-config:v3')
    if (!raw) return
    const parsed = JSON.parse(raw) as {
      crypto?: AuthCryptoPublicConfig
      serverTime?: number
      [API_CRYPTO_CLIENT_TIME_CACHE_FIELD]?: number
    }
    applyApiCryptoFromPublicConfig(
      parsed.crypto,
      parsed.serverTime,
      parsed[API_CRYPTO_CLIENT_TIME_CACHE_FIELD]
    )
  } catch {
    /* ignore */
  }
}

if ((typeof window !== 'undefined')) {
  hydrateApiCryptoFromSessionCache()
}
