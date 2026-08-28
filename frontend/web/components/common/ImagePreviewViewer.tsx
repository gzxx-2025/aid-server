'use client'

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type CSSProperties
} from 'react'
import {
  ZoomInOutlined,
  ZoomOutOutlined,
  RotateRightOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import './ImagePreviewViewer.css'

const MIN_SCALE = 0.25
const MAX_SCALE = 4
const SCALE_STEP = 0.25

export interface ImagePreviewViewerProps {
  url: string
  alt?: string
  maxHeight?: string
  /** 在弹窗壳内铺满可用高度（与批量生成分镜图弹窗内容区一致） */
  fillStage?: boolean
  /** fillStage 时图片默认占舞台的比例（0-1）；预览弹窗传 1 以撑满可视区 */
  stageFitRatio?: number
  /** 有值时标题与工具条同一行（左标题 / 中工具 / 右留白给关闭） */
  headerTitle?: string
}

export interface ImagePreviewViewerHandle {
  reset: () => void
  zoomIn: () => void
  zoomOut: () => void
  rotate: () => void
}

function getRotatedBounds(width: number, height: number, deg: number) {
  const rad = (deg * Math.PI) / 180
  const cos = Math.abs(Math.cos(rad))
  const sin = Math.abs(Math.sin(rad))
  return {
    width: width * cos + height * sin,
    height: width * sin + height * cos
  }
}

/** 全屏图片预览：放大、缩小、旋转、拖拽、滚轮缩放 */
export const ImagePreviewViewer = forwardRef<ImagePreviewViewerHandle, ImagePreviewViewerProps>(
  function ImagePreviewViewer(
    {
      url,
      alt = '预览',
      maxHeight = '66vh',
      fillStage = false,
      stageFitRatio = 1,
      headerTitle = ''
    },
    ref
  ) {
    const stageRef = useRef<HTMLDivElement | null>(null)
    const imgRef = useRef<HTMLImageElement | null>(null)
    const [scale, setScale] = useState(1)
    const [rotation, setRotation] = useState(0)
    const [pan, setPan] = useState({ x: 0, y: 0 })
    const [dragging, setDragging] = useState(false)
    const [canPan, setCanPan] = useState(false)
    const [imgBoundsStyle, setImgBoundsStyle] = useState<CSSProperties>({})

    const baseSizeRef = useRef({ width: 0, height: 0 })
    const dragStartRef = useRef<{ x: number; y: number; panX: number; panY: number } | null>(null)
    // RO/原生 wheel 回调读取最新 props/state 用
    const latestRef = useRef({ fillStage, stageFitRatio, scale, rotation })
    latestRef.current = { fillStage, stageFitRatio, scale, rotation }

    const resolvedUrl = String(url || '').trim()

    const effectiveStageFitRatio = useCallback(() => {
      const { fillStage: fill, stageFitRatio: ratio } = latestRef.current
      if (!fill) return 1
      if (!Number.isFinite(ratio)) return 1
      return Math.min(1, Math.max(0.35, ratio))
    }, [])

    const computeCanPan = useCallback((scaleVal: number, rotationVal: number) => {
      const stage = stageRef.current
      const size = baseSizeRef.current
      if (!stage || !size.width || !size.height) return false
      const scaled = { width: size.width * scaleVal, height: size.height * scaleVal }
      const bounds = getRotatedBounds(scaled.width, scaled.height, rotationVal)
      return bounds.width > stage.clientWidth + 1 || bounds.height > stage.clientHeight + 1
    }, [])

    const updateImgBoundsStyle = useCallback(() => {
      const stage = stageRef.current
      if (!stage || !latestRef.current.fillStage) {
        setImgBoundsStyle({})
        return
      }
      const ratio = effectiveStageFitRatio()
      const stageW = stage.clientWidth
      const stageH = stage.clientHeight
      // 舞台尚未布局完成时不要写成 1px，避免图片被压没
      if (stageW < 2 || stageH < 2) {
        setImgBoundsStyle({
          width: 'auto',
          height: 'auto',
          maxWidth: '100%',
          maxHeight: '100%',
          objectFit: 'contain'
        })
        return
      }
      const maxW = Math.max(1, Math.floor(stageW * ratio))
      const maxH = Math.max(1, Math.floor(stageH * ratio))
      // 所有比例统一 contain：完整展示（含 16:9 上下留白），等比撑满且不裁切
      setImgBoundsStyle({
        width: 'auto',
        height: 'auto',
        maxWidth: `${maxW}px`,
        maxHeight: `${maxH}px`,
        objectFit: 'contain'
      })
    }, [effectiveStageFitRatio])

    const measureBaseSize = useCallback(() => {
      const img = imgRef.current
      if (!img) return
      baseSizeRef.current = { width: img.clientWidth, height: img.clientHeight }
      const { scale: s, rotation: r } = latestRef.current
      setCanPan(computeCanPan(s, r))
    }, [computeCanPan])

    const onImgLoad = useCallback(() => {
      requestAnimationFrame(() => {
        updateImgBoundsStyle()
        measureBaseSize()
      })
    }, [updateImgBoundsStyle, measureBaseSize])

    const zoomIn = useCallback(() => {
      setScale((s) => Math.min(MAX_SCALE, Number((s + SCALE_STEP).toFixed(2))))
    }, [])

    const zoomOut = useCallback(() => {
      setScale((s) => Math.max(MIN_SCALE, Number((s - SCALE_STEP).toFixed(2))))
    }, [])

    const applyZoomDelta = useCallback((delta: number) => {
      if (!delta) return
      setScale((s) => Math.min(MAX_SCALE, Math.max(MIN_SCALE, Number((s + delta).toFixed(2)))))
    }, [])

    const rotate = useCallback(() => {
      setRotation((r) => (r + 90) % 360)
    }, [])

    const reset = useCallback(() => {
      setScale(1)
      setRotation(0)
      setPan({ x: 0, y: 0 })
      requestAnimationFrame(() => {
        updateImgBoundsStyle()
        measureBaseSize()
      })
    }, [updateImgBoundsStyle, measureBaseSize])

    useImperativeHandle(ref, () => ({ reset, zoomIn, zoomOut, rotate }), [
      reset,
      zoomIn,
      zoomOut,
      rotate
    ])

    // 缩放/旋转变化：重算可拖拽态，收不下时清零位移（对齐原 watch([scale, rotation])）
    useEffect(() => {
      const next = computeCanPan(scale, rotation)
      setCanPan(next)
      if (!next) setPan({ x: 0, y: 0 })
    }, [scale, rotation, computeCanPan])

    // 图片地址变化：全量复位（对齐原 watch(resolvedUrl)）
    useEffect(() => {
      setScale(1)
      setRotation(0)
      setPan({ x: 0, y: 0 })
      baseSizeRef.current = { width: 0, height: 0 }
      setImgBoundsStyle({})
      setCanPan(false)
    }, [resolvedUrl])

    // 舞台尺寸变化：重排图片边界并重新量取基准尺寸
    useEffect(() => {
      const stage = stageRef.current
      if (typeof ResizeObserver === 'undefined' || !stage) return
      const observer = new ResizeObserver(() => {
        updateImgBoundsStyle()
        requestAnimationFrame(() => measureBaseSize())
      })
      observer.observe(stage)
      return () => observer.disconnect()
    }, [updateImgBoundsStyle, measureBaseSize])

    // 滚轮缩放需 preventDefault，React 合成 wheel 为 passive，必须原生监听
    useEffect(() => {
      const stage = stageRef.current
      if (!stage) return
      const onWheel = (event: WheelEvent) => {
        event.preventDefault()
        event.stopPropagation()
        if (!event.deltaY) return
        if (event.ctrlKey) {
          // 触摸板双指捏合缩放
          applyZoomDelta(-event.deltaY * 0.01)
          return
        }
        // 鼠标滚轮：按方向步进缩放
        applyZoomDelta(event.deltaY < 0 ? SCALE_STEP : -SCALE_STEP)
      }
      stage.addEventListener('wheel', onWheel, { passive: false })
      return () => stage.removeEventListener('wheel', onWheel)
    }, [applyZoomDelta])

    function onPointerDown(event: React.PointerEvent) {
      if (!canPan || event.button !== 0) return
      setDragging(true)
      dragStartRef.current = {
        x: event.clientX,
        y: event.clientY,
        panX: pan.x,
        panY: pan.y
      }
      stageRef.current?.setPointerCapture(event.pointerId)
      event.preventDefault()
    }

    function onPointerMove(event: React.PointerEvent) {
      const start = dragStartRef.current
      if (!dragging || !start) return
      setPan({
        x: start.panX + (event.clientX - start.x),
        y: start.panY + (event.clientY - start.y)
      })
      event.preventDefault()
    }

    function onPointerUp(event: React.PointerEvent) {
      if (!dragging) return
      setDragging(false)
      dragStartRef.current = null
      try {
        stageRef.current?.releasePointerCapture(event.pointerId)
      } catch {}
    }

    const canvasStyle: CSSProperties = {
      transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale}) rotate(${rotation}deg)`
    }

    // 原 v-bind(maxHeight)：fillStage 时由 --fill 规则置 none，此处交给 imgBoundsStyle
    const imgStyle: CSSProperties = fillStage
      ? { ...imgBoundsStyle }
      : { maxHeight, ...imgBoundsStyle }

    const rootClass = [
      'image-preview-viewer',
      fillStage ? 'image-preview-viewer--fill' : '',
      headerTitle ? 'image-preview-viewer--header-row' : ''
    ]
      .filter(Boolean)
      .join(' ')

    return (
      <div className={rootClass}>
        <div
          className={`image-preview-viewer__chrome${headerTitle ? ' image-preview-viewer__chrome--header' : ''}`}
        >
          {headerTitle ? (
            <div className="image-preview-viewer__title" title={headerTitle}>
              {headerTitle}
            </div>
          ) : null}
          <div className="image-preview-viewer__toolbar" role="toolbar" aria-label="图片预览工具">
            <button
              type="button"
              className="image-preview-viewer__tool-btn"
              aria-label="放大"
              title="放大"
              onClick={zoomIn}
            >
              <ZoomInOutlined />
            </button>
            <button
              type="button"
              className="image-preview-viewer__tool-btn"
              aria-label="缩小"
              title="缩小"
              onClick={zoomOut}
            >
              <ZoomOutOutlined />
            </button>
            <button
              type="button"
              className="image-preview-viewer__tool-btn"
              aria-label="旋转"
              title="旋转"
              onClick={rotate}
            >
              <RotateRightOutlined />
            </button>
            <button
              type="button"
              className="image-preview-viewer__tool-btn image-preview-viewer__tool-btn--reset"
              aria-label="还原"
              title="还原"
              onClick={reset}
            >
              <ReloadOutlined />
            </button>
          </div>
          {headerTitle ? (
            <div className="image-preview-viewer__header-trail" aria-hidden="true" />
          ) : null}
        </div>

        <div
          ref={stageRef}
          className={`image-preview-viewer__stage${canPan ? ' is-pannable' : ''}${dragging ? ' is-dragging' : ''}`}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          onLostPointerCapture={onPointerUp}
        >
          <div className="image-preview-viewer__canvas" style={canvasStyle}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              ref={imgRef}
              className="image-preview-viewer__img"
              src={resolvedUrl}
              alt={alt}
              style={imgStyle}
              draggable={false}
              onLoad={onImgLoad}
            />
          </div>
        </div>
      </div>
    )
  }
)

export default ImagePreviewViewer
