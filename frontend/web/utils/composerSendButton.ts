export type ComposerSendMode = 'home' | 'agent'

export type ComposerSendVisualState = 'empty' | 'active' | 'loading' | 'pause' | 'resume'

export const COMPOSER_SEND_EMPTY_TOOLTIP = '请输入内容'

export function resolveComposerSendVisualState(input: {
  mode: ComposerSendMode
  hasContent: boolean
  loading?: boolean
  generating?: boolean
  showResume?: boolean
}): ComposerSendVisualState {
  if (input.mode === 'home' && input.loading) return 'loading'
  if (input.mode === 'agent' && input.generating) return 'pause'
  if (input.mode === 'agent' && input.showResume) return 'resume'
  if (!input.hasContent) return 'empty'
  return 'active'
}

export function isComposerSendClickDisabled(input: {
  visualState: ComposerSendVisualState
  disabled?: boolean
  pauseDisabled?: boolean
}): boolean {
  if (input.visualState === 'empty' || input.visualState === 'loading') return true
  if (input.visualState === 'pause') return Boolean(input.pauseDisabled)
  if (input.visualState === 'resume') return false
  return Boolean(input.disabled)
}
