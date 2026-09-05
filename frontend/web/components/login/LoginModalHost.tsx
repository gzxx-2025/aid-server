'use client'

import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { usePathname, useRouter } from 'next/navigation'
import { LoginModal } from './LoginModal'
import { closeLoginModal, useLoginModalStore } from '@/stores/loginModal'
import { useUserStore } from '@/stores/user'
import { isAuthRequiredPath, isForgotPasswordPath } from '~/utils/authRequiredPath'

const EXIT_MS = 220

/**
 * 登录弹窗挂载层：portal + 进出场动画 + 路由联动关闭。
 * store.open 为意图；退场播完再卸 DOM，避免生硬闪断。
 */
export function LoginModalHost() {
  const open = useLoginModalStore((s) => s.open)
  const pathname = usePathname()
  const router = useRouter()
  const [mounted, setMounted] = useState(false)
  const [rendered, setRendered] = useState(false)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  /* 找回密码是独立页：进入该路由时强制关掉登录弹窗（清遮罩与回跳意图） */
  useEffect(() => {
    if (!pathname || !isForgotPasswordPath(pathname)) return
    if (useLoginModalStore.getState().open) closeLoginModal()
  }, [pathname])

  useEffect(() => {
    if (!open) {
      setVisible(false)
      return
    }
    setRendered(true)
    const id = window.requestAnimationFrame(() => {
      window.requestAnimationFrame(() => setVisible(true))
    })
    return () => window.cancelAnimationFrame(id)
  }, [open])

  useEffect(() => {
    if (open || !rendered) return
    const timer = window.setTimeout(() => setRendered(false), EXIT_MS)
    return () => window.clearTimeout(timer)
  }, [open, rendered])

  useEffect(() => {
    if (!open) return
    if (!localStorage.getItem('token') && useUserStore.getState().token) {
      useUserStore.getState().logout()
    }
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeLoginModal()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = prev
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  useEffect(() => {
    if (open) return
    if (useUserStore.getState().token) return
    if (pathname && isAuthRequiredPath(pathname)) {
      router.replace('/')
    }
  }, [open, pathname, router])

  if (!mounted || !rendered) return null
  return createPortal(<LoginModal visible={visible} />, document.body)
}
