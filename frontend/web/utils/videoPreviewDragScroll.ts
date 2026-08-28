/**
 * 拖拽位移换算秒数：含时间轴自动滚动补偿。
 * scrollLeft 增大（内容右移可视）时，等价于指针在内容坐标系向右移动。
 */
export function dragPointerDeltaSec(input: {
  clientX: number
  startX: number
  scrollLeft: number
  originScrollLeft: number
  pxPerSec?: number
}): number {
  const pxPerSec = input.pxPerSec ?? 90
  const dx =
    input.clientX - input.startX + (input.scrollLeft - input.originScrollLeft)
  return dx / pxPerSec
}
