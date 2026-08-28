/** 用户侧权威计费报价；只读、无冻结和扣费副作用。 */
import type { AxiosRequestConfig } from 'axios'
import type { ApiEnvelope, BillingQuoteRequest, BillingQuoteVO } from '~/types/business-api'
import { request } from '~/utils/api'
import { unwrap } from '~/utils/business/shared'

export async function userBillingQuote(
  body: BillingQuoteRequest,
  config?: Pick<AxiosRequestConfig, 'signal'>
): Promise<BillingQuoteVO> {
  const res = await request.post<ApiEnvelope<BillingQuoteVO>>(
    '/api/user/billing/quote',
    body,
    config
  )
  return unwrap(res)
}
