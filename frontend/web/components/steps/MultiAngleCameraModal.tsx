'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { Modal, Button, InputNumber, Slider, message } from 'antd'
import { UploadOutlined, CloseOutlined } from '@ant-design/icons'
import { EllipsisTooltip } from '~/components/common/EllipsisTooltip'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import { ModelSelectDropdown, type ModelOption } from './ModelSelectDropdown'
import { useMultiAngleThreeScene } from './multi-angle-camera/useMultiAngleThreeScene'
import {
  buildMultiAnglePromptParts,
  type MultiAngleGeneratePayload
} from '~/utils/multiAngleCameraPrompt'
import { DEFAULT_NINE_GRID_ANGLE_PROMPTS } from '~/utils/nineGridCameraAngles'
import { createTrackedObjectUrl, revokeObjectUrl } from '~/utils/objectUrl'
import './MultiAngleCameraModal.css'

interface Props {
  open: boolean
  imageUrl?: string
  /** 编辑分镜图：固定九宫格机位，禁用旋转与右侧调节 */
  fixedNineGrid?: boolean
  modelValue?: ModelOption
  modelOptions?: ModelOption[]
  modelExpanded?: boolean
  onOpenChange: (value: boolean) => void
  onModelExpandedChange?: (value: boolean) => void
  onSelectModel?: (model: ModelOption) => void
  onGenerate?: (payload: MultiAngleGeneratePayload) => void
  billingTarget?:
    | { kind: 'storyboard'; storyboardId: number; aspectRatio?: string }
    | { kind: 'form'; formId: number; aspectRatio?: string }
}

const DEFAULT_MODEL_VALUE: ModelOption = {
  id: '',
  name: '请选择模型',
  iconBg: '#10B981',
  prices: []
}

