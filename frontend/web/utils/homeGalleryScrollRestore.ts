/** 棣栭〉妗堜緥骞垮満锛氳繘鍏ヨ鎯呭墠淇濆瓨婊氬姩锛岃繑鍥炲悗鎭㈠鍒扮偣鍑讳綔鍝侀檮杩?*/

export const HOME_GALLERY_SCROLL_STORAGE_KEY = 'aid:home-gallery-scroll:v1'

export type HomeGalleryScrollSnapshot = {
  scrollTop: number
  workId: number | null
  savedAt: number
}

const DEFAULT_MAX_AGE_MS = 30 * 60 * 1000

function canUseSessionStorage(): boolean {
  try {
    return typeof sessionStorage !== 'undefined' && !!sessionStorage
  } catch {
    return false
  }
}

export function getHomeMainScrollEl(): HTMLElement | null {
  if (typeof document === 'undefined') return null
  return document.querySelector('.home-new-main') as HTMLElement | null
}

export function readHomeMainScrollTop(): number {
  const el = getHomeMainScrollEl()
  if (!el) return 0
  const top = Number(el.scrollTop)
  return Number.isFinite(top) && top > 0 ? top : 0
}

export function writeHomeMainScrollTop(scrollTop: number): void {
  const el = getHomeMainScrollEl()
  if (!el) return
  el.scrollTop = Math.max(0, Number(scrollTop) || 0)
}

export function saveHomeGalleryScrollSnapshot(snapshot: HomeGalleryScrollSnapshot): void {
  if (!canUseSessionStorage()) return
  try {
    sessionStorage.setItem(
      HOME_GALLERY_SCROLL_STORAGE_KEY,
      JSON.stringify({
        scrollTop: Math.max(0, Number(snapshot.scrollTop) || 0),
        workId:
          snapshot.workId != null && Number.isFinite(Number(snapshot.workId)) && Number(snapshot.workId) > 0
            ? Number(snapshot.workId)
            : null,
        savedAt: Number(snapshot.savedAt) || Date.now()
      })
    )
  } catch {
    // ignore quota / private mode
  }
}

export function peekHomeGalleryScrollSnapshot(): HomeGalleryScrollSnapshot | null {
  if (!canUseSessionStorage()) return null
  try {
    const raw = sessionStorage.getItem(HOME_GALLERY_SCROLL_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<HomeGalleryScrollSnapshot>
    const scrollTop = Math.max(0, Number(parsed.scrollTop) || 0)
    const workIdRaw = Number(parsed.workId)
    const workId = Number.isFinite(workIdRaw) && workIdRaw > 0 ? workIdRaw : null
    const savedAt = Number(parsed.savedAt) || 0
    return { scrollTop, workId, savedAt }
  } catch {
    return null
  }
}

export function consumeHomeGalleryScrollSnapshot(
  maxAgeMs = DEFAULT_MAX_AGE_MS
): HomeGalleryScrollSnapshot | null {
  const snapshot = peekHomeGalleryScrollSnapshot()
  if (!canUseSessionStorage()) return null
  try {
    sessionStorage.removeItem(HOME_GALLERY_SCROLL_STORAGE_KEY)
  } catch {
    // ignore
  }
  if (!snapshot) return null
  if (!snapshot.savedAt || Date.now() - snapshot.savedAt > maxAgeMs) return null
  return snapshot
}

/**
 * 鍏堟仮澶嶇粷瀵?scrollTop锛堢€戝竷娴佽櫄鎷熷垪琛ㄦ湭鎸傝浇鐩爣鍗℃椂涔熷彲鐢級锛? * 鑻ュ崱鐗囧凡鍦?DOM 鍐嶅井璋冨埌灞呬腑銆? */
export function restoreHomeGalleryScroll(snapshot: HomeGalleryScrollSnapshot): void {
  const el = getHomeMainScrollEl()
  if (!el) return

  writeHomeMainScrollTop(snapshot.scrollTop)

  if (snapshot.workId == null) return
  const card = document.querySelector(
    `[data-gallery-work-id="${snapshot.workId}"]`
  ) as HTMLElement | null
  if (card) {
    card.scrollIntoView({ block: 'center', inline: 'nearest' })
  }
}
