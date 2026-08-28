/** 将响应式 / 特殊原型对象转为可安全 JSON 序列化的纯对象（SSR payload、持久化用） */
export function plainDeep<T>(value: T): T {
  if (value === undefined) return value
  return JSON.parse(JSON.stringify(value)) as T
}
