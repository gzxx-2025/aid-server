/** 视频首帧海报缓存：内存即时命中 + IndexedDB 跨刷新复用 */

const DB_NAME = 'aid-video-poster-cache'
const DB_VERSION = 1
const STORE = 'posters'
const MEMORY_MAX = 80

const memory = new Map<string, Blob>()
const objectUrlByKey = new Map<string, string>()

let dbPromise: Promise<IDBDatabase> | null = null

export function normalizeVideoPosterKey(videoUrl: string): string {
  const raw = String(videoUrl || '').trim()
  if (!raw) return ''
  try {
    const u = new URL(raw, 'https://local.invalid')
    u.hash = ''
    if (u.origin === 'https://local.invalid') {
      return `${u.pathname}${u.search}`
    }
    return u.toString()
  } catch {
    return raw.split('#')[0]!.trim()
  }
}

export function clearVideoPosterMemoryCache() {
  for (const url of objectUrlByKey.values()) {
    try {
      URL.revokeObjectURL(url)
    } catch {
      /* ignore */
    }
  }
  objectUrlByKey.clear()
  memory.clear()
}

export function getVideoPosterFromMemory(videoUrl: string): Blob | null {
  const key = normalizeVideoPosterKey(videoUrl)
  if (!key) return null
  return memory.get(key) ?? null
}

export function setVideoPosterInMemory(videoUrl: string, blob: Blob) {
  const key = normalizeVideoPosterKey(videoUrl)
  if (!key || !blob) return
  if (memory.has(key)) {
    memory.delete(key)
  }
  memory.set(key, blob)
  // 淘汰只丢 Map 引用，不 revoke：仍在绑定的 <img> 不能被误伤
  while (memory.size > MEMORY_MAX) {
    const oldest = memory.keys().next().value
    if (oldest == null) break
    if (objectUrlByKey.has(oldest)) {
      // 仍有存活 object URL 的条目跳过淘汰，移到队尾
      const blobKeep = memory.get(oldest)!
      memory.delete(oldest)
      memory.set(oldest, blobKeep)
      break
    }
    memory.delete(oldest)
  }
}

/** 同步：内存命中则返回可绑定的 object URL（同 key 复用） */
export function getVideoPosterObjectUrlFromMemory(videoUrl: string): string {
  const key = normalizeVideoPosterKey(videoUrl)
  if (!key) return ''
  const cachedUrl = objectUrlByKey.get(key)
  if (cachedUrl) return cachedUrl
  const blob = memory.get(key)
  if (!blob) return ''
  const url = URL.createObjectURL(blob)
  objectUrlByKey.set(key, url)
  return url
}

function canUseIdb(): boolean {
  return typeof indexedDB !== 'undefined'
}

function openDb(): Promise<IDBDatabase> {
  if (!canUseIdb()) return Promise.reject(new Error('no indexedDB'))
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onerror = () => {
        dbPromise = null
        reject(req.error || new Error('idb open failed'))
      }
      req.onsuccess = () => resolve(req.result)
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE)
        }
      }
    })
  }
  return dbPromise
}

export async function readVideoPosterFromIdb(videoUrl: string): Promise<Blob | null> {
  const key = normalizeVideoPosterKey(videoUrl)
  if (!key || !canUseIdb()) return null
  try {
    const db = await openDb()
    return await new Promise((resolve) => {
      const tx = db.transaction(STORE, 'readonly')
      const store = tx.objectStore(STORE)
      const req = store.get(key)
      req.onsuccess = () => {
        const val = req.result
        if (val instanceof Blob) resolve(val)
        else if (val?.blob instanceof Blob) resolve(val.blob)
        else resolve(null)
      }
      req.onerror = () => resolve(null)
    })
  } catch {
    return null
  }
}

export async function writeVideoPosterToIdb(videoUrl: string, blob: Blob): Promise<void> {
  const key = normalizeVideoPosterKey(videoUrl)
  if (!key || !blob || !canUseIdb()) return
  try {
    const db = await openDb()
    await new Promise<void>((resolve) => {
      const tx = db.transaction(STORE, 'readwrite')
      const store = tx.objectStore(STORE)
      store.put(blob, key)
      tx.oncomplete = () => resolve()
      tx.onerror = () => resolve()
      tx.onabort = () => resolve()
    })
  } catch {
    /* ignore */
  }
}

/** 先内存后 IDB；命中 IDB 时回填内存 */
export async function resolveVideoPosterBlob(videoUrl: string): Promise<Blob | null> {
  const mem = getVideoPosterFromMemory(videoUrl)
  if (mem) return mem
  const fromIdb = await readVideoPosterFromIdb(videoUrl)
  if (fromIdb) {
    setVideoPosterInMemory(videoUrl, fromIdb)
    return fromIdb
  }
  return null
}

export async function persistVideoPoster(videoUrl: string, blob: Blob): Promise<string> {
  setVideoPosterInMemory(videoUrl, blob)
  void writeVideoPosterToIdb(videoUrl, blob)
  return getVideoPosterObjectUrlFromMemory(videoUrl)
}
