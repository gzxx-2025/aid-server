'use client'

import {
CheckCircleFilled,
DownOutlined,
MoreOutlined,
RightOutlined,
UpOutlined
} from '@ant-design/icons'
import { useEffect,useRef,useState } from 'react'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import type { AgentOption } from '~/types/modelAgentOptions'
import './AgentDropdown.css'
/** Compatibility: the legacy AgentPickerModal type now lives in types/modelAgentOptions. */
export type { AgentOption }

interface Props {
  value: AgentOption
  options: AgentOption[]
  expanded: boolean
  onToggle?: () => void
  onSelect?: (agent: AgentOption) => void
  onMore?: () => void
}

/** 原 <Transition name="dropdown"> 时长（transition: all 0.2s ease） */
const DROPDOWN_DURATION_MS = 200

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

export function AgentDropdown({ value, options, expanded, onToggle, onSelect, onMore }: Props) {
  // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
  const [rendered, setRendered] = useState(false)
  const [transitionClass, setTransitionClass] = useState('')
  const renderedRef = useRef(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }

    if (expanded) {
      renderedRef.current = true
      setRendered(true)
      setTransitionClass('dropdown-enter-from dropdown-enter-active')
      nextFrame(() => {
        setTransitionClass('dropdown-enter-active')
        timerRef.current = setTimeout(() => {
          timerRef.current = null
          setTransitionClass('')
        }, DROPDOWN_DURATION_MS)
      })
      return
    }

    if (!renderedRef.current) return
    setTransitionClass('dropdown-leave-active dropdown-leave-to')
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      renderedRef.current = false
      setRendered(false)
      setTransitionClass('')
    }, DROPDOWN_DURATION_MS)
  }, [expanded])

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])

  return (
    <div className="agent-dropdown">
      {/* 当前选中的智能体（可点击展开） */}
      <div
        className={expanded ? 'selected-agent expanded' : 'selected-agent'}
        onClick={() => onToggle?.()}
      >
        <div className="agent-preview">
          {value.thumbnail ? (
            <ShimmerImage
              src={value.thumbnail}
              alt={value.name}
              imgClass="agent-thumbnail__img"
              wrapperClass="agent-thumbnail"
              objectFit="cover"
              revealDirection="fade"
              minShimmerMs={280}
            />
          ) : (
            <div className="agent-thumbnail placeholder">{value.name?.slice(0, 1) || '?'}</div>
          )}
          <div className="agent-info">
            <div className="agent-name">{value.name || '请选择智能体'}</div>
            {expanded && value.desc ? <div className="agent-desc">{value.desc}</div> : null}
          </div>
        </div>
        {expanded ? <UpOutlined className="expand-icon" /> : <DownOutlined className="expand-icon" />}
      </div>

      {/* 下拉选项列表 */}
      {rendered ? (
        <div className={transitionClass ? `options-list ${transitionClass}` : 'options-list'}>
          {options.map((option) => (
            <div
              key={option.id}
              className={value.id === option.id ? 'option-item selected' : 'option-item'}
              onClick={() => onSelect?.(option)}
            >
              {option.thumbnail ? (
                <ShimmerImage
                  src={option.thumbnail}
                  alt={option.name}
                  imgClass="option-thumbnail__img"
                  wrapperClass="option-thumbnail"
                  objectFit="cover"
                  revealDirection="fade"
                  minShimmerMs={280}
                />
              ) : (
                <div className="option-thumbnail placeholder">
                  {option.name?.slice(0, 1) || '?'}
                </div>
              )}
              <div className="option-info">
                <div className="option-name">{option.name}</div>
                {option.desc ? <div className="option-desc">{option.desc}</div> : null}
              </div>
              {value.id === option.id ? <CheckCircleFilled className="check-icon" /> : null}
            </div>
          ))}

          {/* 选择更多模式 */}
          <div className="option-item more-option" onClick={() => onMore?.()}>
            <div className="more-icon">
              <MoreOutlined />
            </div>
            <div className="option-info">
              <div className="option-name">选择更多模式</div>
            </div>
            <RightOutlined className="arrow-icon" />
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default AgentDropdown
