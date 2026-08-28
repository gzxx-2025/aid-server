import { NextResponse,type NextRequest } from 'next/server'
import { handleMediaProxyRequest } from '~/server/mediaProxyCore'

const MOBILE_ONLY_PATH = '/mobile'

function isMobileUserAgent(ua: string): boolean {
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile|Windows Phone/i.test(
    ua
  )
}

/** SSR 阶段 UA 拦截：与原 Nuxt middleware/00.mobile-only.global.ts 的服务端分支一致，避免首屏闪跳 */
export async function proxy(request: NextRequest) {
  const ua = request.headers.get('user-agent') || ''
  const isMobile = isMobileUserAgent(ua)
  const { pathname } = request.nextUrl

  if (pathname === '/media/proxy') {
    return handleMediaProxyRequest(request)
  }

  if (isMobile && pathname !== MOBILE_ONLY_PATH) {
    return NextResponse.redirect(new URL(MOBILE_ONLY_PATH, request.url))
  }
  if (!isMobile && pathname === MOBILE_ONLY_PATH) {
    return NextResponse.redirect(new URL('/', request.url))
  }
  return NextResponse.next()
}

export const config = {
  /** 跳过静态资源与 API 代理路径 */
  matcher: [
    '/media/proxy',
    '/((?!_next|url|api|tac|media|favicon|.*\\..*).*)'
  ]
}
