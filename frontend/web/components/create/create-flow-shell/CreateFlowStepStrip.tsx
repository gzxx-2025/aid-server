'use client'

/**
 * 顶部横向流程条（原 CreateFlowShell.vue create-workflow 段拆分）。
 */

import { LoadingOutlined } from '@ant-design/icons'
import { Fragment } from 'react'
import ellipse10Nor from '~/assets/img/icon/Ellipse-10-nor.svg'
import ellipse10Sel from '~/assets/img/icon/Ellipse-10-sel.svg'
import ellipse11Nor from '~/assets/img/icon/Ellipse-11-nor.svg'
import ellipse11Sel from '~/assets/img/icon/Ellipse-11-sel.svg'
import ellipse12Nor from '~/assets/img/icon/Ellipse-12-nor.svg'
import ellipse12Sel from '~/assets/img/icon/Ellipse-12-sel.svg'
import ellipse13Nor from '~/assets/img/icon/Ellipse-13-nor.svg'
import ellipse13Sel from '~/assets/img/icon/Ellipse-13-sel.svg'
import ellipse14Nor from '~/assets/img/icon/Ellipse-14-nor.svg'
import ellipse14Sel from '~/assets/img/icon/Ellipse-14-sel.svg'
import ellipse15Nor from '~/assets/img/icon/Ellipse-15-nor.svg'
import ellipse15Sel from '~/assets/img/icon/Ellipse-15-sel.svg'
import ellipse7Nor from '~/assets/img/icon/Ellipse-7-nor.svg'
import ellipse7Sel from '~/assets/img/icon/Ellipse-7-sel.svg'
import processIconNorRaw from '~/assets/img/icon/process-nor.svg'
import processIconSelRaw from '~/assets/img/icon/process-sel.svg'
import type { CreationStep } from '~/types'
import { assetUrl } from '~/utils/assetUrl'
import type { CreationFlowStepMeta } from '~/utils/createFlowStepMeta'

const stepFlowIcons: Record<CreationStep, { nor: string; sel: string }> = {
  'global-setting': { nor: assetUrl(ellipse7Nor), sel: assetUrl(ellipse7Sel) },
  'story-script': { nor: assetUrl(ellipse10Nor), sel: assetUrl(ellipse10Sel) },
  'scene-character': { nor: assetUrl(ellipse15Nor), sel: assetUrl(ellipse15Sel) },
  'storyboard-script': { nor: assetUrl(ellipse11Nor), sel: assetUrl(ellipse11Sel) },
  'storyboard-video': { nor: assetUrl(ellipse12Nor), sel: assetUrl(ellipse12Sel) },
  dubbing: { nor: assetUrl(ellipse13Nor), sel: assetUrl(ellipse13Sel) },
  preview: { nor: assetUrl(ellipse14Nor), sel: assetUrl(ellipse14Sel) }
}

const processIconNor = assetUrl(processIconNorRaw)
const processIconSel = assetUrl(processIconSelRaw)

export interface CreateFlowStepStripProps {
  displaySteps: CreationFlowStepMeta[]
  flowStepIndex: number
  stepStatus: Array<'completed' | 'pending' | 'disabled' | 'active'>
  unlockedStepIndex: number
  stepRealIndex: (stepKey: CreationStep) => number
  isConnectorTrailDone: (index: number) => boolean
  isPillDisabled: (index: number) => boolean
  /** 从案例复制/预览进入时，高亮全部流程入口。 */
  highlightAll?: boolean
  /** 素材准备（scene-character）步 loading（提取中 / 第三步可视生成中） */
  sceneCharacterGenerating: boolean
  storyboardScriptGenerating: boolean
  storyboardVideoGenerating: boolean
  dubbingGenerating: boolean
  onPillClick: (index: number) => void
  onPillIntent: (step: CreationStep) => void
}

export function CreateFlowStepStrip({
  displaySteps,
  flowStepIndex,
  stepStatus,
  unlockedStepIndex,
  stepRealIndex,
  isConnectorTrailDone,
  isPillDisabled,
  highlightAll = false,
  sceneCharacterGenerating,
  storyboardScriptGenerating,
  storyboardVideoGenerating,
  dubbingGenerating,
  onPillClick,
  onPillIntent
}: CreateFlowStepStripProps) {
  function pillLoading(stepKey: CreationStep): boolean {
    if (stepKey === 'scene-character') return sceneCharacterGenerating
    if (stepKey === 'storyboard-script') return storyboardScriptGenerating
    if (stepKey === 'storyboard-video') return storyboardVideoGenerating
    if (stepKey === 'dubbing') return dubbingGenerating
    return false
  }

  return (
    <div className="create-workflow">
      <div className="create-workflow__scroll">
        {displaySteps.map((step, displayIndex) => {
          const realIndex = stepRealIndex(step.key)
          const isActive = flowStepIndex === realIndex
          const selected = isActive || stepStatus[realIndex] === 'completed'
          const pillClasses = [
            'flow-step-pill',
            'flow-step-pill--strip',
            isActive ? 'flow-step-pill--active' : '',
            stepStatus[realIndex] === 'completed' ? 'flow-step-pill--completed' : '',
            stepStatus[realIndex] === 'pending' ? 'flow-step-pill--pending' : '',
            stepStatus[realIndex] === 'disabled' ? 'flow-step-pill--disabled' : '',
            realIndex > unlockedStepIndex ? 'flow-step-pill--locked-ahead' : '',
            highlightAll ? 'flow-step-pill--entry-breathing' : ''
          ]
            .filter(Boolean)
            .join(' ')
          return (
            <Fragment key={step.key}>
              <div className="flow-step-pill-wrap">
                <button
                  type="button"
                  className={pillClasses}
                  disabled={isPillDisabled(realIndex)}
                  onPointerEnter={() => onPillIntent(step.key)}
                  onPointerDown={() => onPillIntent(step.key)}
                  onFocus={() => onPillIntent(step.key)}
                  onClick={() => onPillClick(realIndex)}
                >
                  <span className="flow-step-pill__icon" aria-hidden="true">
                    {pillLoading(step.key) ? (
                      <LoadingOutlined className="flow-step-pill__loading" spin />
                    ) : (
                      <img
                        src={stepFlowIcons[step.key][selected ? 'sel' : 'nor']}
                        alt=""
                        className={`flow-step-pill__img${selected ? ' flow-step-pill__img--active' : ''}`}
                      />
                    )}
                  </span>
                  <span className="flow-step-pill__title">{step.title}</span>
                </button>
              </div>
              {displayIndex < displaySteps.length - 1 ? (
                <div
                  className={[
                    'flow-step-connector',
                    'flow-step-connector--h',
                    isConnectorTrailDone(realIndex) ? 'flow-step-connector--done' : '',
                    isActive ? 'flow-step-connector--next' : ''
                  ]
                    .filter(Boolean)
                    .join(' ')}
                >
                  <img
                    src={isConnectorTrailDone(realIndex) ? processIconSel : processIconNor}
                    alt=""
                    className={`flow-step-connector__img${isActive ? ' flow-step-connector__img--next' : ''}`}
                  />
                  {isActive ? (
                    <span className="flow-step-connector__march" aria-hidden="true">
                      <i className="march-chevron c1"></i>
                      <i className="march-chevron c2"></i>
                      <i className="march-chevron c3"></i>
                    </span>
                  ) : null}
                </div>
              ) : null}
            </Fragment>
          )
        })}
      </div>
    </div>
  )
}
