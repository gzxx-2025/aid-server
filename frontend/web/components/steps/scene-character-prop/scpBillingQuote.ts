import type { BillingQuoteRequest } from '~/types/business-api'
import type { ScpCtx, TabKey } from './types'

function validIds(values: number[]): number[] {
  return [...new Set(values.map(Number).filter((value) => Number.isFinite(value) && value > 0))]
}

/** 与 FORM_GENERATE 正式提交共用项目配置口径；服务端 DTO 字段为 assetIds。 */
export async function resolveScpFormGenerateBillingRequest(
  ctx: ScpCtx,
  tab: TabKey,
  assetIds: number[]
): Promise<BillingQuoteRequest | null> {
  const ids = validIds(assetIds)
  if (!ids.length) return null
  const fields = await ctx.resolveFormTextSubmitFields(tab)
  return {
    quoteType: 'FORM_GENERATE',
    payload: {
      assetIds: ids,
      agentCode: fields.agentCode,
      ...(fields.modelCode ? { modelCode: fields.modelCode } : {})
    }
  }
}

/** 与 runFormImageGenerate 相同：先解析真实 formId，再读取当前项目生图配置。 */
export async function resolveScpFormImageBillingRequest(
  ctx: ScpCtx,
  tab: 'character' | 'prop',
  assetIndex: number,
  formIndex: number
): Promise<BillingQuoteRequest | null> {
  const formId = await ctx.resolveFormIdForAssetForm(tab, assetIndex, formIndex)
  if (formId == null || !Number.isFinite(Number(formId)) || Number(formId) <= 0) return null
  const fields = await ctx.resolveFormImageApiSubmitFields(tab)
  return {
    quoteType: 'FORM_IMAGE',
    payload: {
      formIds: [Number(formId)],
      agentCode: fields.agentCode,
      ...(fields.modelCode ? { modelCode: fields.modelCode } : {}),
      ...(fields.resolution ? { resolution: fields.resolution } : {}),
      ...(fields.aspectRatio ? { aspectRatio: fields.aspectRatio } : {})
    }
  }
}
