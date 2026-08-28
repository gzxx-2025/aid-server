import { captureVideoPosterBlobWithRetry } from '~/utils/videoPosterCapture'
import {
  getVideoPosterObjectUrlFromMemory,
  persistVideoPoster,
  resolveVideoPosterBlob
} from '~/utils/videoPosterCache'
import { createVideoPosterQueue, type VideoPosterQueue } from '~/utils/videoPosterQueue'

export const VIDEO_POSTER_PRIORITY = {
  activeTab: 100,
  selectedHistory: 90,
  tab: 50,
  history: 10
} as const

let sharedQueue: VideoPosterQueue | null = null

function getQueue(): VideoPosterQueue {
  if (!sharedQueue) {
    sharedQueue = createVideoPosterQueue({
      capture: (url) => captureVideoPosterBlobWithRetry(url)
    })
  }
  return sharedQueue
}

/** 同步内存命中 */
export function peekVideoPosterObjectUrl(videoUrl: string): string {
  return getVideoPosterObjectUrlFromMemory(videoUrl)
}

/**
 * 解析可绑定的首帧 object URL：内存 → IDB → 空闲抽帧队列。
 * 失败返回 ''（由 UI 走单格 video 兜底）。
 */
export async function ensureVideoPosterObjectUrl(
  videoUrl: string,
  priority = VIDEO_POSTER_PRIORITY.history
): Promise<string> {
  const url = String(videoUrl || '').trim()
  if (!url) return ''

  const mem = getVideoPosterObjectUrlFromMemory(url)
  if (mem) return mem

  const fromStore = await resolveVideoPosterBlob(url)
  if (fromStore) {
    return persistVideoPoster(url, fromStore)
  }

  const captured = await getQueue().enqueue(url, priority)
  if (!captured) return ''
  return persistVideoPoster(url, captured)
}

export function cancelPendingVideoPosters(videoUrls: string[]) {
  getQueue().cancel(videoUrls)
}

/** 仅测试 */
export function __resetEnsureVideoPosterForTests() {
  sharedQueue = null
}
