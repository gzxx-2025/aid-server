/** 剧本上传：与 POST /api/user/script/upload 对齐的前端校验 */

export const SCRIPT_UPLOAD_ACCEPT = '.txt,text/plain'

export function isScriptTxtFileName(fileName: string): boolean {
  return (fileName || '').trim().toLowerCase().endsWith('.txt')
}

const BINARY_MAGIC: Array<{ name: string; bytes: number[]; offset?: number }> = [
  { name: 'PNG', bytes: [0x89, 0x50, 0x4e, 0x47] },
  { name: 'JPEG', bytes: [0xff, 0xd8, 0xff] },
  { name: 'GIF87a', bytes: [0x47, 0x49, 0x46, 0x38, 0x37, 0x61] },
  { name: 'GIF89a', bytes: [0x47, 0x49, 0x46, 0x38, 0x39, 0x61] },
  { name: 'PDF', bytes: [0x25, 0x50, 0x44, 0x46] },
  { name: 'ZIP', bytes: [0x50, 0x4b, 0x03, 0x04] },
  { name: 'DOC', bytes: [0xd0, 0xcf, 0x11, 0xe0] },
  { name: 'WEBP', bytes: [0x57, 0x45, 0x42, 0x50], offset: 8 }
]

function matchMagic(buf: Uint8Array, magic: (typeof BINARY_MAGIC)[number]): boolean {
  const offset = magic.offset ?? 0
  if (buf.length < offset + magic.bytes.length) return false
  return magic.bytes.every((b, i) => buf[offset + i] === b)
}

/** 读取文件头做二进制嗅探（与服务端规则对齐，最终仍以服务端为准） */
export async function assertScriptPlainTextFile(file: File): Promise<void> {
  const head = new Uint8Array(await file.slice(0, 8192).arrayBuffer())
  if (!head.length) return

  if (head.includes(0)) {
    throw new Error('内容非文本')
  }

  if (head.length >= 12 && head[0] === 0x52 && head[1] === 0x49 && head[2] === 0x46 && head[3] === 0x46) {
    if (matchMagic(head, BINARY_MAGIC.find((m) => m.name === 'WEBP')!)) {
      throw new Error('内容非文本')
    }
  }

  for (const magic of BINARY_MAGIC) {
    if (magic.name === 'WEBP') continue
    if (matchMagic(head, magic)) {
      throw new Error('内容非文本')
    }
  }

  let controlCount = 0
  for (const b of head) {
    const isAllowedWhitespace = b === 0x09 || b === 0x0a || b === 0x0d
    const isControl = (b <= 0x1f || b === 0x7f) && !isAllowedWhitespace
    if (isControl) controlCount += 1
  }
  if (controlCount / head.length > 0.05) {
    throw new Error('内容非文本')
  }
}

export function validateScriptUploadFile(file: File): string | null {
  if (!isScriptTxtFileName(file.name)) {
    return '格式不支持，请上传 .txt 纯文本文件'
  }
  return null
}
