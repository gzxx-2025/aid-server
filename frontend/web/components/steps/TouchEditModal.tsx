'use client'

import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, MouseEvent as ReactMouseEvent } from 'react'
import { createPortal } from 'react-dom'
import { Modal, message } from 'antd'
import { userTouchEditPointDetect } from '~/utils/businessApi'
import { assetUrl } from '~/utils/assetUrl'
import { createClientId } from '~/utils/clientId'
import closeIconRaw from '~/assets/img/icon/close.svg'
import starWhiteIconRaw from '~/assets/img/icon/star_white.svg'
import './TouchEditModal.css'

const closeIconUrl = assetUrl(closeIconRaw)
const starWhiteIconUrl = assetUrl(starWhiteIconRaw)

interface TouchMark {
  id: string
  order: number
  x: number
  y: number
  zoomed: boolean
  loading: boolean
  name: string
}

interface Props {
  open: boolean
  imageUrl: string
  onOpenChange: (value: boolean) => void
}

const thumbZoom = 3.6
const previewZoom = 4.2

function clamp(n: number, min: number, max: number) {
  return Math.min(max, Math.max(min, n))
}

function toFixedCoord(v: number) {
  return Number(v.toFixed(5))
}

function clampScale(v: number) {
  return Math.min(4, Math.max(0.6, v))
}

