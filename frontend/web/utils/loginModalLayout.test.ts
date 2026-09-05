import { describe, expect, it } from 'vitest'
import {
  computeLoginModalFitScale,
  LOGIN_MODAL_ALT_DIVIDER_CLASS,
  LOGIN_MODAL_ALT_ROW_CLASS,
  LOGIN_MODAL_AUX_ROW_CLASS,
  LOGIN_MODAL_BODY_CLASS,
  LOGIN_MODAL_CLOSE_CLASS,
  LOGIN_MODAL_DESIGN_HEIGHT,
  LOGIN_MODAL_DESIGN_WIDTH,
  LOGIN_MODAL_FIELD_CLASS,
  LOGIN_MODAL_FIELD_WRAP_CLASS,
  LOGIN_MODAL_FIELDS_CLASS,
  LOGIN_MODAL_FIT_SHELL_CLASS,
  LOGIN_MODAL_FORM_CLASS,
  LOGIN_MODAL_FRAME_CLASS,
  LOGIN_MODAL_HINT_CLASS,
  LOGIN_MODAL_OVERLAY_CLASS,
  LOGIN_MODAL_SUBMIT_CLASS,
  LOGIN_MODAL_VISUAL_CLASS
} from './loginModalLayout'
import { LOGIN_MODAL_ANTD_THEME } from './loginModalAntdTheme'

describe('login modal layout tokens', () => {
  it('uses a design-canvas frame with fit shell (no page-level vw zoom)', () => {
    expect(LOGIN_MODAL_DESIGN_WIDTH).toBe(1440)
    expect(LOGIN_MODAL_DESIGN_HEIGHT).toBe(1024)
    expect(LOGIN_MODAL_FRAME_CLASS).toContain('login-modal-frame')
    expect(LOGIN_MODAL_FIT_SHELL_CLASS).toContain('login-modal-fit-shell')
    expect(LOGIN_MODAL_OVERLAY_CLASS).toContain('login-modal-overlay')
    expect(LOGIN_MODAL_OVERLAY_CLASS).toContain('items-center')
    expect(LOGIN_MODAL_OVERLAY_CLASS).toContain('justify-center')
    expect(LOGIN_MODAL_OVERLAY_CLASS).not.toMatch(/75vw|94\.81dvh/)
  })

  it('uses shared auth-dark-field wrap class (not login-only)', () => {
    expect(LOGIN_MODAL_FIELD_WRAP_CLASS).toContain('auth-dark-field-wrap')
    expect(LOGIN_MODAL_FIELD_CLASS).toBe('auth-dark-field-input')
  })

  it('splits visual / form columns by flex percentages', () => {
    expect(LOGIN_MODAL_VISUAL_CLASS).toContain('w-[62.5%]')
    expect(LOGIN_MODAL_FORM_CLASS).toContain('w-[37.5%]')
  })

  it('vertically centers the form and keeps design spacing; close icon has no border', () => {
    expect(LOGIN_MODAL_BODY_CLASS).toContain('justify-center')
    expect(LOGIN_MODAL_BODY_CLASS).toContain('gap-[4.5rem]')
    expect(LOGIN_MODAL_FIELDS_CLASS).toContain('gap-8')
    expect(LOGIN_MODAL_CLOSE_CLASS).toContain('border-0')
    expect(LOGIN_MODAL_CLOSE_CLASS).not.toContain('!border-white')
    expect(LOGIN_MODAL_CLOSE_CLASS).not.toMatch(/(?:^|\s)!border(?:\s|$)/)
    expect(LOGIN_MODAL_CLOSE_CLASS).toContain('cursor-pointer')
    expect(LOGIN_MODAL_SUBMIT_CLASS).toContain('auth-dark-submit')
    expect(LOGIN_MODAL_SUBMIT_CLASS).toContain('auth-dark-submit--light')
    expect(LOGIN_MODAL_SUBMIT_CLASS).toContain('cursor-pointer')
  })

  it('keeps visual / form class hooks for compact CSS override', () => {
    expect(LOGIN_MODAL_VISUAL_CLASS).toContain('login-modal-visual')
    expect(LOGIN_MODAL_FORM_CLASS).toContain('login-modal-form')
  })

  it('stretches the alt-method divider to the tab height with wider gap', () => {
    expect(LOGIN_MODAL_ALT_ROW_CLASS).toContain('items-stretch')
    expect(LOGIN_MODAL_ALT_ROW_CLASS).toContain('gap-20')
    expect(LOGIN_MODAL_ALT_DIVIDER_CLASS).toContain('h-12')
  })

  it('reserves a fixed aux row so password / code tabs share height', () => {
    expect(LOGIN_MODAL_AUX_ROW_CLASS).toContain('h-4')
    expect(LOGIN_MODAL_HINT_CLASS).toContain('min-h-10')
  })
})

describe('computeLoginModalFitScale', () => {
  it('keeps scale 1 on 1920×1080 design viewport', () => {
    expect(computeLoginModalFitScale(1920, 1080)).toBe(1)
  })

  it('scales down on 1366×768 so the canvas fits with margins', () => {
    const scale = computeLoginModalFitScale(1366, 768)
    expect(scale).toBeLessThan(1)
    expect(scale).toBeGreaterThan(0.6)
    expect(LOGIN_MODAL_DESIGN_HEIGHT * scale).toBeLessThanOrEqual(768 - 56 + 0.5)
    expect(LOGIN_MODAL_DESIGN_WIDTH * scale).toBeLessThanOrEqual(1366 - 56 + 0.5)
  })

  it('never scales above 1 on large viewports', () => {
    expect(computeLoginModalFitScale(2560, 1440)).toBe(1)
    expect(computeLoginModalFitScale(3840, 2160)).toBe(1)
  })

  it('scales for high OS zoom CSS viewports (~1280×720 at 150%)', () => {
    const scale = computeLoginModalFitScale(1280, 720)
    expect(scale).toBeLessThan(0.75)
    expect(scale).toBeGreaterThan(0.5)
  })
})

describe('login modal antd theme', () => {
  it('uses muted border by default and brighter border on hover', () => {
    const input = LOGIN_MODAL_ANTD_THEME.components?.Input
    expect(input?.colorBorder).toBe('#8e97a5')
    expect(input?.hoverBorderColor).toBe('#ffffff')
    expect(input?.activeBorderColor).toBe('#ffffff')
    expect(input?.paddingInline).toBe(16)
    expect(input?.borderRadius).toBe(8)
    expect(LOGIN_MODAL_ANTD_THEME.components?.Button?.borderRadius).toBe(8)
  })
})
