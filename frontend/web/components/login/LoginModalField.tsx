'use client'

import type { ReactNode } from 'react'
import { AuthDarkField } from '@/components/auth/AuthDarkField'

interface LoginModalFieldProps {
  type?: 'text' | 'password'
  name?: string
  value: string
  placeholder: string
  maxLength?: number
  inputMode?: 'numeric' | 'text'
  suffix?: ReactNode
  onChange: (value: string) => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

/** 登录弹窗字段 = 共用 AuthDarkField（ghost 描边面） */
export function LoginModalField(props: LoginModalFieldProps) {
  return <AuthDarkField {...props} surface="ghost" showPasswordToggle={false} />
}
