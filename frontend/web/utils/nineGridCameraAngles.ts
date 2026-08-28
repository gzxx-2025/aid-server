/** 九宫格默认机位提示词（与接口文档示例一致，格1~格9 对应 3×3 从左到右、从上到下） */
export const DEFAULT_NINE_GRID_ANGLE_PROMPTS: readonly string[] = [
  '正面平视，半身近景，居中构图',
  '左侧 30°，半身近景',
  '右侧 30°，半身近景',
  '正面平视，全身远景',
  '正面平视，特写头部',
  '俯视 45°，半身',
  '仰视 30°，半身',
  '背面平视，全身',
  '侧面 90°，全身'
] as const

export const NINE_GRID_CELL_LABELS = [
  '格1',
  '格2',
  '格3',
  '格4',
  '格5',
  '格6',
  '格7',
  '格8',
  '格9'
] as const

export function createDefaultNineGridAngles(): string[] {
  return [...DEFAULT_NINE_GRID_ANGLE_PROMPTS]
}

/**
 * 九宫格 3D 预览：球面上 9 个固定机位（yaw 绕 Y、pitch 仰角，单位度）
 * 布局：上 2、中上 2、水平 2、下 3（与固定九宫格示意图一致）
 */
export const NINE_GRID_SPHERE_CAMERA_POSITIONS: ReadonlyArray<{
  yaw: number
  pitch: number
}> = [
  { yaw: 38, pitch: 32 },
  { yaw: -38, pitch: 32 },
  { yaw: 55, pitch: 14 },
  { yaw: -55, pitch: 14 },
  { yaw: 88, pitch: 0 },
  { yaw: -88, pitch: 0 },
  { yaw: 36, pitch: -34 },
  { yaw: 0, pitch: -38 },
  { yaw: -36, pitch: -34 }
]
