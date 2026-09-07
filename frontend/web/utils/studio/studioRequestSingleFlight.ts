const studioRequestInflight = new Map<string, Promise<unknown>>()

/**
 * 同一画布作用域、节点与动作在请求结束前只允许一个执行者。
 * 重复入口复用首个 Promise，避免按钮、快捷键或重复挂载再次提交真实任务。
 */
export function runStudioRequestSingleFlight<T>(
  key: string,
  request: () => Promise<T>
): Promise<T> {
  const normalizedKey = String(key || '').trim()
  if (!normalizedKey) return request()

  const existing = studioRequestInflight.get(normalizedKey)
  if (existing) return existing as Promise<T>

  const promise: Promise<T> = Promise.resolve()
    .then(request)
    .finally(() => {
      if (studioRequestInflight.get(normalizedKey) === promise) {
        studioRequestInflight.delete(normalizedKey)
      }
    })
  studioRequestInflight.set(normalizedKey, promise)
  return promise
}
