const pluginMap = {
  ScrollTrigger: () => import('gsap/ScrollTrigger'),
  Flip: () => import('gsap/Flip'),
  ScrollToPlugin: () => import('gsap/ScrollToPlugin'),
  TextPlugin: () => import('gsap/TextPlugin')
} as const

type PluginName = keyof typeof pluginMap

type GsapCore = typeof import('gsap').gsap

let gsapLoader: Promise<GsapCore> | null = null

/** 动态加载 GSAP 核心，避免首页同步解析动画库。 */
export function loadGsap(): Promise<GsapCore> {
  if (!gsapLoader) {
    gsapLoader = import('gsap').then((mod) => mod.gsap)
  }
  return gsapLoader
}

/**
 * GSAP 入口：SSR 安全使用方式见 useMotion；核心与插件均按需懒加载。
 */
export function useGSAP() {
  async function lazyLoadPlugin(name: PluginName) {
    const gsap = await loadGsap()
    const mod = (await pluginMap[name]()) as Record<string, unknown>
    const plugin = mod[name]
    if (plugin) {
      gsap.registerPlugin(plugin as object)
    }
    return plugin
  }

  return {
    loadGsap,
    lazyLoadPlugin
  }
}
