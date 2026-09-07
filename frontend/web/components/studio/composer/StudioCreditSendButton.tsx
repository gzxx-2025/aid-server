'use client'

/* eslint-disable @next/next/no-img-element -- 积分图标来自现有 SVG 资源映射，保持与侧边栏一致。 */

import type { ComponentProps } from 'react'
import starlightCoinRaw from '~/assets/img/home/starlightCoin.svg'
import { ComposerSendButton } from '~/components/common/composer-send/ComposerSendButton'
import { useUserStore } from '@/stores/user'
import { assetUrl } from '@/utils/assetUrl'
import { studioCreditPresentation } from '@/utils/studio/studioCreditEstimate'

const coinUrl = assetUrl(starlightCoinRaw)

type Props = Omit<ComponentProps<typeof ComposerSendButton>, 'leadingContent'> & {
  credits: number
  /** 免费模型展示「免费」，不再显示约 0 */
  isFree?: boolean
  showCredits?: boolean
  /** 仅服务端 billing/quote 返回的金额可设为 true；本地模型列表估算不得阻止提交。 */
  authoritative?: boolean
  estimateLoading?: boolean
  estimateError?: string
  disabledReason?: string
}

export function StudioCreditSendButton({
  credits,
  isFree = false,
  showCredits = true,
  authoritative = false,
  estimateLoading = false,
  estimateError = '',
  disabledReason = '',
  disabled,
  className = '',
  ...props
}: Props) {
  const balance = useUserStore((state) => state.user?.balance)
  const presentation = studioCreditPresentation(credits, { isFree, authoritative })
  const insufficient = !isFree
    && authoritative
    && showCredits
    && typeof balance === 'number'
    && balance < credits
  return (
    <span
      className={`studio-credit-send${insufficient ? ' is-insufficient' : ''}`}
      title={disabledReason || (insufficient
        ? `积分不足：当前 ${balance}，本次报价 ${credits}`
        : estimateLoading
          ? '服务端估价中'
          : estimateError
            ? `报价暂不可用：${estimateError}`
            : presentation.ariaLabel)}
    >
      <ComposerSendButton
        {...props}
        disabled={Boolean(disabled || insufficient)}
        className={`${className}${insufficient ? ' is-insufficient' : ''}`}
        leadingContent={showCredits ? (
          <span className="studio-credit-send__cost" aria-label={presentation.ariaLabel}>
            <img src={coinUrl} alt="" aria-hidden="true" />
            <b>{estimateLoading ? '…' : presentation.visible}</b>
          </span>
        ) : undefined}
      />
    </span>
  )
}
