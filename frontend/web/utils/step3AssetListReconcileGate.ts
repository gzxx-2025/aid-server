/**
 * 资产列表 reconcile 时是否保留卡片 generating。
 * 跨集返回时 formImage registry 常尚未重建，禁止把 Pinia/scope 的 generating 误降为 idle/success。
 */
export function shouldPreserveStep3GeneratingOnAssetListReconcile(input: {
  underActiveFormImageGen: boolean
  previousStatus: string | undefined
  assetBootstrapReady: boolean
  flowStepGenerating: boolean
}): boolean {
  if (input.underActiveFormImageGen) return true
  if (input.previousStatus !== 'generating') return false
  return !input.assetBootstrapReady || input.flowStepGenerating
}
