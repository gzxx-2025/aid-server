/**
 * 多机位弹窗：水平旋转(Yaw)、垂直角度(Pitch)、焦距 与拼接提示词映射。
 * 对照 `components/steps/接口.md` 多机位映射表；供前端拼接与后续接口入参预留。
 */

/** 水平旋转（Yaw）：弹窗 0～360°，按文档取相对「正面」的最小夹角 0～180° 再映射 */
export function mapYawDegreesToPromptTags(yawDegrees: number): string {
  let y = ((Number(yawDegrees) % 360) + 360) % 360
  if (y > 180) y = 360 - y
  const a = y
  if (a <= 15) return 'front view, straight-on'
  if (a <= 45) return 'slight angle, off-center view'
  if (a <= 75) return 'three-quarter view, angled view'
  if (a <= 105) return 'side view, profile view'
  if (a <= 135) return 'rear three-quarter view, back and side view'
  if (a <= 160) return 'mostly back view'
  return 'back view, from behind'
}

/** 垂直角度（Pitch）：弹窗约 -30～60°，按文档区间映射（与接口表边界对齐） */
export function mapPitchDegreesToPromptTags(pitchDegrees: number): string {
  const p = Number(pitchDegrees)
  if (p >= 70) return "bird's-eye view, top-down view, zenith view"
  if (p >= 35) return 'high angle shot, extreme from above'
  if (p >= 15) return 'slight high angle, from above'
  if (p > -15 && p < 15) return 'eye-level shot, straight-on, neutral angle'
  if (p >= -35) return 'slight low angle, from below'
  if (p >= -70) return 'low angle shot, extreme from below'
  return "worm's-eye view, nadir view, directly below"
}

/**
 * 焦距：弹窗滑块 0=远景、10=特写；接口表参数越大越「广」。
 * 故将 UI 值反转为文档 0～80 再选档。
 */
export function mapFocalUi0to10ToPromptTags(focalUi0to10: number): string {
  const t = Math.max(0, Math.min(10, Number(focalUi0to10)))
  const f = ((10 - t) / 10) * 80
  if (f <= 15) return 'extreme close-up, macro shot, detail shot'
  if (f <= 35) return 'close-up shot, close range'
  if (f <= 60) return 'medium shot, mid-range view'
  return 'wide shot, full view, complete view'
}

export type MultiAnglePromptParts = {
  yawTags: string
  pitchTags: string
  focalTags: string
  /** 三段英文提示词逗号拼接，便于整段提交后端 */
  concat: string
}

/** 多机位弹窗「开始生图」emit / 预留生图接口入参（与 MultiAngleCameraModal 一致） */
export type MultiAngleGeneratePayload = {
  /** 默认 single；编辑分镜图弹窗内为固定九宫格多机位 */
  mode?: 'single' | 'nineGridFixed'
  horizontalRotation: number
  verticalAngle: number
  focalLength: number
  imageUrl: string
  angles: Array<{ angle: string; url: string }>
  multiAnglePromptConcat: string
  multiAnglePromptParts: MultiAnglePromptParts
  /** mode=nineGridFixed 时提交 9 条机位提示词 */
  nineGridAngles?: string[]
}

export function buildMultiAnglePromptParts(
  yawDegrees: number,
  pitchDegrees: number,
  focalUi0to10: number
): MultiAnglePromptParts {
  const yawTags = mapYawDegreesToPromptTags(yawDegrees)
  const pitchTags = mapPitchDegreesToPromptTags(pitchDegrees)
  const focalTags = mapFocalUi0to10ToPromptTags(focalUi0to10)
  const concat = [yawTags, pitchTags, focalTags].join(', ')
  return { yawTags, pitchTags, focalTags, concat }
}
