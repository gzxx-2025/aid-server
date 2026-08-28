import { normalizeVideoPosterKey } from '~/utils/videoPosterCache'

export type VideoPosterCaptureFn = (videoUrl: string) => Promise<Blob | null>
export type VideoPosterScheduleFn = (fn: () => void) => () => void

type Job = {
  key: string
  url: string
  priority: number
  resolvers: Array<(blob: Blob | null) => void>
  cancelled: boolean
}

export type VideoPosterQueue = {
  enqueue: (videoUrl: string, priority?: number) => Promise<Blob | null>
  cancel: (videoUrls: string[]) => void
}

function defaultSchedule(fn: () => void): () => void {
  if (typeof requestIdleCallback === 'function') {
    const id = requestIdleCallback(() => fn(), { timeout: 800 })
    return () => cancelIdleCallback(id)
  }
  const t = setTimeout(fn, 0)
  return () => clearTimeout(t)
}

export function createVideoPosterQueue(opts: {
  capture: VideoPosterCaptureFn
  schedule?: VideoPosterScheduleFn
  concurrency?: number
}): VideoPosterQueue {
  const concurrency = Math.max(1, opts.concurrency ?? 1)
  const schedule = opts.schedule ?? defaultSchedule
  const pending: Job[] = []
  const inFlight = new Map<string, Promise<Blob | null>>()
  let active = 0
  let cancelSchedule: (() => void) | null = null

  const settle = (job: Job, blob: Blob | null) => {
    for (const r of job.resolvers) r(blob)
    job.resolvers.length = 0
  }

  const pump = () => {
    while (active < concurrency && pending.length) {
      pending.sort((a, b) => b.priority - a.priority)
      const job = pending.shift()!
      if (job.cancelled) {
        settle(job, null)
        continue
      }
      active += 1
      const run = (async () => {
        try {
          if (job.cancelled) return null
          return await opts.capture(job.url)
        } catch {
          return null
        }
      })()
      inFlight.set(job.key, run)
      void run.then((blob) => {
        inFlight.delete(job.key)
        active = Math.max(0, active - 1)
        settle(job, job.cancelled ? null : blob)
        if (pending.length) schedulePump()
      })
    }
  }

  const schedulePump = () => {
    if (cancelSchedule) return
    let ranSync = false
    const cancel = schedule(() => {
      ranSync = true
      cancelSchedule = null
      pump()
    })
    // 同步 schedule：回调已执行，勿留下 truthy cancel 挡住后续 pump
    if (!ranSync) cancelSchedule = cancel
  }

  return {
    enqueue(videoUrl: string, priority = 0): Promise<Blob | null> {
      const key = normalizeVideoPosterKey(videoUrl)
      const url = String(videoUrl || '').trim()
      if (!key || !url) return Promise.resolve(null)

      const existingInFlight = inFlight.get(key)
      if (existingInFlight) {
        // 进行中：附带等待同一 promise
        return existingInFlight
      }

      const existing = pending.find((j) => j.key === key)
      if (existing) {
        existing.priority = Math.max(existing.priority, priority)
        existing.cancelled = false
        return new Promise((resolve) => {
          existing.resolvers.push(resolve)
        })
      }

      return new Promise((resolve) => {
        pending.push({
          key,
          url,
          priority,
          resolvers: [resolve],
          cancelled: false
        })
        schedulePump()
      })
    },

    cancel(videoUrls: string[]) {
      const keys = new Set(
        videoUrls.map((u) => normalizeVideoPosterKey(u)).filter(Boolean)
      )
      if (!keys.size) return
      for (const job of pending) {
        if (keys.has(job.key)) job.cancelled = true
      }
    }
  }
}

/** 兼容旧测试/调用名 */
export function cancelVideoPosterJobs(queue: VideoPosterQueue, videoUrls: string[]) {
  queue.cancel(videoUrls)
}

