/** 媒体 Blob 校验：拦截 SPA HTML / JSON 等非媒体响应，避免喂给 WebAV MP4Clip。 */

const HTML_OR_JSON_TYPE = /text\/html|application\/json/i
const MEDIA_TYPE_HINT =
  /^(video|audio)\//i
const FTYP_MARK = [0x66, 0x74, 0x79, 0x70] // ftyp

export function isRejectedMediaContentType(contentType: string | null | undefined): boolean {
  return HTML_OR_JSON_TYPE.test(String(contentType || ''))
}

export function hasMediaContentTypeHint(contentType: string | null | undefined): boolean {
  const type = String(contentType || '').trim()
  if (!type) return false
  if (isRejectedMediaContentType(type)) return false
  return MEDIA_TYPE_HINT.test(type) || /application\/octet-stream/i.test(type)
}

/** 在前 64 字节内查找 ISO BMFF `ftyp` box，识别常见 MP4/MOV。 */
export function bufferLooksLikeMp4(header: Uint8Array): boolean {
  if (header.length < 8) return false
  const limit = Math.min(header.length - 4, 64)
  for (let index = 0; index <= limit; index += 1) {
    if (
      header[index] === FTYP_MARK[0] &&
      header[index + 1] === FTYP_MARK[1] &&
      header[index + 2] === FTYP_MARK[2] &&
      header[index + 3] === FTYP_MARK[3]
    ) {
      return true
    }
  }
  return false
}

export async function blobLooksLikeMp4(blob: Blob): Promise<boolean> {
  if (!blob || blob.size < 8) return false
  if (isRejectedMediaContentType(blob.type)) return false
  const header = new Uint8Array(await blob.slice(0, 64).arrayBuffer())
  return bufferLooksLikeMp4(header)
}

/**
 * 判断 Blob 是否可作为 WebAV / 播放器用媒体。
 * - 明确拒绝 HTML/JSON
 * - 优先认 MP4 ftyp；无 ftyp 时若 Content-Type 像媒体仍放行（兼容部分音频）
 */
export async function isUsableMediaBlob(blob: Blob | null | undefined): Promise<boolean> {
  if (!blob || blob.size <= 0) return false
  if (isRejectedMediaContentType(blob.type)) return false
  if (await blobLooksLikeMp4(blob)) return true
  return hasMediaContentTypeHint(blob.type) && blob.size > 1024
}
