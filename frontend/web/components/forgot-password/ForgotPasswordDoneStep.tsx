'use client'

import { FORGOT_PASSWORD_DONE_CLASS, FORGOT_PASSWORD_SUCCESS } from '~/utils/forgotPasswordLayout'

interface ForgotPasswordDoneStepProps {
  onGoLogin: () => void
}

export function ForgotPasswordDoneStep({ onGoLogin }: ForgotPasswordDoneStepProps) {
  return (
    <div className={FORGOT_PASSWORD_DONE_CLASS}>
      <div
        className="flex h-16 w-16 items-center justify-center rounded-full"
        style={{ backgroundColor: FORGOT_PASSWORD_SUCCESS }}
        aria-hidden="true"
      >
        <svg viewBox="0 0 24 24" className="h-9 w-9" fill="none">
          <path
            d="M5 12.5l5 5L19 7"
            stroke="#fff"
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>
      <p className="m-0 text-lg font-medium leading-[22px] text-white">新密码已设置成功</p>
      <button
        type="button"
        className="cursor-pointer border-0 bg-transparent text-base font-normal text-white hover:opacity-80"
        onClick={onGoLogin}
      >
        去登录 &gt;&gt;
      </button>
    </div>
  )
}
