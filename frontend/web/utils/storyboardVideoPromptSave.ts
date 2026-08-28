/** 多参方向手动保存（接口 10.7）：须含 # 主题 / # 运镜 / # 风格 */
export function validateMultiParamVideoPromptPlain(
  plain: string
): { ok: true } | { ok: false; message: string } {
  const text = String(plain ?? '').trim()
  if (!text) {
    return { ok: false, message: '提示词不能为空' }
  }
  if (text.length > 3024) {
    return { ok: false, message: '提示词不规范' }
  }
  // if (!text.includes('# 主题') || !text.includes('# 运镜') || !text.includes('# 风格')) {
  //   return { ok: false, message: '提示词不规范' }
  // }
  return { ok: true }
}

/** 图生方向提示词（接口 10.8 产出约 180 字纯字符串，含镜头运动/画面描述等结构化字段） */
export function validateImageToVideoPromptPlain(
  plain: string
): { ok: true } | { ok: false; message: string } {
  const text = String(plain ?? '').trim()
  if (!text) {
    return { ok: false, message: '提示词不能为空' }
  }
  if (text.length > 4000) {
    return { ok: false, message: '提示词过长' }
  }
  if (!text.includes('镜头运动：') || !text.includes('画面描述：')) {
    return { ok: false, message: '提示词不规范' }
  }
  return { ok: true }
}

/** 宫格方向提示词（接口 video-prompt-grid，写 video_prompt_image；产出含切换镜头/参考分镜图等） */
export function validateGridVideoPromptPlain(
  plain: string
): { ok: true } | { ok: false; message: string } {
  const text = String(plain ?? '').trim()
  if (!text) {
    return { ok: false, message: '提示词不能为空' }
  }
  if (text.length > 4000) {
    return { ok: false, message: '提示词过长' }
  }
  return { ok: true }
}

/** 首尾帧方向：videoPrompt 可空（回落 aid_storyboard.video_prompt），非空时校验长度 */
export function validateEdgeVideoPromptPlain(
  plain: string
): { ok: true } | { ok: false; message: string } {
  const text = String(plain ?? '').trim()
  if (!text) return { ok: true }
  if (text.length > 3024) {
    return { ok: false, message: '提示词过长' }
  }
  return { ok: true }
}

/** @deprecated 请使用 validateMultiParamVideoPromptPlain 或 validateImageToVideoPromptPlain */
export function validateStoryboardVideoPromptPlain(
  plain: string
): { ok: true } | { ok: false; message: string } {
  return validateImageToVideoPromptPlain(plain)
}
