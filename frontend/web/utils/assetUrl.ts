/**
 * 统一静态资源导入产物：Next webpack 下 svg/img 导入可能是 StaticImageData（{ src }）
 * 或 URL 字符串，img src / CSS url() 处统一用本函数取字符串。
 */
export function assetUrl(mod: unknown): string {
  if (typeof mod === 'string') return mod
  if (mod && typeof mod === 'object' && 'src' in mod) {
    const src = (mod as { src?: unknown }).src
    if (typeof src === 'string') return src
  }
  return ''
}
