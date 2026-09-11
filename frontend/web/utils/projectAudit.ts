/** 作品审核 / 公开锁 — 与 `components/steps/接口.md` 对齐 */

export const PROJECT_PUBLIC_LOCK_MSG = '请先关闭项目公开'

export type AuditMediaRow = {
  status?: number | null
  pendingVideoUrl?: string | null
}

export function hasPendingReauditVideo(row?: AuditMediaRow | null): boolean {
  return Boolean(String(row?.pendingVideoUrl || '').trim())
}

export type FullVideoExportRow = {
  finalVideoUrl?: string | null
  pendingVideoUrl?: string | null
  exportStatus?: number | null
}

/** 是否已有过成功导出的成片（含待审新片）；从未导出则发布入口应禁用 */
export function hasExportedFullVideo(row?: FullVideoExportRow | null): boolean {
  if (Number(row?.exportStatus) === 2) return true
  return (
    Boolean(String(row?.finalVideoUrl || '').trim()) ||
    Boolean(String(row?.pendingVideoUrl || '').trim())
  )
}

/**
 * 工程已改、当前成片已过期：exportStatus=0（待重新导出），且仍有历史成片/待审片。
 * 注意：即便仍残留 pendingVideoUrl，只要 status=0 也视为过期（改过内容后必须重新导出）。
 */
export function isFullVideoExportStale(row?: FullVideoExportRow | null): boolean {
  if (Number(row?.exportStatus) === 2) return false
  if (Number(row?.exportStatus) !== 0) return false
  return (
    Boolean(String(row?.finalVideoUrl || '').trim()) ||
    Boolean(String(row?.pendingVideoUrl || '').trim())
  )
}

/** 当前成片可直接用于发布（导出成功；或导出成功态下的待审新片） */
export function canPublishWithCurrentFullVideo(row?: FullVideoExportRow | null): boolean {
  // 工程已改必须先重新导出，不能拿旧的 pending/final 直接发
  if (Number(row?.exportStatus) === 0) return false
  return (
    Number(row?.exportStatus) === 2 ||
    Boolean(String(row?.pendingVideoUrl || '').trim())
  )
}

export const PUBLISH_NEED_EXPORT_TIP = '请先导出完整视频后再发布至案例广场'
export const PUBLISH_STALE_EXPORT_TIP =
  '当前发布的视频还是旧版内容，请先导出新版的完成内容后再发布'

/** 发布至案例广场不可用时的悬停/点击提示；可发布返回 null */
export function resolvePublishToCasePlazaBlockReason(
  row?: FullVideoExportRow | null
): string | null {
  if (!hasExportedFullVideo(row)) return PUBLISH_NEED_EXPORT_TIP
  if (isFullVideoExportStale(row) || !canPublishWithCurrentFullVideo(row)) {
    return PUBLISH_STALE_EXPORT_TIP
  }
  return null
}

/**
 * 成品预览点「导出/发布」前：是否需确认「新版会替换旧版」。
 * - 已发布/过审后又改工程（exportStatus=0）优先提示
 * - 导出成功且有待审新片（pendingVideoUrl）时提示；同一 URL 确认过后不再提示
 */
export function shouldConfirmReplacePublishedVideo(payload: {
  pendingVideoUrl?: string | null
  ackedPendingVideoUrl?: string | null
  finalVideoUrl?: string | null
  exportStatus?: number | null
  hasPublishedHistory?: boolean
  staleExportAcked?: boolean
}): boolean {
  if (
    payload.hasPublishedHistory &&
    isFullVideoExportStale({
      finalVideoUrl: payload.finalVideoUrl,
      pendingVideoUrl: payload.pendingVideoUrl,
      exportStatus: payload.exportStatus
    }) &&
    !payload.staleExportAcked
  ) {
    return true
  }
  // 仅「当前导出成功」的待审新片走替换确认；工程已改(status=0)上面已处理
  if (Number(payload.exportStatus) !== 2) return false
  const pending = String(payload.pendingVideoUrl || '').trim()
  if (!pending) return false
  const acked = String(payload.ackedPendingVideoUrl || '').trim()
  return pending !== acked
}

/** 是否允许提交（或重新提交）审核 */
export function canSubmitAudit(row?: AuditMediaRow | null): boolean {
  const status = row?.status
  if (status === 3) return false
  if (status === 4) return hasPendingReauditVideo(row)
  return true
}

/**
 * 发布至案例广场前是否需要先调 submit-audit。
 * - 已过审且无待审新片 → 跳过提审，只 update + publish
 * - 已过审但有 pendingVideoUrl / 其它可提审态 → 需提审
 * - 审核中 → 不可提审（调用方应拦截）
 */
export function needsSubmitAuditBeforePublish(row?: AuditMediaRow | null): boolean {
  return canSubmitAudit(row)
}

export function auditSubmitBlockedReason(row?: AuditMediaRow | null): string | null {
  const status = row?.status
  if (status === 3) return '作品审核中，请耐心等待'
  if (status === 4 && !hasPendingReauditVideo(row)) return '作品已通过审核'
  return null
}

export function isProjectPublicLockError(err: unknown): boolean {
  const msg = String((err as { msg?: string })?.msg || (err as Error)?.message || '').trim()
  return msg.includes(PROJECT_PUBLIC_LOCK_MSG)
}

export function projectPublicLockUserHint(): string {
  return '作品已公开，请先在作品库关闭公开后再修改'
}

/** 作品卡片只展示进行中和失败；发布到案例广场即代表已通过，不重复展示通过状态。 */
export function auditStatusBadgeLabel(status?: number | null): string | null {
  if (status === 3) return '审核中'
  if (status === 5) return '审核失败'
  return null
}

export type AuditBadgeTone = 'reviewing' | 'failed'

export function auditStatusBadgeTone(status?: number | null): AuditBadgeTone | null {
  if (status === 3) return 'reviewing'
  if (status === 5) return 'failed'
  return null
}

/** 项目是否仍发布在案例广场；兼容接口可能返回的字符串、数字或布尔值。 */
export function isProjectPublished(isPublic: unknown): boolean {
  return isPublic === true || String(isPublic ?? '').trim() === '1'
}

/** 导出成功后的可播放地址：重新导出过审场景优先预览待审新片 */
export function resolveExportPlaybackUrl(payload: {
  finalVideoUrl?: string | null
  pendingVideoUrl?: string | null
  needReaudit?: boolean | null
}): string {
  const pending = String(payload.pendingVideoUrl || '').trim()
  const finalUrl = String(payload.finalVideoUrl || '').trim()
  if (payload.needReaudit && pending) return pending
  return finalUrl || pending
}
