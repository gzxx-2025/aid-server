'use client'

import { Input } from 'antd'
import type { ReactNode } from 'react'

export type AuthDarkFieldSurface = 'ghost' | 'solid'

export interface AuthDarkFieldProps {
  type?: 'text' | 'password'
  name?: string
  value: string
  placeholder: string
  maxLength?: number
  inputMode?: 'numeric' | 'text'
  autoComplete?: string
  suffix?: ReactNode
  /** ghost：登录弹窗描边半透明底；solid：找回密码实心底 */
  surface?: AuthDarkFieldSurface
  showPasswordToggle?: boolean
  onChange: (value: string) => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

const SURFACE_WRAP: Record<AuthDarkFieldSurface, string> = {
  ghost: 'auth-dark-field-wrap auth-dark-field-wrap--ghost',
  solid: 'auth-dark-field-wrap auth-dark-field-wrap--solid'
}

/** 登录 / 找回密码共用暗色输入；antd 深层覆盖见 assets/css/auth-dark-field.css */
export function AuthDarkField({
  type = 'text',
  name,
  value,
  placeholder,
  maxLength,
  inputMode,
  autoComplete,
  suffix,
  surface = 'ghost',
  showPasswordToggle = false,
  onChange,
  onUnlockAutofill
}: AuthDarkFieldProps) {
  const isPassword = type === 'password' || showPasswordToggle
  const shared = {
    name,
    size: 'large' as const,
    value,
    placeholder,
    maxLength,
    suffix,
    variant: 'borderless' as const,
    readOnly: true,
    autoComplete: isPassword ? autoComplete || 'new-password' : autoComplete || 'off',
    className: 'auth-dark-field-input',
    onFocus: onUnlockAutofill,
    onMouseDown: onUnlockAutofill,
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value)
  }

  return (
    <div className={SURFACE_WRAP[surface]}>
      {isPassword ? (
        <Input.Password {...shared} visibilityToggle={showPasswordToggle} />
      ) : (
        <Input {...shared} inputMode={inputMode} />
      )}
    </div>
  )
}
