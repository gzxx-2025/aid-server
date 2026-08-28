import type { UserAssetRpsUpdateMainRequest } from '../types/business-api'
import {
extractPropPayloadFromSettingHtml,
extractScenePayloadFromSettingHtml,
parseCharacterBasicsSection,
parseCharacterDisplayName,
parsePropDisplayName,
parseSceneDisplayName,
profilePlainFromSettingHtml,
sectionsLookStructured,
splitSettingHtmlBySectionTitles,
splitTagLikePlain
} from './characterSettingProfileCore'
import { expectedAppearancesFromPlain } from './rpsUpdateMainPayload'
function isFilledStr(v: unknown): boolean {
  if (v == null) return false
  const s = String(v).trim()
  return s.length > 0
}

function isValidSentinel(v: unknown): boolean {
  return isFilledStr(v)
}

/** 从设定 HTML 提取角色 update-main 平铺字段（兼容 Quill 锁定小节 span） */
export function extractCharacterPayloadFromSettingHtml(
  html: string
): Partial<UserAssetRpsUpdateMainRequest> {
  const sections = splitSettingHtmlBySectionTitles(html || '')
  const payload: Partial<UserAssetRpsUpdateMainRequest> = {}

  if (sectionsLookStructured(sections)) {
    if ('角色介绍' in sections) payload.introduction = sections['角色介绍'] ?? ''
    if ('别名' in sections) {
      const raw = sections['别名'] ?? ''
      payload.aliasesName = splitTagLikePlain(raw).join(',')
    }
    if ('基本信息' in sections) {
      Object.assign(payload, parseCharacterBasicsSection(sections['基本信息'] ?? ''))
    }
    if ('视觉关键词' in sections) {
      payload.visualKeywords = splitTagLikePlain(sections['视觉关键词'] ?? '')
    }
    if ('性格标签' in sections) {
      payload.personalityTags = splitTagLikePlain(sections['性格标签'] ?? '')
    }
    if ('推荐色系' in sections) {
      payload.suggestedColors = splitTagLikePlain(sections['推荐色系'] ?? '')
    }
    if ('主要识别特征' in sections) {
      payload.primaryIdentifier = (sections['主要识别特征'] ?? '').trim()
    }
    if ('子形象列表' in sections) {
      const plain = sections['子形象列表'] ?? ''
      const j = expectedAppearancesFromPlain(plain)
      payload.expectedAppearances = j !== undefined ? j : plain.trim() === '' ? [] : undefined
    }
    return payload
  }

  const { introduction } = profilePlainFromSettingHtml(html || '')
  return { introduction }
}

/** 场景 / 角色 / 道具设定 HTML → update-main 平铺字段（供 SceneCharacterProp 保存复用） */
export function buildUpdateMainPayloadFromSettingHtml(
  html: string,
  variant: 'character' | 'scene' | 'prop'
): Partial<UserAssetRpsUpdateMainRequest> {
  if (variant === 'scene') return extractScenePayloadFromSettingHtml(html)
  if (variant === 'character') return extractCharacterPayloadFromSettingHtml(html)
  return extractPropPayloadFromSettingHtml(html)
}

/**
 * 按接口 2.5.1 校验合并态必填项；返回首条错误文案（与后端 v2.33.0 错误信息对齐）。
 */
export function getCharacterSettingValidationError(
  html: string,
  assetDisplayName: string
): string | null {
  const name = parseCharacterDisplayName(assetDisplayName)
  if (!name) return '请填写名称'

  const p = extractCharacterPayloadFromSettingHtml(html)

  if (!isValidSentinel(p.gender)) return '请填写性别'
  if (!isValidSentinel(p.ageRange)) return '请填写年龄'
  if (!isFilledStr(p.introduction)) return '请填写介绍'
  if (!isValidSentinel(p.archetype)) return '请填写原型'
  if (!isValidSentinel(p.eraPeriod)) return '请填写时代'
  if (!isValidSentinel(p.occupation)) return '请填写职业'
  if (!isValidSentinel(p.roleLevel)) return '请填写层级'
  if (p.costumeTier == null || !Number.isFinite(Number(p.costumeTier))) return '请填写服装'
  if (!isValidSentinel(p.socialClass)) return '请填写阶层'

  const vk = p.visualKeywords
  if (!Array.isArray(vk) || vk.length === 0) return '请填写关键词'

  const pt = p.personalityTags
  if (!Array.isArray(pt) || pt.length === 0) return '请填写性格'

  const sc = p.suggestedColors
  if (!Array.isArray(sc) || sc.length === 0) return '请填写配色'

  if (!isFilledStr(p.primaryIdentifier)) return '请填写特征'

  return null
}

/**
 * 按接口 2.5.2 校验场景设定必填项；返回首条错误文案（与后端 v2.33.0 对齐）。
 */
export function getSceneSettingValidationError(html: string, assetDisplayName: string): string | null {
  const name = parseSceneDisplayName(assetDisplayName)
  if (!name) return '请填写名称'

  const p = extractScenePayloadFromSettingHtml(html)
  if (!isFilledStr(p.summary)) return '请填写概要'
  if (!isFilledStr(p.introduction)) return '请填写描述'

  const slotsRaw = p.availableSlots
  if (!isFilledStr(slotsRaw)) return '请填写槽位'
  try {
    const arr = JSON.parse(String(slotsRaw)) as unknown
    if (!Array.isArray(arr)) return '槽位格式错'
    const nonEmpty = arr.filter((x) => typeof x === 'string' && String(x).trim().length > 0)
    if (nonEmpty.length === 0) return '请填写槽位'
  } catch {
    return '槽位格式错'
  }

  if (p.hasCrowd !== 0 && p.hasCrowd !== 1) return '请填写人群'
  if (p.crowdDescription === undefined) return '请填写人群'

  return null
}

/**
 * 按接口 2.5.3 校验道具设定必填项；返回首条错误文案（与后端 v2.33.0 对齐）。
 */
export function getPropSettingValidationError(html: string, assetDisplayName: string): string | null {
  const name = parsePropDisplayName(assetDisplayName)
  if (!name) return '请填写名称'

  const p = extractPropPayloadFromSettingHtml(html)
  if (!isFilledStr(p.summary)) return '请填写概要'
  if (!isFilledStr(p.introduction)) return '请填写描述'

  return null
}
