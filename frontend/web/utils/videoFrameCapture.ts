import { deflate } from 'pako'
import { fetchMediaStream } from './mediaFetch'
export type CapturedVideoFrame = {
  url: string
  name: string
  sourceVideoId?: string
  sourceLabel?: string
  capturedAtMs: number
}

const DEFAULT_FRAME_INTERVAL_SECONDS = 1 / 30
const MICROSECONDS_PER_SECOND = 1_000_000
  const DEFAULT_FRAME_INTERVAL_US = Math.round(DEFAULT_FRAME_INTERVAL_SECONDS * MICROSECONDS_PER_SECOND)
const PNG_SIGNATURE = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])
const PNG_COLOR_TYPE_RGBA = 6
const PNG_COMPRESSION_LEVEL = 6

interface TimelineFrameOptions {
  count?: number
  maxWidth?: number
  shouldContinue?: () => boolean
}

type TimelineThumb = { ts: number; img: Blob }

/** 将时间限制在视频可解码的帧范围内，避免精确 duration 落入结束黑帧。 */
export function clampVideoFrameTime(target: number, duration: number): number {
  const safeDuration = Number.isFinite(duration) ? Math.max(0, duration) : 0
  const lastFrameTime = Math.max(0, safeDuration - DEFAULT_FRAME_INTERVAL_SECONDS)
  const safeTarget = Number.isFinite(target) ? target : 0
  return Math.min(Math.max(0, safeTarget), lastFrameTime)
}

/**
 * 为 tick 空帧生成邻近时间候选。
 * WebAV 在精确时间点偶发返回 video=null（关键帧间隙 / 硬解降级），换邻近时间通常可解出。
 */
export function buildVideoFrameDecodeCandidates(targetUs: number, durationUs: number): number[] {
  const safeDuration = Number.isFinite(durationUs) ? Math.max(0, Math.floor(durationUs)) : 0
  if (safeDuration <= 0) return [0]

  // tick(t >= duration) 会直接返回 done 且无画面
  const lastUs = Math.max(0, safeDuration - 1)
  const clampUs = (value: number) => Math.min(Math.max(0, Math.round(value)), lastUs)
  const offsets = [
    0,
    DEFAULT_FRAME_INTERVAL_US,
    -DEFAULT_FRAME_INTERVAL_US,
    DEFAULT_FRAME_INTERVAL_US * 2,
    -DEFAULT_FRAME_INTERVAL_US * 2,
    DEFAULT_FRAME_INTERVAL_US * 3,
    1_000,
    -1_000
  ]

  const candidates: number[] = []
  const seen = new Set<number>()
  for (const offset of offsets) {
    const next = clampUs((Number.isFinite(targetUs) ? targetUs : 0) + offset)
    if (seen.has(next)) continue
    seen.add(next)
    candidates.push(next)
  }
  if (!seen.has(0)) candidates.push(0)
  return candidates
}

/** 从列表中均匀取样，保证时间轴缩略图数量稳定。 */
export function pickEvenlySpacedItems<T>(items: T[], count: number): T[] {
  const safeCount = Math.max(0, Math.floor(count))
  if (!items.length || safeCount <= 0) return []
  if (items.length <= safeCount) return items.slice()
  if (safeCount === 1) return [items[0]!]

  const picked: T[] = []
  for (let index = 0; index < safeCount; index += 1) {
    const sourceIndex = Math.round((index * (items.length - 1)) / (safeCount - 1))
    picked.push(items[sourceIndex]!)
  }
  return picked
}

function isMp4ClipParseError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error || '')
  return /MP4Clip stream is done, but not emit ready|MP4Clip stream not contain any sample/i.test(
    message
  )
}

async function loadVideoClip(sourceUrl: string) {
  const stream = await fetchMediaStream(sourceUrl)
  if (!stream) {
    throw new Error('视频读取失败，请检查媒体代理或 CDN 跨域配置')
  }

  const { MP4Clip } = await import('@webav/av-cliper')
  const clip = new MP4Clip(stream, { audio: false })
  try {
    await clip.ready
    if (!clip.meta.width || !clip.meta.height || !clip.meta.duration) {
      throw new Error('视频信息无效')
    }
    return clip
  } catch (error) {
    clip.destroy()
    if (isMp4ClipParseError(error)) {
      throw new Error('视频解析失败：未获得有效 MP4 数据（可能下载到了网页而非视频）', {
        cause: error
      })
    }
    throw error
  }
}

type LoadedVideoClip = Awaited<ReturnType<typeof loadVideoClip>>

interface RgbaFrame {
  pixels: Uint8Array
  width: number
  height: number
}

