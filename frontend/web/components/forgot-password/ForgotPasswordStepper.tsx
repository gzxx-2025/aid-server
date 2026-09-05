'use client'

import {
  FORGOT_PASSWORD_ACCENT,
  FORGOT_PASSWORD_STEPPER_CLASS,
  FORGOT_PASSWORD_STEPPER_LINE_CLASS
} from '~/utils/forgotPasswordLayout'

const STEPS = [
  { id: 1, label: '身份验证' },
  { id: 2, label: '重置密码' },
  { id: 3, label: '完成' }
] as const

interface ForgotPasswordStepperProps {
  step: 1 | 2 | 3
}

export function ForgotPasswordStepper({ step }: ForgotPasswordStepperProps) {
  return (
    <nav className={FORGOT_PASSWORD_STEPPER_CLASS} aria-label="找回密码进度">
      {STEPS.map((item, index) => {
        const active = step >= item.id
        const color = active ? FORGOT_PASSWORD_ACCENT : '#FFFFFF'
        const showLine = index < STEPS.length - 1
        const lineActive = step >= item.id

        return (
          <div key={item.id} className="flex items-center">
            <div className="flex h-8 items-center gap-2 whitespace-nowrap" style={{ color }}>
              <span
                className="inline-block text-[1.875rem] font-normal leading-8"
                style={{
                  fontFamily: '"Lantinghei SC", "PingFang SC", "Microsoft YaHei", sans-serif'
                }}
              >
                {item.id}
              </span>
              <span className="text-base font-medium leading-[22px]">{item.label}</span>
            </div>
            {showLine && (
              <span
                aria-hidden="true"
                className={FORGOT_PASSWORD_STEPPER_LINE_CLASS}
                style={{
                  borderColor: lineActive ? 'var(--home-cyan, #4AE7FD)' : 'rgba(255,255,255,0.9)'
                }}
              />
            )}
          </div>
        )
      })}
    </nav>
  )
}
