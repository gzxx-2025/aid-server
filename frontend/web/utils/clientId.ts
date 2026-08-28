/** 生成仅用于前端临时列表键的 ID；不作为后端业务主键。 */
export function createClientId(prefix = ''): string {
  const token =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
  return prefix ? `${prefix}-${token}` : token
}
