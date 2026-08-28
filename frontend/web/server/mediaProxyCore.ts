const MAX_URL_LENGTH = 4096
const MAX_REDIRECTS = 5
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308])
const MEDIA_ACCEPT = 'video/*,audio/*,application/octet-stream,*/*;q=0.8'

class MediaProxyError extends Error {
  constructor(
    message: string,
    readonly status: number
  ) {
    super(message)
  }
}

function matchesAllowedHost(host: string, rule: string): boolean {
  if (!rule.startsWith('*.')) return host === rule
  const suffix = rule.slice(1)
  return host.endsWith(suffix) && host.length > suffix.length
}

function isBlockedIpv4(host: string): boolean {
  const parts = host.split('.').map(Number)
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return false
  }
  const [a, b, c] = parts
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 0 && (c === 0 || c === 2)) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
    (a === 203 && b === 0 && c === 113) ||
    a >= 224
  )
}

export function isBlockedMediaHostname(rawHost: string): boolean {
  const host = rawHost.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.+$/, '')
  if (
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host.endsWith('.local') ||
    host.endsWith('.internal')
  ) {
    return true
  }
  if (isBlockedIpv4(host)) return true
  if (!host.includes(':')) return false

  return (
    host === '::' ||
    host === '::1' ||
    host.startsWith('::ffff:') ||
    /^(fc|fd)/i.test(host) ||
    /^fe[89a-f]/i.test(host) ||
    /^ff/i.test(host)
  )
}

function allowedHosts(): string[] {
  return String(
    process.env.MEDIA_PROXY_ALLOWED_HOSTS || process.env.NUXT_MEDIA_PROXY_ALLOWED_HOSTS || ''
  )
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean)
}

function assertPublicMediaUrl(parsed: URL) {
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new MediaProxyError('仅支持 http/https', 400)
  }

  const host = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.+$/, '')
  const allowlist = allowedHosts()
  if (allowlist.length && !allowlist.some((rule) => matchesAllowedHost(host, rule))) {
    throw new MediaProxyError('媒体域名未授权', 403)
  }
  if (isBlockedMediaHostname(host)) {
    throw new MediaProxyError('不允许的媒体地址', 400)
  }
}

function parsePublicMediaUrl(rawUrl: string): URL {
  if (!rawUrl) throw new MediaProxyError('缺少 url 参数', 400)
  if (rawUrl.length > MAX_URL_LENGTH) throw new MediaProxyError('url 过长', 400)
  try {
    const parsed = new URL(rawUrl)
    assertPublicMediaUrl(parsed)
    return parsed
  } catch (error) {
    if (error instanceof MediaProxyError) throw error
    throw new MediaProxyError('url 格式无效', 400)
  }
}

function upstreamHeaders(request: Request): Headers {
  const headers = new Headers({
    Accept: MEDIA_ACCEPT,
    'Accept-Encoding': 'identity'
  })
  const range = String(request.headers.get('range') || '').trim()
  if (range) {
    const bytes = range.slice(6)
    if (!/^bytes=\d*-\d*$/i.test(range) || !bytes.split('-').some(Boolean)) {
      throw new MediaProxyError('Range 格式无效', 416)
    }
    headers.set('Range', range)
  }
  const ifRange = String(request.headers.get('if-range') || '').trim()
  if (ifRange && ifRange.length <= 512) headers.set('If-Range', ifRange)
  return headers
}

async function fetchWithSafeRedirects(initialUrl: URL, headers: Headers): Promise<Response> {
  let target = initialUrl
  for (let redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount += 1) {
    assertPublicMediaUrl(target)
    const response = await fetch(target, {
      headers,
      redirect: 'manual',
      signal: AbortSignal.timeout(30_000)
    })
    if (!REDIRECT_STATUSES.has(response.status)) return response

    const location = response.headers.get('location')
    if (!location) throw new MediaProxyError('媒体重定向无效', 502)
    if (redirectCount === MAX_REDIRECTS) {
      throw new MediaProxyError('媒体重定向过多', 502)
    }
    await response.body?.cancel()
    target = new URL(location, target)
  }
  throw new MediaProxyError('媒体重定向过多', 502)
}

function errorResponse(error: unknown): Response {
  const status = error instanceof MediaProxyError ? error.status : 502
  const message = error instanceof MediaProxyError ? error.message : '媒体拉取失败'
  return Response.json({ message }, { status })
}

/** Next Proxy 使用的同源流媒体代理，恢复架构迁移前的 Range/206 能力。 */
export async function handleMediaProxyRequest(request: Request): Promise<Response> {
  try {
    const requestUrl = new URL(request.url)
    const target = parsePublicMediaUrl(String(requestUrl.searchParams.get('url') || '').trim())
    const upstream = await fetchWithSafeRedirects(target, upstreamHeaders(request))

    if (upstream.status === 416) {
      const headers = new Headers()
      for (const name of ['content-range', 'accept-ranges']) {
        const value = upstream.headers.get(name)
        if (value) headers.set(name, value)
      }
      return new Response(null, { status: 416, headers })
    }
    if (!upstream.ok || !upstream.body) {
      return errorResponse(new MediaProxyError('媒体拉取失败', upstream.status || 502))
    }

    const contentType = String(upstream.headers.get('content-type') || 'application/octet-stream')
    if (/text\/html|application\/json/i.test(contentType)) {
      await upstream.body.cancel()
      return errorResponse(new MediaProxyError('非媒体资源', 400))
    }

    const headers = new Headers({
      'Content-Type': contentType,
      'Cache-Control': 'private, max-age=300',
      'X-Content-Type-Options': 'nosniff',
      'Cross-Origin-Resource-Policy': 'same-origin'
    })
    for (const name of [
      'accept-ranges',
      'content-length',
      'content-range',
      'etag',
      'last-modified'
    ]) {
      const value = upstream.headers.get(name)
      if (value) headers.set(name, value)
    }
    return new Response(upstream.body, {
      status: upstream.status === 206 ? 206 : 200,
      headers
    })
  } catch (error) {
    return errorResponse(error)
  }
}
