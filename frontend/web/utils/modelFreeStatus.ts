export interface ModelFreeStatusSource {
  isFree?: boolean | null
}

/** 免费角标只认接口明确返回的 isFree=true，不从价格字段推断。 */
export function shouldShowModelFreeBadge(model: ModelFreeStatusSource): boolean {
  return model.isFree === true
}
