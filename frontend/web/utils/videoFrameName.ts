function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function pad3(value: number): string {
  return String(value).padStart(3, '0')
}

/** capturedAtMs 是视频内时间点；at 仅用于生成易识别的墙钟时间。 */
export function formatVideoFrameName(
  sourceLabel: string,
  capturedAtMs: number,
  at: Date = new Date()
): string {
  const label = String(sourceLabel || '分镜').trim() || '分镜'
  const milliseconds = Math.max(0, Math.floor(Number(capturedAtMs) || 0))
  const timePart = `${pad2(Math.floor(milliseconds / 1000))}.${pad3(milliseconds % 1000)}`
  const stamp =
    `${at.getFullYear()}${pad2(at.getMonth() + 1)}${pad2(at.getDate())}` +
    `${pad2(at.getHours())}${pad2(at.getMinutes())}`
  return `${label}-视频帧[${timePart}]-${stamp}`
}
