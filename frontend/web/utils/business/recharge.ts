/** 充值与计费域：充值套餐/订单创建查询取消、积分消耗明细、公共计费详情。 */
import type {
ApiEnvelope,
CreditConsumeListResponse,
CreditConsumeRecordRow,
PublicBillingDetailData,
PublicBillingDetailRequest,
RechargeOrderCreateData,
RechargeOrderCreateRequest,
RechargeOrderListResponse,
RechargeOrderQueryData,
RechargeOrderRow,
RechargePackageItem
} from '~/types/business-api'
import { request } from '~/utils/api'
import { extractPageRows,unwrap } from '~/utils/business/shared'

/** 充值：套餐列表（POST，空请求体） */
export async function rechargePackageList(): Promise<RechargePackageItem[]> {
  const res = await request.post<ApiEnvelope<RechargePackageItem[]>>('/recharge/package/list', {})
  return unwrap(res)
}

/** 充值：创建订单 */
export async function rechargeOrderCreate(body: RechargeOrderCreateRequest): Promise<RechargeOrderCreateData> {
  const res = await request.post<ApiEnvelope<RechargeOrderCreateData>>('/recharge/order/create', body)
  return unwrap(res)
}

/** 充值：查询订单状态（POST + JSON body，与接口文档 `/recharge/order/query` 一致） */
export async function rechargeOrderQuery(orderNo: string): Promise<RechargeOrderQueryData> {
  const res = await request.post<ApiEnvelope<RechargeOrderQueryData>>('/recharge/order/query', {
    orderNo
  })
  return unwrap(res)
}

/** 充值：订单列表（成功时 code 可能为 0；列表在响应体 `data` 数组中，见 {@link RechargeOrderListResponse}） */
export async function rechargeOrderList(params?: {
  payStatus?: string
  pageNum?: number
  pageSize?: number
}): Promise<{ total: number; rows: RechargeOrderRow[] }> {
  const res = (await request.post('/recharge/order/list', params ?? {})) as RechargeOrderListResponse
  const { rows, total } = extractPageRows<RechargeOrderRow>(res)
  const normalized = rows.map((row) => ({
    ...row,
    payStatus:
      typeof row.payStatus === 'string'
        ? row.payStatus.toLowerCase()
        : row.payStatus != null
          ? String(row.payStatus)
          : ''
  }))
  return { total, rows: normalized }
}

/** 充值：继续支付（POST + JSON，与接口文档 `/recharge/order/repay` 一致） */
export async function rechargeOrderRepay(orderNo: string): Promise<RechargeOrderCreateData> {
  const res = await request.post<ApiEnvelope<RechargeOrderCreateData>>('/recharge/order/repay', {
    orderNo
  })
  return unwrap(res)
}

/** 充值：取消订单（POST + JSON body，与接口文档一致） */
export async function rechargeOrderCancel(orderNo: string): Promise<void> {
  await request.post<ApiEnvelope>('/recharge/order/cancel', { orderNo })
}

/** 积分消耗明细：分页列表 POST /api/user/credit/consume/list */
export async function creditConsumeList(params?: {
  pageNum?: number
  pageSize?: number
}): Promise<{ total: number; rows: CreditConsumeRecordRow[] }> {
  const res = (await request.post('/api/user/credit/consume/list', params ?? {})) as CreditConsumeListResponse
  const { rows, total } = extractPageRows<CreditConsumeRecordRow>(res)
  return { total, rows }
}

/** 计费详情（公共）：POST /api/public/billing/detail */
export async function publicBillingDetail(
  body: PublicBillingDetailRequest = {}
): Promise<PublicBillingDetailData> {
  const res = await request.post<ApiEnvelope<PublicBillingDetailData>>('/api/public/billing/detail', body)
  const data = unwrap(res)
  return {
    creditUnit: data?.creditUnit ?? 'Credits',
    llm: Array.isArray(data?.llm) ? data.llm : [],
    image: Array.isArray(data?.image) ? data.image : [],
    video: Array.isArray(data?.video) ? data.video : [],
    voice: Array.isArray(data?.voice) ? data.voice : []
  }
}
