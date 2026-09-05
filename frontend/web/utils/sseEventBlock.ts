/** 解析一个 SSE 事件块（以空行分隔），合并多行 data。 */
export function parseSseEventBlock(block: string): { event: string; data: string } | null {
  const lines = block.split('\n')
  let eventName = ''
  const dataLines: string[] = []
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '')
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^\s/, ''))
    }
  }
  const data = dataLines.join('\n')
  if (!eventName && !data) return null
  return { event: eventName || 'message', data }
}
