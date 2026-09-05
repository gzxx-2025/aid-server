import type { AspectRatioOption } from '~/utils/aspectRatioPicker'
import { fitAspectRatioIconSize } from '~/utils/aspectRatioPicker'
import './aspect-ratio-picker.css'

export type { AspectRatioOption }

/** 创作参数面板使用的比例缩略图。 */
export function AspectRatioShape({ value, size = 18 }: { value: string; size?: number }) {
  const box = fitAspectRatioIconSize(value, size)
  return (
    <span
      className="aspect-ratio-picker__shape"
      style={{ width: box.width, height: box.height }}
      aria-hidden="true"
    />
  )
}
