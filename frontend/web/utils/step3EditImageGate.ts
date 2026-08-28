/**
 * 素材准备列表：自动生成进行中时禁用「编辑场景图 / 编辑形态图」。
 * 状态来自 step3GenVisual（按作品分桶），切项目返回后仍 generating 则继续禁用。
 * 若列表 status 被 reconcile 提前清掉，只要该形态 formId 仍在进行中任务内，也应禁用。
 */

export const STEP3_EDIT_IMAGE_GENERATING_TOOLTIP =
  '图片正在生成中，请等待生成完成后再编辑'

/** 列表自动生成进行中 → 禁用编辑图入口 */
export function isStep3ListEditImageDisabled(status: unknown): boolean {
  return String(status ?? '')
    .trim()
    .toLowerCase() === 'generating'
}

/** status=generating，或该形态 formId 仍挂在进行中生图任务上 */
export function isStep3ListEditImageDisabledForSlot(input: {
  status: unknown
  formIdUnderActiveGen?: boolean
}): boolean {
  if (input.formIdUnderActiveGen) return true
  return isStep3ListEditImageDisabled(input.status)
}
