/**
 * 按 /auth/public-config 的 upload 块做上传前本地校验。
 * 未配置分类型时回退 globalMaxSizeMb / globalAllowedExtensions。
 */
import type { AuthUploadPublicConfig } from '~/types/business-api';

export type UploadLimitCheckResult =
  | { ok: true }
  | { ok: false; message: string }

function fileExt(fileName: string): string {
  const name = String(fileName || '').trim()
  const i = name.lastIndexOf('.')
  if (i < 0 || i === name.length - 1) return ''
  return name.slice(i + 1).toLowerCase()
}

function parseGlobalExts(raw: unknown): string[] {
  const s = String(raw || '').trim()
  if (!s) return []
  return s
    .split(/[,，\s]+/)
    .map((x) => x.replace(/^\./, '').trim().toLowerCase())
    .filter(Boolean)
}

function mbToBytes(mb: number): number {
  return Math.max(0, mb) * 1024 * 1024
}

/**
 * 校验单文件是否符合公开配置的上传限制。
 * config 为空时放行（由服务端兜底校验）。
 */
export function checkFileAgainstUploadLimits(
  file: File,
  config?: AuthUploadPublicConfig | null
): UploadLimitCheckResult {
  if (!file || !config) return { ok: true }

  const ext = fileExt(file.name)
  const typeLimits = Array.isArray(config.typeLimits) ? config.typeLimits : []

  if (typeLimits.length) {
    const hit = typeLimits.find((t) =>
      (Array.isArray(t.extensions) ? t.extensions : [])
        .map((e) => String(e || '').replace(/^\./, '').toLowerCase())
        .includes(ext)
    )
    if (!hit) {
      return { ok: false, message: '文件类型错误' }
    }
    const maxMb = Number(hit.maxSizeMb)
    if (Number.isFinite(maxMb) && maxMb > 0 && file.size > mbToBytes(maxMb)) {
      return { ok: false, message: `文件过大（${hit.name || '该类型'}上限 ${maxMb}MB）` }
    }
    return { ok: true }
  }

  const globalExts = parseGlobalExts(config.globalAllowedExtensions)
  if (globalExts.length && ext && !globalExts.includes(ext)) {
    return { ok: false, message: '文件类型错误' }
  }
  const globalMb = Number(config.globalMaxSizeMb)
  if (Number.isFinite(globalMb) && globalMb > 0 && file.size > mbToBytes(globalMb)) {
    return { ok: false, message: `文件过大（上限 ${globalMb}MB）` }
  }
  return { ok: true }
}

export function assertFilesAgainstUploadLimits(
  files: File[],
  config?: AuthUploadPublicConfig | null
): UploadLimitCheckResult {
  for (const file of files) {
    const r = checkFileAgainstUploadLimits(file, config)
    if (!r.ok) return r
  }
  return { ok: true }
}
