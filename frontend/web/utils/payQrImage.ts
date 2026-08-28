/**
 * 将支付接口返回的 qrCode（多为支付链接 URL）转为可在 <img src> 使用的 data: 图片（本地生成，不经第三方）。
 * 已在浏览器端用 qrcode 的 toDataURL；SSR 不生成二维码（弹窗仅在客户端展示）。
 */
export async function payQrToImageSrc(qr: string | null | undefined): Promise<string> {
  if (qr == null) return ''
  const t = String(qr).trim()
  if (!t) return ''
  if (t.startsWith('data:image')) return t

  const looksLikeHttp = /^https?:\/\//i.test(t)
  const looksLikeImageUrl = /\.(png|jpe?g|gif|webp)(\?|$)/i.test(t)
  if (looksLikeHttp && looksLikeImageUrl) return t

  if ((typeof window === 'undefined')) return ''

  const { default: QRCode } = await import('qrcode')
  return QRCode.toDataURL(t, {
    width: 220,
    margin: 2,
    color: { dark: '#000000', light: '#ffffff' }
  })
}
