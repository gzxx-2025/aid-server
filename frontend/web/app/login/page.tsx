'use client'

import { Suspense, useEffect } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { openLoginModal } from '@/stores/loginModal'
import { useUserStore } from '@/stores/user'
import { readInviteCodeFromSearch, sanitizeLoginRedirect } from '~/utils/authRequiredPath'

/**
 * 兼容旧书签 / 邀请链接 /login?invite= ：落到首页并打开登录弹窗。
 */
export default function LoginRedirectPage() {
  return (
    <Suspense fallback={null}>
      <LoginRedirectInner />
    </Suspense>
  )
}

function LoginRedirectInner() {
  const router = useRouter()
  const searchParams = useSearchParams()

  useEffect(() => {
    useUserStore.getState().hydrateFromStorage()
    const token = useUserStore.getState().token || localStorage.getItem('token') || ''
    const redirect = sanitizeLoginRedirect(searchParams.get('redirect'))
    if (token) {
      router.replace(redirect)
      return
    }
    openLoginModal({
      inviteCode: readInviteCodeFromSearch(searchParams),
      redirect
    })
    router.replace('/')
  }, [router, searchParams])

  return null
}
