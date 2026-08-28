/**
 * Quill 异步挂载守卫：Tab v-if 销毁组件后，禁止对已失效容器 new Quill，
 * 避免抛出 Invalid Quill container 被 Nuxt 打成整页 500。
 */

export type AsyncMountGate = {
  /** 开始一次挂载，返回本次 token；同实例再次挂载会作废旧 token */
  begin: () => number
  /** 卸载 / 取消：使进行中的异步挂载全部失效 */
  dispose: () => void
  /** token 是否仍对应当前未卸载的那次挂载 */
  isCurrent: (token: number) => boolean
}

export function createAsyncMountGate(): AsyncMountGate {
  let generation = 0
  let disposed = true

  return {
    begin() {
      disposed = false
      generation += 1
      return generation
    },
    dispose() {
      disposed = true
      generation += 1
    },
    isCurrent(token: number) {
      return !disposed && token === generation
    }
  }
}

/** 容器仍在文档中，才允许交给 Quill */
export function isValidQuillContainer(el: unknown): el is HTMLElement {
  if (el == null || typeof el !== 'object') return false
  const node = el as { nodeType?: number; isConnected?: boolean }
  return node.nodeType === 1 && node.isConnected === true
}

export function shouldProceedQuillMount(input: {
  token: number
  gate: Pick<AsyncMountGate, 'isCurrent'>
  container: unknown
}): boolean {
  return input.gate.isCurrent(input.token) && isValidQuillContainer(input.container)
}

/**
 * 安全创建 Quill：容器失效或构造抛 Invalid Quill container 时返回 null，不向外抛错。
 */
export function createQuillSafely<T>(
  create: (container: HTMLElement) => T,
  container: unknown
): T | null {
  if (!isValidQuillContainer(container)) return null
  try {
    return create(container)
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    if (/invalid quill container/i.test(msg)) return null
    throw err
  }
}