export function TouchEditModal({ open, imageUrl, onOpenChange }: Props) {
  const viewportRef = useRef<HTMLDivElement | null>(null)
  const imageRef = useRef<HTMLImageElement | null>(null)
  const [instructionText, setInstructionText] = useState('')

  /** 原 ref 数组：逻辑读写走 ref，再镜像到 state 触发渲染 */
  const marksRef = useRef<TouchMark[]>([])
  const [marks, setMarksState] = useState<TouchMark[]>([])
  const syncMarks = () => setMarksState([...marksRef.current])

  const hoverTagIdRef = useRef<string | null>(null)
  const [hoverTagId, setHoverTagIdState] = useState<string | null>(null)
  const setHoverTagId = (v: string | null) => {
    hoverTagIdRef.current = v
    setHoverTagIdState(v)
  }
  const [hoverPreviewZoomed, setHoverPreviewZoomed] = useState(false)
  const [hoverPreviewPos, setHoverPreviewPos] = useState({ left: 28, top: 120 })
  const viewportHoveringRef = useRef(false)
  const [viewportHovering, setViewportHoveringState] = useState(false)
  const setViewportHovering = (v: boolean) => {
    viewportHoveringRef.current = v
    setViewportHoveringState(v)
  }
  const [hoverPos, setHoverPos] = useState<{ x: number; y: number } | null>(null)

  const scaleRef = useRef(1)
  const [scale, setScaleState] = useState(1)
  const setScale = (v: number) => {
    scaleRef.current = v
    setScaleState(v)
  }
  const offsetXRef = useRef(0)
  const [offsetX, setOffsetXState] = useState(0)
  const setOffsetX = (v: number) => {
    offsetXRef.current = v
    setOffsetXState(v)
  }
  const offsetYRef = useRef(0)
  const [offsetY, setOffsetYState] = useState(0)
  const setOffsetY = (v: number) => {
    offsetYRef.current = v
    setOffsetYState(v)
  }
  const draggingRef = useRef(false)
  const [dragging, setDraggingState] = useState(false)
  const setDragging = (v: boolean) => {
    draggingRef.current = v
    setDraggingState(v)
  }
  const dragStartXRef = useRef(0)
  const dragStartYRef = useRef(0)
  const naturalSizeRef = useRef({ w: 0, h: 0 })
  const [naturalSize, setNaturalSizeState] = useState({ w: 0, h: 0 })
  const setNaturalSize = (v: { w: number; h: number }) => {
    naturalSizeRef.current = v
    setNaturalSizeState(v)
  }

  const imageStyle: CSSProperties = {
    transform: `translate(${offsetX}px, ${offsetY}px) scale(${scale})`
  }

  function handleMainImageLoad() {
    const imgEl = imageRef.current
    if (!imgEl) return
    setNaturalSize({
      w: imgEl.naturalWidth || 0,
      h: imgEl.naturalHeight || 0
    })
  }

  function previewBgStyle(
    xRaw: number,
    yRaw: number,
    zoomRaw: number,
    sizePx: number,
    focused: boolean
  ): CSSProperties {
    const x = clamp(xRaw, 0, 1)
    const y = clamp(yRaw, 0, 1)
    const zoom = focused ? Math.max(1.01, zoomRaw) : 1
    const imgEl = imageRef.current
    const w = naturalSize.w || imgEl?.naturalWidth || 0
    const h = naturalSize.h || imgEl?.naturalHeight || 0
    if (!imageUrl || !w || !h) {
      return {
        backgroundImage: `url(${imageUrl})`,
        backgroundRepeat: 'no-repeat',
        backgroundSize: 'cover',
        backgroundPosition: '50% 50%'
      }
    }

    const baseScale = Math.max(sizePx / w, sizePx / h)
    const renderW = w * baseScale * zoom
    const renderH = h * baseScale * zoom

    // 让点击点尽量处于预览中心，超边界时做钳制
    let left = sizePx / 2 - x * renderW
    let top = sizePx / 2 - y * renderH
    left = clamp(left, sizePx - renderW, 0)
    top = clamp(top, sizePx - renderH, 0)

    return {
      backgroundImage: `url(${imageUrl})`,
      backgroundRepeat: 'no-repeat',
      backgroundSize: `${renderW}px ${renderH}px`,
      backgroundPosition: `${left}px ${top}px`
    }
  }

  // 原 watch(() => props.open)
  useEffect(() => {
    if (!open) return
    marksRef.current = []
    syncMarks()
    setInstructionText('')
    setHoverTagId(null)
    setViewportHovering(false)
    setHoverPos(null)
    setScale(1)
    setOffsetX(0)
    setOffsetY(0)

    // 双保险：有些缓存图不会稳定触发 onload，这里在弹窗打开时主动同步一次原图尺寸
    const raf = requestAnimationFrame(() => {
      const imgEl = imageRef.current
      if (imgEl?.complete && imgEl.naturalWidth && imgEl.naturalHeight) {
        setNaturalSize({
          w: imgEl.naturalWidth,
          h: imgEl.naturalHeight
        })
      }
    })
    return () => cancelAnimationFrame(raf)
     
  }, [open])

  // 原 watch(() => props.imageUrl)
  useEffect(() => {
    setNaturalSize({ w: 0, h: 0 })
     
  }, [imageUrl])

  // 提示文案始终跟随手势展示（点击后也不消失）；拖拽时隐藏避免干扰
  const showHoverTooltip = viewportHovering && !dragging

  // 原 @wheel.prevent：React 合成 wheel 为 passive 无法 preventDefault，改为原生非 passive 监听
  useEffect(() => {
    if (!open) return
    const el = viewportRef.current
    if (!el) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const next = scaleRef.current + (event.deltaY < 0 ? 0.12 : -0.12)
      setScale(clampScale(next))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
     
  }, [open])

  function handleMouseDown(event: ReactMouseEvent) {
    if (scaleRef.current <= 1) return
    setDragging(true)
    dragStartXRef.current = event.clientX - offsetXRef.current
    dragStartYRef.current = event.clientY - offsetYRef.current
  }

  function handleMouseMove(event: ReactMouseEvent) {
    // hover tip：始终跟随鼠标（只要在图片区域内）
    const imgEl = imageRef.current
    if (imgEl && viewportHoveringRef.current) {
      const rect = imgEl.getBoundingClientRect()
      const m = getImageContentMetrics()
      if (!m) return
      const localX = event.clientX - rect.left
      const localY = event.clientY - rect.top
      const x = (localX - m.offsetX) / m.contentW
      const y = (localY - m.offsetY) / m.contentH
      if (Number.isFinite(x) && Number.isFinite(y) && x >= 0 && y >= 0 && x <= 1 && y <= 1) {
        setHoverPos({ x, y })
      } else {
        setHoverPos(null)
      }
    }
    if (!draggingRef.current) return
    setOffsetX(event.clientX - dragStartXRef.current)
    setOffsetY(event.clientY - dragStartYRef.current)
  }

  function handleMouseUp() {
    setDragging(false)
  }

  function handleViewportEnter() {
    setViewportHovering(true)
  }

  function handleViewportLeave() {
    setViewportHovering(false)
    setHoverPos(null)
  }

  function handleTagMouseEnter(tagId: string, event: ReactMouseEvent) {
    setHoverTagId(tagId)
    setHoverPreviewZoomed(false)
    const target = event.currentTarget as HTMLElement | null
    if (target && typeof window !== 'undefined') {
      const rect = target.getBoundingClientRect()
      const previewW = 180
      const previewH = 180
      const gap = 10
      let left = rect.left + rect.width / 2 - previewW / 2
      let top = rect.top - previewH - gap
      if (top < 8) top = rect.bottom + gap
      left = Math.max(8, Math.min(left, window.innerWidth - previewW - 8))
      top = Math.max(8, Math.min(top, window.innerHeight - previewH - 8))
      setHoverPreviewPos({ left, top })
    }
    requestAnimationFrame(() => {
      if (hoverTagIdRef.current === tagId) {
        setHoverPreviewZoomed(true)
      }
    })
  }

  function handleTagMouseLeave() {
    setHoverTagId(null)
    setHoverPreviewZoomed(false)
  }

  function getImageContentMetrics() {
    const imgEl = imageRef.current
    if (!imgEl) return null
    // 注意：图片在 viewport 内会做 translate/scale 变换。
    // 坐标换算必须与事件里使用的 getBoundingClientRect() 保持同一坐标系，
    // 否则在缩放后会出现明显偏移。
    const rect = imgEl.getBoundingClientRect()
    const boxW = rect.width
    const boxH = rect.height
    const naturalW = imgEl.naturalWidth
    const naturalH = imgEl.naturalHeight
    if (!boxW || !boxH || !naturalW || !naturalH) return null

    const boxRatio = boxW / boxH
    const imgRatio = naturalW / naturalH

    let contentW = boxW
    let contentH = boxH
    let offsetX = 0
    let offsetY = 0

    if (imgRatio > boxRatio) {
      contentW = boxW
      contentH = boxW / imgRatio
      offsetY = (boxH - contentH) / 2
    } else {
      contentH = boxH
      contentW = boxH * imgRatio
      offsetX = (boxW - contentW) / 2
    }

    return { boxW, boxH, contentW, contentH, offsetX, offsetY }
  }

  function overlayStyle(nx: number, ny: number): CSSProperties {
    const m = getImageContentMetrics()
    if (!m) return { left: `${nx * 100}%`, top: `${ny * 100}%` }
    const leftPx = m.offsetX + nx * m.contentW
    const topPx = m.offsetY + ny * m.contentH
    return {
      left: `${(leftPx / m.boxW) * 100}%`,
      top: `${(topPx / m.boxH) * 100}%`
    }
  }

  async function handleImageClick(event: ReactMouseEvent) {
    if (draggingRef.current) return
    if (!imageUrl) return
    const imgEl = imageRef.current
    if (!imgEl) return
    // 坐标按 object-fit: contain 后的真实可视区域换算，避免黑边偏移
    const rect = imgEl.getBoundingClientRect()
    const m = getImageContentMetrics()
    if (!m) return
    const localX = event.clientX - rect.left
    const localY = event.clientY - rect.top
    const x = (localX - m.offsetX) / m.contentW
    const y = (localY - m.offsetY) / m.contentH
    if (!Number.isFinite(x) || !Number.isFinite(y)) return
    if (x < 0 || y < 0 || x > 1 || y > 1) return

    const nx = toFixedCoord(x)
    const ny = toFixedCoord(y)
    const id = createClientId('touch')
    const nextOrder = marksRef.current.length + 1
    marksRef.current.push({
      id,
      order: nextOrder,
      x: nx,
      y: ny,
      zoomed: false,
      loading: true,
      name: ''
    })
    syncMarks()
    // 让缩略图先以“整图”出现，再过渡到“局部区域”
    requestAnimationFrame(() => {
      const idx = marksRef.current.findIndex((m) => m.id === id)
      if (idx >= 0) {
        marksRef.current[idx] = { ...marksRef.current[idx]!, zoomed: true }
        syncMarks()
      }
    })

    try {
      const prompt = `(${nx},${ny})`
      const res = await userTouchEditPointDetect({ image: imageUrl, prompt })
      const label = String(
        res?.name ||
          res?.label ||
          res?.objectName ||
          res?.data?.name ||
          res?.data?.label ||
          `区域${nextOrder}`
      )
      const idx = marksRef.current.findIndex((m) => m.id === id)
      if (idx >= 0) {
        marksRef.current[idx] = { ...marksRef.current[idx]!, loading: false, name: label }
        syncMarks()
      }
    } catch (e: unknown) {
      const idx = marksRef.current.findIndex((m) => m.id === id)
      if (idx >= 0) {
        marksRef.current[idx] = { ...marksRef.current[idx]!, loading: false, name: `区域${nextOrder}` }
        syncMarks()
      }
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '识别失败，已使用默认名称')
    }
  }

  return (
    <Modal
      open={open}
      width="100vw"
      style={{ top: 0, paddingBottom: 0, maxWidth: '100vw' }}
      footer={null}
      closable={false}
      mask={{ closable: false }}
      wrapClassName="create-flow-modal touch-edit-modal-wrap"
      className="touch-edit-modal"
      onCancel={() => onOpenChange(false)}
    >
      <div className="touch-edit">
        <div className="touch-edit__stage">
          <div className="touch-edit__canvas-wrap">
            <div
              ref={viewportRef}
              className={`touch-edit__viewport ${scale > 1 ? (dragging ? 'is-grabbing' : 'is-grab') : ''}`}
              onMouseEnter={handleViewportEnter}
              onMouseLeave={handleViewportLeave}
              onMouseDown={handleMouseDown}
              onMouseMove={handleMouseMove}
              onMouseUp={handleMouseUp}
              onClick={(e) => void handleImageClick(e)}
            >
              <img
                ref={imageRef}
                className="touch-edit__image"
                src={imageUrl}
                alt=""
                style={imageStyle}
                onLoad={handleMainImageLoad}
                draggable={false}
              />

              {marks.map((mark, index) => (
                <div key={mark.id} className="touch-edit__mark" style={overlayStyle(mark.x, mark.y)}>
                  <span>{index + 1}</span>
                </div>
              ))}

              {showHoverTooltip && hoverPos ? (
                <div className="touch-edit__tip" style={overlayStyle(hoverPos.x, hoverPos.y)}>
                  点击区域进行编辑
                </div>
              ) : null}
            </div>
            <img
              src={closeIconUrl}
              alt=""
              className="close-icon"
              onClick={() => onOpenChange(false)}
            />
          </div>

          <aside className="touch-edit__side">
            <div className="touch-edit__tags">
              {marks.map((tag) => (
                <div
                  key={tag.id}
                  className="touch-edit__tag"
                  onMouseEnter={(e) => handleTagMouseEnter(tag.id, e)}
                  onMouseLeave={handleTagMouseLeave}
                >
                  <div className="touch-edit__tag-thumb-wrap">
                    <div className="touch-edit__tag-thumb">
                      <div
                        className="touch-edit__tag-thumb-img"
                        style={previewBgStyle(tag.x, tag.y, thumbZoom, 24, tag.zoomed)}
                      />
                    </div>
                  </div>
                  <div className="touch-edit__tag-order">{tag.order}</div>
                  {tag.loading ? (
                    <div className="touch-edit__tag-loading" />
                  ) : (
                    <div className="touch-edit__tag-name">{tag.name}</div>
                  )}

                  {hoverTagId === tag.id
                    ? createPortal(
                        <div
                          className="touch-edit__hover-preview"
                          style={{
                            left: `${hoverPreviewPos.left}px`,
                            top: `${hoverPreviewPos.top}px`
                          }}
                        >
                          <div
                            className="touch-edit__hover-preview-img"
                            style={previewBgStyle(tag.x, tag.y, previewZoom, 180, hoverPreviewZoomed)}
                          />
                        </div>,
                        document.body
                      )
                    : null}
                </div>
              ))}
            </div>
            <div className="touch-edit__input-wrap">
              <textarea
                value={instructionText}
                onChange={(e) => setInstructionText(e.target.value)}
                className="touch-edit__input"
                placeholder="拖动框并修改您的指令内容，如：将画面中男子的头发改成白色"
              />
            </div>
            <button type="button" className="touch-edit__submit-btn">
              <img src={starWhiteIconUrl} alt="" />
              <span>开始生成</span>
            </button>
          </aside>
        </div>
      </div>
    </Modal>
  )
}

export default TouchEditModal
