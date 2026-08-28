/** 创作步骤：本地路由步骤与服务端 currentStep 对照缓存（sessionStorage） */

const PREFIX = 'vg-creation-step-sync:'

export interface CreationStepSyncCachePayload {
  localStep: number
  serverCurrentStep: number
  t: number
}

function key(projectId: number, episodeId: number | undefined) {
  return `${PREFIX}${projectId}:${episodeId ?? 'na'}`
}

export function writeCreationStepSyncCache(
  projectId: number,
  episodeId: number | undefined,
  localStep: number,
  serverCurrentStep: number
): void {
  if (typeof sessionStorage === 'undefined') return
  try {
    const payload: CreationStepSyncCachePayload = {
      localStep,
      serverCurrentStep,
      t: Date.now()
    }
    sessionStorage.setItem(key(projectId, episodeId), JSON.stringify(payload))
  } catch {
    /* ignore quota / private mode */
  }
}

export function readCreationStepSyncCache(
  projectId: number,
  episodeId: number | undefined
): CreationStepSyncCachePayload | null {
  if (typeof sessionStorage === 'undefined') return null
  try {
    const raw = sessionStorage.getItem(key(projectId, episodeId))
    if (!raw) return null
    const o = JSON.parse(raw) as CreationStepSyncCachePayload
    if (
      typeof o?.localStep !== 'number' ||
      typeof o?.serverCurrentStep !== 'number'
    ) {
      return null
    }
    return o
  } catch {
    return null
  }
}
