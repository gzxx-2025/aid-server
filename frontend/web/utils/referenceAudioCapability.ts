/**
 * 视频模型参考音频 capability 解析与上传前校验
 * 字段契约见 /api/user/model/listByFunc capability
 * 本文件保持无 ~/ 依赖，便于 node:test 直接跑。
 */

export const MODEL_NO_REF_AUDIO_TIP = '该模型不支持音频文件，请移除后重试'

export interface ReferenceAudioCapability {
  supportsReferenceAudio: boolean
  maxReferenceAudios: number
  referenceAudioMinDurationSeconds: number
  referenceAudioMaxDurationSeconds: number
  referenceAudioMaxTotalDurationSeconds: number
  referenceAudioFormats: string[]
}

export interface ReferenceAudioDurationFormat {
  durationMs?: number | null
  audioFormat?: string | null
}

export type ValidateReferenceAudioResult =
  | { ok: true }
  | { ok: false; message: string }

function readNonNegInt(raw: unknown): number {
  const n = Number(raw)
  if (!Number.isFinite(n) || n < 0) return 0
  return Math.floor(n)
}

function normalizeFormat(raw: string): string {
  return String(raw || '')
    .trim()
    .replace(/^\./, '')
    .toLowerCase()
}

function resolveCapabilityRecord(item?: { capability?: unknown } | null): Record<string, unknown> {
  const raw = item?.capability as unknown
  if (!raw) return {}
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : {}
    } catch {
      return {}
    }
  }
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Record<string, unknown>
  }
  return {}
}

/** 从模型项解析参考音频能力；不支持时其余字段为 0 / [] */
export function parseReferenceAudioCapability(
  item?: { capability?: unknown } | null
): ReferenceAudioCapability {
  const cap = resolveCapabilityRecord(item)
  const supports = cap.supportsReferenceAudio === true
  if (!supports) {
    return {
      supportsReferenceAudio: false,
      maxReferenceAudios: 0,
      referenceAudioMinDurationSeconds: 0,
      referenceAudioMaxDurationSeconds: 0,
      referenceAudioMaxTotalDurationSeconds: 0,
      referenceAudioFormats: []
    }
  }
  const formats = Array.isArray(cap.referenceAudioFormats)
    ? [
        ...new Set(
          (cap.referenceAudioFormats as unknown[])
            .map((f) => normalizeFormat(String(f ?? '')))
            .filter(Boolean)
        )
      ]
    : []
  return {
    supportsReferenceAudio: true,
    maxReferenceAudios: readNonNegInt(cap.maxReferenceAudios),
    referenceAudioMinDurationSeconds: readNonNegInt(cap.referenceAudioMinDurationSeconds),
    referenceAudioMaxDurationSeconds: readNonNegInt(cap.referenceAudioMaxDurationSeconds),
    referenceAudioMaxTotalDurationSeconds: readNonNegInt(cap.referenceAudioMaxTotalDurationSeconds),
    referenceAudioFormats: formats
  }
}

function durationSecondsOf(item: ReferenceAudioDurationFormat): number {
  const ms = Number(item.durationMs)
  if (!Number.isFinite(ms) || ms <= 0) return 0
  return ms / 1000
}

/** 新增一条参考音频前的本地校验（数量 / 格式 / 单段与总时长） */
export function validateReferenceAudioAdd(opts: {
  capability: ReferenceAudioCapability
  existing: ReferenceAudioDurationFormat[]
  next: ReferenceAudioDurationFormat
}): ValidateReferenceAudioResult {
  const { capability, existing, next } = opts
  if (!capability.supportsReferenceAudio) {
    return { ok: false, message: MODEL_NO_REF_AUDIO_TIP }
  }

  const maxCount = capability.maxReferenceAudios
  if (maxCount > 0 && existing.length >= maxCount) {
    return { ok: false, message: '参考音频数量已达上限' }
  }

  const fmt = normalizeFormat(String(next.audioFormat || ''))
  const allowed = capability.referenceAudioFormats
  if (allowed.length > 0) {
    if (!fmt || !allowed.includes(fmt)) {
      return {
        ok: false,
        message: `仅支持 ${allowed.join('、')} 格式`
      }
    }
  }

  const nextSec = durationSecondsOf(next)
  const minSec = capability.referenceAudioMinDurationSeconds
  const maxSec = capability.referenceAudioMaxDurationSeconds
  if (nextSec > 0) {
    if (minSec > 0 && nextSec < minSec) {
      return { ok: false, message: '参考音频时长不符合当前模型要求' }
    }
    if (maxSec > 0 && nextSec > maxSec) {
      return { ok: false, message: '参考音频时长不符合当前模型要求' }
    }
  }

  const totalMax = capability.referenceAudioMaxTotalDurationSeconds
  if (totalMax > 0) {
    const existingTotal = existing.reduce((sum, a) => sum + durationSecondsOf(a), 0)
    if (existingTotal + nextSec > totalMax) {
      return { ok: false, message: '参考音频时长不符合当前模型要求' }
    }
  }

  return { ok: true }
}

/** 已有音频时，目标模型是否允许切换 */
export function canSwitchModelWithReferenceAudio(
  target: ReferenceAudioCapability,
  hasAnyReferenceAudio: boolean
): ValidateReferenceAudioResult {
  if (hasAnyReferenceAudio && !target.supportsReferenceAudio) {
    return { ok: false, message: MODEL_NO_REF_AUDIO_TIP }
  }
  return { ok: true }
}