function createCrcTable(): Uint32Array {
  const table = new Uint32Array(256)
  for (let index = 0; index < table.length; index += 1) {
    let value = index
    for (let bit = 0; bit < 8; bit += 1) {
      value = (value & 1) === 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
    }
    table[index] = value >>> 0
  }
  return table
}

const CRC_TABLE = createCrcTable()

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff
  for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 0xff]! ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

function writeUint32(bytes: Uint8Array, offset: number, value: number) {
  new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).setUint32(offset, value >>> 0)
}

function createPngChunk(type: string, data: Uint8Array): Uint8Array {
  const typeBytes = new Uint8Array(4)
  for (let index = 0; index < typeBytes.length; index += 1) {
    typeBytes[index] = type.charCodeAt(index)
  }

  const chunk = new Uint8Array(12 + data.length)
  writeUint32(chunk, 0, data.length)
  chunk.set(typeBytes, 4)
  chunk.set(data, 8)

  const crcInput = new Uint8Array(typeBytes.length + data.length)
  crcInput.set(typeBytes)
  crcInput.set(data, typeBytes.length)
  writeUint32(chunk, chunk.length - 4, crc32(crcInput))
  return chunk
}

function concatBytes(parts: Uint8Array[]): Uint8Array {
  const output = new Uint8Array(parts.reduce((total, part) => total + part.length, 0))
  let offset = 0
  for (const part of parts) {
    output.set(part, offset)
    offset += part.length
  }
  return output
}

async function copyVideoFrameToRgba(frame: VideoFrame): Promise<RgbaFrame> {
  const width = frame.codedWidth || frame.displayWidth
  const height = frame.codedHeight || frame.displayHeight
  if (!width || !height) throw new Error('视频帧无效')

  const copyOptions: VideoFrameCopyToOptions = { format: 'RGBA' }
  const buffer = new Uint8Array(frame.allocationSize(copyOptions))
  const layouts = await frame.copyTo(buffer, copyOptions)
  const rowSize = width * 4
  const offset = layouts[0]?.offset || 0
  const stride = layouts[0]?.stride || rowSize
  if (offset === 0 && stride === rowSize && buffer.length === rowSize * height) {
    return { pixels: buffer, width, height }
  }

  const pixels = new Uint8Array(rowSize * height)
  for (let row = 0; row < height; row += 1) {
    const sourceStart = offset + row * stride
    pixels.set(buffer.subarray(sourceStart, sourceStart + rowSize), row * rowSize)
  }
  return { pixels, width, height }
}

function resizeRgbaFrame(frame: RgbaFrame, maxWidth?: number): RgbaFrame {
  if (!maxWidth || frame.width <= maxWidth) return frame

  const width = maxWidth
  const height = Math.max(1, Math.round((frame.height * width) / frame.width))
  const pixels = new Uint8Array(width * height * 4)
  for (let targetY = 0; targetY < height; targetY += 1) {
    const sourceY = Math.min(frame.height - 1, Math.floor((targetY * frame.height) / height))
    for (let targetX = 0; targetX < width; targetX += 1) {
      const sourceX = Math.min(frame.width - 1, Math.floor((targetX * frame.width) / width))
      const sourceOffset = (sourceY * frame.width + sourceX) * 4
      const targetOffset = (targetY * width + targetX) * 4
      pixels.set(frame.pixels.subarray(sourceOffset, sourceOffset + 4), targetOffset)
    }
  }
  return { pixels, width, height }
}

function encodeRgbaFrameToPng(frame: RgbaFrame): Blob {
  const rowSize = frame.width * 4
  const scanlines = new Uint8Array((rowSize + 1) * frame.height)
  for (let row = 0; row < frame.height; row += 1) {
    const targetOffset = row * (rowSize + 1)
    scanlines[targetOffset] = 0
    scanlines.set(frame.pixels.subarray(row * rowSize, (row + 1) * rowSize), targetOffset + 1)
  }

  const header = new Uint8Array(13)
  writeUint32(header, 0, frame.width)
  writeUint32(header, 4, frame.height)
  header[8] = 8
  header[9] = PNG_COLOR_TYPE_RGBA

  const png = concatBytes([
    PNG_SIGNATURE,
    createPngChunk('IHDR', header),
    createPngChunk('IDAT', deflate(scanlines, { level: PNG_COMPRESSION_LEVEL })),
    createPngChunk('IEND', new Uint8Array())
  ])
  const pngBuffer = new ArrayBuffer(png.byteLength)
  new Uint8Array(pngBuffer).set(png)
  return new Blob([pngBuffer], { type: 'image/png' })
}

