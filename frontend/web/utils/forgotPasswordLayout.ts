/** 找回密码页布局常量（Tailwind class 写在标签上；禁止为本页再开 CSS / fitScale） */

export const FORGOT_PASSWORD_ACCENT = 'var(--home-cyan, #4AE7FD)'
export const FORGOT_PASSWORD_SUCCESS = '#52C41A'

/** 主区铺满 home-main-route，整块（标题+步进+卡片）水平垂直居中 */
export const FORGOT_PASSWORD_PAGE_CLASS =
  'forgot-password-page relative flex h-full min-h-full w-full flex-1 flex-col items-center justify-center overflow-x-hidden px-4 py-8 md:px-8'

export const FORGOT_PASSWORD_GLOW_CLASS =
  'pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_55%_at_50%_18%,rgba(74,231,253,0.14)_0%,rgba(14,89,250,0.06)_42%,transparent_70%)]'

export const FORGOT_PASSWORD_STACK_CLASS =
  'relative z-[1] flex w-full max-w-[45rem] flex-col items-center gap-10 md:gap-14'

export const FORGOT_PASSWORD_TITLE_CLASS =
  'm-0 text-center text-2xl font-medium leading-8 tracking-[0.02em] text-white md:text-[2rem] md:leading-8'

export const FORGOT_PASSWORD_STEPPER_CLASS =
  'flex w-full flex-wrap items-center justify-center gap-y-3'

/** 蓝湖卡片 720×484；圆角 8px；表单在卡内水平垂直居中 */
export const FORGOT_PASSWORD_CARD_CLASS =
  'box-border flex w-full min-h-[28rem] max-w-[45rem] flex-col items-center justify-center rounded-lg border border-[rgba(74,231,253,0.08)] bg-[#202839] px-6 py-10 sm:px-12 md:min-h-[30.25rem] md:px-[8.75rem]'

/** 字段组与主按钮间距 80px（蓝湖：输入底→按钮顶） */
export const FORGOT_PASSWORD_FORM_CLASS =
  'm-0 flex w-full max-w-[27.5rem] flex-col items-stretch gap-20'

export const FORGOT_PASSWORD_FIELDS_CLASS = 'flex w-full flex-col gap-6'

export const FORGOT_PASSWORD_CODE_BTN_CLASS =
  'cursor-pointer whitespace-nowrap border-0 bg-transparent p-0 text-sm font-normal leading-5 text-white hover:opacity-80 disabled:cursor-not-allowed disabled:opacity-60'

export const FORGOT_PASSWORD_SUBMIT_CLASS =
  'auth-dark-submit auth-dark-submit--grad shrink-0'

export const FORGOT_PASSWORD_DONE_CLASS =
  'flex w-full flex-col items-center justify-center gap-6 py-6'

export const FORGOT_PASSWORD_STEPPER_LINE_CLASS =
  'mx-6 block h-0 w-[4.5rem] shrink-0 border-t border-dashed sm:w-[6.875rem]'
