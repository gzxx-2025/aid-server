/**
 * 将 rps.forms 映射为与 formIndex 对齐的 formId 数组。
 * 无效 id 用 0 占位，禁止 filter 压缩下标（否则「其他形态」槽位会串到错误列）。
 */
export function mapRpsFormsToAlignedFormIds(
  forms: Array<{ id?: number | null } | null | undefined> | null | undefined
): number[] {
  return (forms ?? []).map((f) => {
    const n = Number(f?.id)
    return Number.isFinite(n) && n > 0 ? n : 0
  })
}

/** 按 formId 反查形态下标；找不到返回 -1 */
export function findAlignedFormIndexByFormId(
  formIds: Array<number | null | undefined> | null | undefined,
  formId: number
): number {
  const fid = Number(formId)
  if (!Number.isFinite(fid) || fid <= 0) return -1
  const ids = formIds ?? []
  for (let i = 0; i < ids.length; i++) {
    if (Number(ids[i]) === fid) return i
  }
  return -1
}
