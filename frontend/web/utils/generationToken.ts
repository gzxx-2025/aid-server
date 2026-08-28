export interface GenerationToken {
  current: number
}

/**
 * 推进异步流程代次。调用方保存返回值，并在异步边界后与 current 比较以丢弃过期结果。
 */
export function advanceGenerationToken(token: GenerationToken): number {
  token.current += 1
  return token.current
}

/** 仅使既有异步流程失效，不需要读取新代次时使用。 */
export function invalidateGenerationToken(token: GenerationToken): void {
  token.current += 1
}

/** 在异步命令中替换 ref 容器值。 */
export function replaceRefValue<T>(ref: { current: T }, value: T): void {
  ref.current = value
}
