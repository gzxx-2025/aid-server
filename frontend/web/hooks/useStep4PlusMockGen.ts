/**
 * 第四步及之后（分镜脚本 / 分镜视频 / 配音等）本地模拟开关。
 * 默认关闭；设 NEXT_PUBLIC_MOCK_STEP4_PLUS_GEN=1 可开启短延迟模拟。
 * 非 React Hook（纯环境变量读取），故不以 use 前缀命名。
 */
export function getStep4PlusMockGen(): { enabled: boolean } {
  if (typeof window === 'undefined') {
    return { enabled: false }
  }
  const env = process.env.NEXT_PUBLIC_MOCK_STEP4_PLUS_GEN
  if (env === '0' || env === 'false') return { enabled: false }
  if (env === '1' || env === 'true') return { enabled: true }
  return { enabled: false }
}
