/**
 * 素材准备列表图：底部中间按钮「重新生成」vs「替换」的判定。
 * 仅 sourceType=ai_auto（列表自动生成产出）且资产允许自动生成时显示重新生成。
 */

export function resolveAssetImageSourceType(img: unknown): string {
  if (!img || typeof img !== 'object') return ''
  const row = img as Record<string, unknown>
  const server = String(row.sourceType ?? row.source_type ?? row._serverSourceType ?? '')
    .trim()
    .toLowerCase()
  if (server) return server
  const src = String(row.source ?? '')
    .trim()
    .toLowerCase()
  if (src === 'ai' || src === 'ai_auto') return 'ai_auto'
  return ''
}

export function shouldShowRegenerateForAssetImage(img: unknown): boolean {
  return resolveAssetImageSourceType(img) === 'ai_auto'
}

export function shouldShowAssetImageRegenerateAction(
  img: unknown,
  canAutoGenerate: boolean
): boolean {
  return Boolean(canAutoGenerate) && shouldShowRegenerateForAssetImage(img)
}
