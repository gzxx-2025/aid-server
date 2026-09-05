'use client'

import type { ReactNode } from 'react'
import { AuthDarkField } from '@/components/auth/AuthDarkField'

interface ForgotPasswordFieldProps {
  type?: 'text' | 'password'
  value: string
  placeholder: string
  maxLength?: number
  inputMode?: 'numeric' | 'text'
  autoComplete?: string
  suffix?: ReactNode
  showPasswordToggle?: boolean
  onChange: (value: string) => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

/** 找回密码字段 = 共用 AuthDarkField（solid 实心面） */
export function ForgotPasswordField(props: ForgotPasswordFieldProps) {
  return <AuthDarkField {...props} surface="solid" />
}
