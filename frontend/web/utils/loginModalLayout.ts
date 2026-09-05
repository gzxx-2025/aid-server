/** 登录弹窗设计稿尺寸（蓝湖 登录-密码登录 @1920） */
export const LOGIN_MODAL_DESIGN_WIDTH = 1440
export const LOGIN_MODAL_DESIGN_HEIGHT = 1024
/** 相对视口上下/左右预留，避免贴边 */
export const LOGIN_MODAL_VIEWPORT_PAD = 56

/**
 * 登录弹窗共用 class。
 * 双栏态由 CSS/JS 按设计稿等比缩小适配视口（仅弹窗，非整页 zoom）。
 */
export const LOGIN_MODAL_OVERLAY_CLASS =
  'login-modal-overlay fixed inset-0 z-[5000] flex items-center justify-center overflow-y-auto bg-[var(--login-modal-overlay)]'

export const LOGIN_MODAL_FIT_SHELL_CLASS = 'login-modal-fit-shell shrink-0'

export const LOGIN_MODAL_FRAME_CLASS =
  'login-modal-frame relative flex overflow-hidden rounded-2xl bg-[var(--login-modal-bg)] shadow-2xl'

export const LOGIN_MODAL_VISUAL_CLASS =
  'login-modal-visual relative h-full min-w-0 w-[62.5%] overflow-hidden'

export const LOGIN_MODAL_FORM_CLASS =
  'login-modal-form relative flex h-full min-w-0 w-[37.5%] flex-col'

export const LOGIN_MODAL_BODY_CLASS =
  'flex min-h-0 flex-1 flex-col items-center justify-center gap-[4.5rem] overflow-y-auto px-10'

export const LOGIN_MODAL_CLOSE_CLASS =
  'absolute right-6 top-6 z-10 flex h-8 w-8 cursor-pointer items-center justify-center rounded border-0 bg-transparent p-0 text-white hover:opacity-80'

export const LOGIN_MODAL_FIELD_WRAP_CLASS = 'auth-dark-field-wrap auth-dark-field-wrap--ghost'

export const LOGIN_MODAL_FIELD_CLASS = 'auth-dark-field-input'

export const LOGIN_MODAL_FIELDS_CLASS = 'flex w-full flex-col gap-8'

export const LOGIN_MODAL_AUX_ROW_CLASS =
  'flex h-4 w-full shrink-0 items-center justify-end text-xs leading-none text-white'

export const LOGIN_MODAL_SUBMIT_CLASS =
  'auth-dark-submit auth-dark-submit--light h-12 w-full cursor-pointer border-0 text-lg font-medium'

export const LOGIN_MODAL_STACK_CLASS = 'flex w-3/4 max-w-sm min-w-0 flex-col items-stretch gap-8'

export const LOGIN_MODAL_TITLE_CLASS = 'text-center text-2xl font-semibold leading-8 text-white'

export const LOGIN_MODAL_BRAND_CLASS = 'flex flex-col items-center gap-2'

export const LOGIN_MODAL_HINT_CLASS =
  'min-h-10 w-full text-center text-xs leading-relaxed text-[var(--login-modal-muted)]'

export const LOGIN_MODAL_ALT_ROW_CLASS = 'flex items-stretch justify-center gap-20'

export const LOGIN_MODAL_ALT_DIVIDER_CLASS =
  'block h-12 w-px shrink-0 self-stretch bg-[var(--login-modal-divider)]'

/** 计算登录弹窗相对设计稿的适配缩放（≤1，不放大） */
export function computeLoginModalFitScale(
  viewportWidth: number,
  viewportHeight: number,
  pad = LOGIN_MODAL_VIEWPORT_PAD
): number {
  const availW = Math.max(0, viewportWidth - pad)
  const availH = Math.max(0, viewportHeight - pad)
  const scale = Math.min(availW / LOGIN_MODAL_DESIGN_WIDTH, availH / LOGIN_MODAL_DESIGN_HEIGHT, 1)
  return Math.round(scale * 1000) / 1000
}
