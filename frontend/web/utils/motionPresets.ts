/** 展示页（首页、作品库等）— 稍强、有电影感 */
export const MOTION_SHOWCASE = {
  duration: 0.5,
  ease: 'power2.out',
  stagger: 0.06,
  y: 28,
  scaleFrom: 0.96
} as const

/** 创作流程 — 仅淡入，不做位移（大内容区位移会像「抖动」） */
export const MOTION_CREATE = {
  duration: 0.22,
  ease: 'power1.out',
  /** 默认 0：创作页禁止纵向位移动画 */
  y: 0
} as const

export type MotionGsap = typeof import('gsap').gsap

type MotionTarget = string | Element | Element[] | NodeListOf<Element>

export function prefersReducedMotion(): boolean {
  if (!(typeof window !== 'undefined')) return true
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export type StaggerRevealOverrides = Partial<{
  duration: number
  ease: string
  stagger: number
  y: number
  scaleFrom: number
}>

/**
 * 列表卡片错落入场（展示页配方）。
 * @returns tween 实例；减弱动画时返回 null
 */
export function staggerReveal(
  gsapInstance: MotionGsap,
  targets: MotionTarget,
  overrides: StaggerRevealOverrides = {}
) {
  const duration = overrides.duration ?? MOTION_SHOWCASE.duration
  const ease = overrides.ease ?? MOTION_SHOWCASE.ease
  const stagger = overrides.stagger ?? MOTION_SHOWCASE.stagger
  const y = overrides.y ?? MOTION_SHOWCASE.y
  const scaleFrom = overrides.scaleFrom ?? MOTION_SHOWCASE.scaleFrom

  if (prefersReducedMotion()) {
    gsapInstance.set(targets, { autoAlpha: 1, y: 0, scale: 1 })
    return null
  }

  return gsapInstance.fromTo(
    targets,
    { autoAlpha: 0, y, scale: scaleFrom },
    {
      autoAlpha: 1,
      y: 0,
      scale: 1,
      duration,
      ease,
      stagger,
      clearProps: 'transform'
    }
  )
}

export type FadeUpEnterOverrides = Partial<{
  duration: number
  ease: string
  y: number
}>

/**
 * 创作页内容进入：默认仅 opacity 淡入。
 * 不用 autoAlpha（避免 visibility 切换导致大面板抖动），默认无 y 位移。
 * @returns tween 实例；减弱动画时返回 null
 */
export function fadeUpEnter(
  gsapInstance: MotionGsap,
  target: MotionTarget,
  overrides: FadeUpEnterOverrides = {}
) {
  const duration = overrides.duration ?? MOTION_CREATE.duration
  const ease = overrides.ease ?? MOTION_CREATE.ease
  const y = overrides.y ?? MOTION_CREATE.y

  if (prefersReducedMotion()) {
    gsapInstance.set(target, { opacity: 1, y: 0 })
    return null
  }

  const fromVars: Record<string, number> = { opacity: 0 }
  const toVars: Record<string, number | string | boolean> = {
    opacity: 1,
    duration,
    ease,
    overwrite: 'auto',
    clearProps: 'opacity'
  }

  if (y !== 0) {
    fromVars.y = y
    toVars.y = 0
    toVars.clearProps = 'opacity,transform'
  }

  return gsapInstance.fromTo(target, fromVars, toVars)
}