async function decodeVideoFrame(
  clip: LoadedVideoClip,
  targetUs: number,
  maxWidth?: number
): Promise<Blob> {
  const candidates = buildVideoFrameDecodeCandidates(targetUs, clip.meta.duration)
  let lastError: unknown

  for (const candidateUs of candidates) {
    try {
      const result = await clip.tick(candidateUs)
      const frame = result.video
      if (!frame) continue

      try {
        const rgba = await copyVideoFrameToRgba(frame)
        return encodeRgbaFrameToPng(resizeRgbaFrame(rgba, maxWidth))
      } finally {
        frame.close()
      }
    } catch (error) {
      lastError = error
    }
  }

  if (lastError instanceof Error) throw lastError
  throw new Error('目标帧解码失败')
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('缩略图读取失败'))
    reader.readAsDataURL(blob)
  })
}

function toMicroseconds(seconds: number): number {
  return Math.max(0, Math.round(seconds * MICROSECONDS_PER_SECOND))
}

/**
 * 从视频 URL 独立解码指定时间点，不读取页面 video 元素像素，避免预览视频受 Canvas 跨域限制。
 */
export async function captureVideoUrlFrame(
  sourceUrl: string,
  targetSeconds: number,
  fileName: string
): Promise<File> {
  if (!sourceUrl) throw new Error('视频地址为空')

  const clip = await loadVideoClip(sourceUrl)
  try {
    const durationSeconds = clip.meta.duration / MICROSECONDS_PER_SECOND
    const target = clampVideoFrameTime(targetSeconds, durationSeconds)
    const targetUs = toMicroseconds(target)
    const frame = await decodeVideoFrame(clip, targetUs)

    const name = /\.png$/i.test(fileName) ? fileName : `${fileName}.png`
    return new File([frame], name, { type: 'image/png' })
  } finally {
    clip.destroy()
  }
}

async function loadTimelineThumbs(
  clip: LoadedVideoClip,
  maxWidth: number,
  count: number,
  lastFrameUs: number
): Promise<TimelineThumb[]> {
  // 优先用官方 thumbnails：按步长取样，单点空帧会被跳过而不是整条失败
  if (count === 1 || lastFrameUs <= 0) {
    const first = await decodeVideoFrame(clip, 0, maxWidth)
    return [{ ts: 0, img: first }]
  }

  const step = Math.max(1, Math.round(lastFrameUs / (count - 1)))
  try {
    const stepped = await clip.thumbnails(maxWidth, {
      start: 0,
      end: lastFrameUs,
      step
    })
    if (stepped.length > 0) return stepped
  } catch {
    // 硬解失败等：继续走关键帧兜底
  }

  try {
    const byKeyFrame = await clip.thumbnails(maxWidth, {
      start: 0,
      end: Math.max(lastFrameUs, clip.meta.duration)
    })
    if (byKeyFrame.length > 0) return byKeyFrame
  } catch {
    // 再走 tick 重试兜底
  }

  const frames: TimelineThumb[] = []
  for (let index = 0; index < count; index += 1) {
    const targetUs = Math.round((lastFrameUs * index) / (count - 1))
    try {
      const img = await decodeVideoFrame(clip, targetUs, maxWidth)
      frames.push({ ts: targetUs, img })
    } catch {
      // 单个时间点失败不影响其余缩略图
    }
  }
  return frames
}

/** 使用 WebAV 独立生成时间轴缩略图，不读取页面 video 元素，也不写入本地或数据库。 */
export async function captureVideoTimelineFrames(
  sourceUrl: string,
  options: TimelineFrameOptions = {}
): Promise<string[]> {
  const count = Math.max(1, Math.floor(options.count || 10))
  const maxWidth = Math.max(32, Math.floor(options.maxWidth || 160))
  if (!sourceUrl || options.shouldContinue?.() === false) return []

  const clip = await loadVideoClip(sourceUrl)
  try {
    if (options.shouldContinue?.() === false) return []

    const durationSeconds = clip.meta.duration / MICROSECONDS_PER_SECOND
    const lastFrameUs = toMicroseconds(clampVideoFrameTime(durationSeconds, durationSeconds))
    const thumbs = await loadTimelineThumbs(clip, maxWidth, count, lastFrameUs)
    if (options.shouldContinue?.() === false) return []

    const frames: string[] = []
    for (const thumb of pickEvenlySpacedItems(thumbs, count)) {
      if (options.shouldContinue?.() === false) return []
      frames.push(await blobToDataUrl(thumb.img))
    }
    return options.shouldContinue?.() === false ? [] : frames
  } finally {
    clip.destroy()
  }
}
