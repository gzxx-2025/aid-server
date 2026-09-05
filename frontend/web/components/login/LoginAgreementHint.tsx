'use client'

import { LOGIN_MODAL_HINT_CLASS } from '~/utils/loginModalLayout'

interface LoginAgreementHintProps {
  extra?: string
  termsOfServiceUrl?: string | null
  privacyPolicyUrl?: string | null
}

function PolicyLink({
  href,
  children
}: {
  href?: string | null
  children: string
}) {
  if (!href) {
    return <span className="text-[var(--home-cyan)]">{children}</span>
  }
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="cursor-pointer text-[var(--home-cyan)] hover:underline"
      onClick={(e) => e.stopPropagation()}
    >
      {children}
    </a>
  )
}

export function LoginAgreementHint({
  extra = '',
  termsOfServiceUrl,
  privacyPolicyUrl
}: LoginAgreementHintProps) {
  return (
    <p className={LOGIN_MODAL_HINT_CLASS}>
      登录即表示同意
      <PolicyLink href={termsOfServiceUrl}>《用户协议》</PolicyLink>
      和
      <PolicyLink href={privacyPolicyUrl}>《隐私政策》</PolicyLink>
      {extra}
    </p>
  )
}