export function MultiAngleCameraModal({
  open,
  imageUrl = '',
  fixedNineGrid = false,
  modelValue = DEFAULT_MODEL_VALUE,
  modelOptions = [],
  modelExpanded = false,
  onOpenChange,
  onModelExpandedChange,
  onSelectModel,
  onGenerate,
  billingTarget
}: Props) {
  const showModelSelect = modelOptions.length > 0

  /** 原 ref：逻辑读写走 ref，再镜像到 state 触发渲染 */
  const horizontalRotationRef = useRef(0)
  const [horizontalRotation, setHorizontalRotationState] = useState(0)
  const setHorizontalRotation = (v: number) => {
    horizontalRotationRef.current = v
    setHorizontalRotationState(v)
  }
  const verticalAngleRef = useRef(0)
  const [verticalAngle, setVerticalAngleState] = useState(0)
  const setVerticalAngle = (v: number) => {
    verticalAngleRef.current = v
    setVerticalAngleState(v)
  }
  const focalLengthRef = useRef(0)
  const [focalLength, setFocalLengthState] = useState(0)
  const setFocalLength = (v: number) => {
    focalLengthRef.current = v
    setFocalLengthState(v)
  }
  const activeImageUrlRef = useRef(imageUrl || '')
  const [activeImageUrl, setActiveImageUrlState] = useState(imageUrl || '')
  const setActiveImageUrl = (v: string) => {
    activeImageUrlRef.current = v
    setActiveImageUrlState(v)
  }
  const canvasRootRef = useRef<HTMLDivElement | null>(null)
  const hasInteractedRef = useRef(false)
  const [, setHasInteractedState] = useState(false)
  const setHasInteracted = (v: boolean) => {
    hasInteractedRef.current = v
    setHasInteractedState(v)
  }
  const textureReadyRef = useRef(false)
  const [textureReady, setTextureReadyState] = useState(false)
  const setTextureReady = (v: boolean) => {
    textureReadyRef.current = v
    setTextureReadyState(v)
  }

  const fixedNineGridRef = useRef(fixedNineGrid)
  fixedNineGridRef.current = fixedNineGrid
  const propsRef = useRef({ open, imageUrl })
  propsRef.current = { open, imageUrl }

  const isFlatPreviewState =
    !fixedNineGrid &&
    !hasInteractedRef.current &&
    Math.abs(horizontalRotation) < 0.1 &&
    Math.abs(verticalAngle) < 0.1
  const showFlatOverlay =
    !!activeImageUrl &&
    // 初始机位用 HTML 图层完整展示图片，避免球体压扁后只显示一条
    (!textureReady || isFlatPreviewState)

  const quoteRequest = useMemo(() => {
    // 真实提交优先使用当前画布记录 URL，上传预览仅在记录 URL 为空时兜底。
    const source = String(imageUrl || activeImageUrl || '').trim()
    const modelCode = String(modelValue?.id || '').trim()
    if (!open || !billingTarget || !source || !modelCode) return null
    if (billingTarget.kind === 'storyboard') {
      const angles = fixedNineGrid
        ? [...DEFAULT_NINE_GRID_ANGLE_PROMPTS]
        : [buildMultiAnglePromptParts(horizontalRotation, verticalAngle, focalLength).concat]
      return {
        quoteType: fixedNineGrid ? 'STORYBOARD_MULTI_GRID_IMAGE' : 'STORYBOARD_MULTI_VIEW_IMAGE',
        payload: {
          storyboardId: billingTarget.storyboardId,
          imageUrl: source,
          angles,
          modelCode,
          aspectRatio: billingTarget.aspectRatio || '1:1'
        }
      }
    }
    return {
      quoteType: 'FORM_MULTI_VIEW_IMAGE',
      payload: {
        formId: billingTarget.formId,
        imageUrl: source,
        anglePrompt: buildMultiAnglePromptParts(horizontalRotation, verticalAngle, focalLength).concat,
        modelCode,
        ...(billingTarget.aspectRatio ? { aspectRatio: billingTarget.aspectRatio } : {})
      }
    }
  }, [
    activeImageUrl,
    billingTarget,
    fixedNineGrid,
    focalLength,
    horizontalRotation,
    imageUrl,
    modelValue,
    open,
    verticalAngle
  ])
  const generationQuote = useBillingQuote(quoteRequest)

  const {
    varsRef,
    applyTexture,
    updateCameraRig,
    resizeRenderer,
    initThreeScene,
    destroyThreeScene
  } = useMultiAngleThreeScene({
    canvasRootRef,
    fixedNineGridRef,
    horizontalRotationRef,
    verticalAngleRef,
    focalLengthRef,
    hasInteractedRef,
    activeImageUrlRef,
    setHasInteracted,
    setTextureReady
  })

  const handleUploadImage = () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.onchange = (event: Event) => {
      const target = event.target as HTMLInputElement
      const file = target.files?.[0]
      if (!file) return
      const vars = varsRef.current
      if (vars.objectUrlToRevoke) revokeObjectUrl(vars.objectUrlToRevoke)
      vars.objectUrlToRevoke = createTrackedObjectUrl(file)
      setActiveImageUrl(vars.objectUrlToRevoke)
    }
    input.click()
  }

  const handleGenerateAngles = () => {
    const source = activeImageUrlRef.current || propsRef.current.imageUrl || ''
    if (!source) {
      message.warning('请先选择或加载一张参考图后再开始生图')
      return
    }

    if (fixedNineGridRef.current) {
      const nineGridAngles = [...DEFAULT_NINE_GRID_ANGLE_PROMPTS]
      onGenerate?.({
        mode: 'nineGridFixed',
        horizontalRotation: 0,
        verticalAngle: 0,
        focalLength: 0,
        imageUrl: source,
        angles: nineGridAngles.map((angle, idx) => ({
          angle: `格${idx + 1}`,
          url: source
        })),
        multiAnglePromptConcat: nineGridAngles.join(' | '),
        multiAnglePromptParts: {
          yawTags: '',
          pitchTags: '',
          focalTags: '',
          concat: nineGridAngles.join(' | ')
        },
        nineGridAngles
      })
      onOpenChange(false)
      return
    }

    const base = horizontalRotationRef.current
    const angleLabels = ['主视角', '左视角', '右视角', '俯视角']
    const angles = angleLabels.map((angle, idx) => ({
      angle: `${angle}(${(base + idx * 45) % 360}°)`,
      url: source
    }))
    const multiAnglePromptParts = buildMultiAnglePromptParts(
      horizontalRotationRef.current,
      verticalAngleRef.current,
      focalLengthRef.current
    )
    onGenerate?.({
      mode: 'single',
      horizontalRotation: horizontalRotationRef.current,
      verticalAngle: verticalAngleRef.current,
      focalLength: focalLengthRef.current,
      imageUrl: source,
      angles,
      multiAnglePromptConcat: multiAnglePromptParts.concat,
      multiAnglePromptParts
    })
    onOpenChange(false)
  }

  // 原 watch(() => props.open)：打开时重置并初始化 3D 场景，关闭时销毁
  const prevOpenRef = useRef(false)
  useEffect(() => {
    if (prevOpenRef.current === open) return
    prevOpenRef.current = open
    if (open) {
      void (async () => {
        setHasInteracted(false)
        setHorizontalRotation(0)
        setVerticalAngle(0)
        setFocalLength(0)
        setActiveImageUrl(propsRef.current.imageUrl || activeImageUrlRef.current)
        setTextureReady(false)
        await initThreeScene()
        requestAnimationFrame(() => {
          resizeRenderer()
        })
        if (activeImageUrlRef.current) applyTexture(activeImageUrlRef.current)
        const vars = varsRef.current
        if (vars.orbitControls) {
          vars.orbitControls.enableRotate = !fixedNineGridRef.current
          vars.orbitControls.enableZoom = !fixedNineGridRef.current
        }
      })()
      return
    }
    destroyThreeScene()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 原 watch(fixedNineGrid)：机位模式切换时重建场景
  const prevFixedNineGridRef = useRef(fixedNineGrid)
  useEffect(() => {
    if (prevFixedNineGridRef.current === fixedNineGrid) return
    prevFixedNineGridRef.current = fixedNineGrid
    if (!propsRef.current.open) return
    void (async () => {
      destroyThreeScene()
      setHasInteracted(false)
      await initThreeScene()
      if (activeImageUrlRef.current) applyTexture(activeImageUrlRef.current)
      const vars = varsRef.current
      if (vars.orbitControls) {
        vars.orbitControls.enableRotate = !fixedNineGrid
        vars.orbitControls.enableZoom = !fixedNineGrid
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fixedNineGrid])

  // 原 watch(() => props.imageUrl)
  const prevImageUrlRef = useRef(imageUrl)
  useEffect(() => {
    if (prevImageUrlRef.current === imageUrl) return
    prevImageUrlRef.current = imageUrl
    if (!imageUrl) return
    setActiveImageUrl(imageUrl)
     
  }, [imageUrl])

  // 原 watch(activeImageUrl)
  const prevActiveImageUrlRef = useRef(activeImageUrl)
  useEffect(() => {
    if (prevActiveImageUrlRef.current === activeImageUrl) return
    prevActiveImageUrlRef.current = activeImageUrl
    applyTexture(activeImageUrl)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeImageUrl])

  // 原 watch([horizontalRotation, verticalAngle, focalLength])
  const prevSlidersRef = useRef<[number, number, number]>([0, 0, 0])
  useEffect(() => {
    const [ph, pv, pf] = prevSlidersRef.current
    if (ph === horizontalRotation && pv === verticalAngle && pf === focalLength) return
    prevSlidersRef.current = [horizontalRotation, verticalAngle, focalLength]
    if (fixedNineGridRef.current) return
    setHasInteracted(true)
    const vars = varsRef.current
    if (vars.orbitControls) vars.orbitControls.enabled = true
    updateCameraRig()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [horizontalRotation, verticalAngle, focalLength])

  // 原 onBeforeUnmount
  useEffect(() => {
    const vars = varsRef.current
    return () => {
      destroyThreeScene()
      if (vars.objectUrlToRevoke) revokeObjectUrl(vars.objectUrlToRevoke)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Modal
      open={open}
      width={1000}
      footer={null}
      title={null}
      closable={false}
      wrapClassName="create-flow-modal multi-angle-modal-wrap"
      className="multi-angle-modal"
      onCancel={() => onOpenChange(false)}
    >
      <div className="ma-shell">
        <header className="ma-header">
          <h2 className="ma-title">多机位</h2>
          <button type="button" className="ma-close" aria-label="关闭" onClick={() => onOpenChange(false)}>
            <CloseOutlined />
          </button>
        </header>

        <div className="multi-angle-layout">
          <section className="viewport-panel">
            <div className="three-canvas-stage">
              <div ref={canvasRootRef} className="three-canvas-root" />
              {showFlatOverlay && (
                <div className="image-overlay">
                  {activeImageUrl ? (
                    <img src={activeImageUrl} alt="" className="center-image-overlay" />
                  ) : null}
                </div>
              )}
            </div>
            {fixedNineGrid && (
              <p className="nine-grid-fixed-hint">
                <span className="nine-grid-fixed-hint__icon" aria-hidden="true">✓</span>
                固定九宫格机位
              </p>
            )}
          </section>

          <aside className={`control-panel${fixedNineGrid ? ' control-panel--disabled' : ''}`}>
            <h4 className="panel-title">调整摄像机机位</h4>
            <div className="slider-block">
              <div className="label-row">
                <span className="label">水平旋转</span>
                <InputNumber
                  value={horizontalRotation}
                  onChange={(v) => setHorizontalRotation(Number(v ?? 0))}
                  min={0}
                  max={315}
                  controls={false}
                  disabled={fixedNineGrid}
                  size="small"
                  className="value-input"
                />
              </div>
              <Slider
                value={horizontalRotation}
                onChange={(v: number) => setHorizontalRotation(v)}
                min={0}
                max={315}
                disabled={fixedNineGrid}
              />
            </div>

            <div className="slider-block">
              <div className="label-row">
                <span className="label">垂直角度</span>
                <InputNumber
                  value={verticalAngle}
                  onChange={(v) => setVerticalAngle(Number(v ?? 0))}
                  min={-30}
                  max={60}
                  controls={false}
                  disabled={fixedNineGrid}
                  size="small"
                  className="value-input"
                />
              </div>
              <Slider
                value={verticalAngle}
                onChange={(v: number) => setVerticalAngle(v)}
                min={-30}
                max={60}
                disabled={fixedNineGrid}
              />
            </div>

            <div className="slider-block">
              <div className="label">焦距</div>
              <Slider
                value={focalLength}
                onChange={(v: number) => setFocalLength(v)}
                min={0}
                max={10}
                className="focal-slider"
                disabled={fixedNineGrid}
              />
              <div className="focal-presets">
                <button
                  type="button"
                  className="focal-preset"
                  disabled={fixedNineGrid}
                  onClick={() => setFocalLength(0)}
                >
                  0(远景)
                </button>
                <button
                  type="button"
                  className="focal-preset"
                  disabled={fixedNineGrid}
                  onClick={() => setFocalLength(5)}
                >
                  5(中景)
                </button>
                <button
                  type="button"
                  className="focal-preset"
                  disabled={fixedNineGrid}
                  onClick={() => setFocalLength(10)}
                >
                  10(特写)
                </button>
              </div>
            </div>
          </aside>
        </div>

        <div className="footer-actions">
          <div className="footer-left">
            {!fixedNineGrid && (
              <Button className="import-btn-dashed" icon={<UploadOutlined />} onClick={handleUploadImage}>
                <EllipsisTooltip title="选择本地文件" />
              </Button>
            )}
            {showModelSelect && (
              <div className="footer-model-select">
                <ModelSelectDropdown
                  value={modelValue}
                  options={modelOptions}
                  expanded={modelExpanded}
                  onToggle={() => onModelExpandedChange?.(!modelExpanded)}
                  onClose={() => onModelExpandedChange?.(false)}
                  onSelect={(model) => onSelectModel?.(model)}
                />
              </div>
            )}
          </div>
          <div className="right-actions">
            <BillingQuoteHint
              quote={generationQuote.quote}
              loading={generationQuote.loading}
              error={generationQuote.error}
              active={Boolean(quoteRequest)}
            />
            <Button className="cancel-btn" onClick={() => onOpenChange(false)}>取消</Button>
            <Button type="primary" className="gen-btn" onClick={handleGenerateAngles}>
              开始生图
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  )
}

export default MultiAngleCameraModal
