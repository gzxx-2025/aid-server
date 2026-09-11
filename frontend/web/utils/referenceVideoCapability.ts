import { resolveCapabilityRecord } from '~/utils/modelCapability'
import type { UserModelListItem } from '~/types/business-api'

export const MODEL_NO_REF_VIDEO_TIP = '当前模型不支持参考视频，请先切换模型'

export interface ReferenceVideoCapability {
  supportsReferenceVideo: boolean
  maxReferenceVideos: number
  referenceVideoFormats: string[]
  referenceVideoMinDurationSeconds: number
  referenceVideoMaxDurationSeconds: number
  referenceVideoMaxTotalDurationSeconds: number
}

function readNumber(raw: unknown): number {
  const n = Number(raw)
  return Number.isFinite(n) ? n : 0
}

function readFormats(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return [...new Set(raw.map((value) => String(value ?? '').trim().replace(/^\./, '').toLowerCase()).filter(Boolean))]
}

/** 与服务端 capability_json 同源解析，不在前端写死供应商或模型名单。 */
export function parseReferenceVideoCapability(
  item?: { capability?: unknown } | null
): ReferenceVideoCapability {
  const capability = resolveCapabilityRecord(item as UserModelListItem | null)
  const max = readNumber(capability.maxReferenceVideos)
  const hasConfiguredMax = Object.prototype.hasOwnProperty.call(capability, 'maxReferenceVideos')
  const allowedInputs = Array.isArray(capability.allowedInputs)
    ? capability.allowedInputs.map((value) => String(value ?? '').trim().toLowerCase())
    : []
  const supports =
    capability.supportsVideoInput === true ||
    allowedInputs.includes('video') ||
    max > 0 ||
    max === -1

  return {
    supportsReferenceVideo: supports && (!hasConfiguredMax || max !== 0),
    maxReferenceVideos: max,
    referenceVideoFormats: readFormats(capability.referenceVideoFormats),
    referenceVideoMinDurationSeconds: readNumber(capability.referenceVideoMinDurationSeconds),
    referenceVideoMaxDurationSeconds: readNumber(capability.referenceVideoMaxDurationSeconds),
    referenceVideoMaxTotalDurationSeconds: readNumber(capability.referenceVideoMaxTotalDurationSeconds)
  }
}

export function validateReferenceVideoSelection(opts: {
  capability: ReferenceVideoCapability
  existingCount: number
  incomingCount?: number
}): { ok: true } | { ok: false; message: string } {
  if (!opts.capability.supportsReferenceVideo) {
    return { ok: false, message: MODEL_NO_REF_VIDEO_TIP }
  }
  const max = opts.capability.maxReferenceVideos
  const incoming = Math.max(1, Math.floor(Number(opts.incomingCount) || 1))
  if (max > 0 && opts.existingCount + incoming > max) {
    return { ok: false, message: `当前模型最多支持 ${max} 个参考视频` }
  }
  return { ok: true }
}

export function validateReferenceVideoCount(
  capability: ReferenceVideoCapability,
  count: number
): { ok: true } | { ok: false; message: string } {
  const total = Math.max(0, Math.floor(Number(count) || 0))
  if (!total) return { ok: true }
  if (!capability.supportsReferenceVideo) {
    return { ok: false, message: MODEL_NO_REF_VIDEO_TIP }
  }
  if (capability.maxReferenceVideos > 0 && total > capability.maxReferenceVideos) {
    return {
      ok: false,
      message: `当前模型最多支持 ${capability.maxReferenceVideos} 个参考视频`
    }
  }
  return { ok: true }
}
