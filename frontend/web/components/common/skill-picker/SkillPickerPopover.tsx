'use client'

import {
  cloneElement,
  isValidElement,
  type ReactElement,
  type ReactNode,
  useEffect,
  useState
} from 'react'
import { Popover, Tooltip, type TooltipProps } from 'antd'
import lightningIconRaw from '~/assets/img/icon/lightning.svg'
import skillIconRaw from '~/assets/img/icon/skill.svg'
import type { UserSkillDefinition } from '~/types/user-skill'
import { assetUrl } from '~/utils/assetUrl'
import { toSkillPickerCard } from '~/utils/storyScriptAgentSkillPicker'
import './skill-picker-popover.css'

const skillTriggerIconUrl = assetUrl(skillIconRaw)
const skillListFallbackIconUrl = assetUrl(lightningIconRaw)

export interface SkillPickerPopoverProps {
  skills: UserSkillDefinition[]
  selectedSkillCode: string
  loading?: boolean
  error?: string
  disabled?: boolean
  onRequestLoad?: () => void
  onSelect: (skillCode: string) => void
  placement?: TooltipProps['placement']
  /** 标题旁展示 `/skillCode`。首页默认关掉，对话面板保持 slash 提示。 */
  showSkillCode?: boolean
  children?: ReactNode
}

export function SkillPickerPopover({
  skills,
  selectedSkillCode,
  loading = false,
  error = '',
  disabled = false,
  onRequestLoad,
  onSelect,
  placement = 'topLeft',
  showSkillCode = true,
  children
}: SkillPickerPopoverProps) {
  const [open, setOpen] = useState(false)
  const cards = skills.map(toSkillPickerCard).filter((item) => item.skillCode)
  const tooltip = 'Skill'

  useEffect(() => {
    if (!open) return undefined
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopPropagation()
      setOpen(false)
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [open])

  function selectSkill(skillCode: string) {
    if (disabled) return
    onSelect(skillCode)
    setOpen(false)
  }

  return (
    <Popover
      trigger="click"
      placement={placement}
      arrow={false}
      open={disabled ? false : open}
      onOpenChange={(next) => {
        if (disabled) return
        setOpen(next)
        if (next) onRequestLoad?.()
      }}
      destroyOnHidden
      getPopupContainer={() => document.body}
      classNames={{ root: 'skill-picker-popover' }}
      styles={{
        container: { padding: 0, background: 'transparent' },
        content: { padding: 0 }
      }}
      content={
        <div className="skill-picker-popover__panel" role="listbox" aria-label="选择 Skill">
          <header className="skill-picker-popover__head">Skill</header>
          {loading ? <p className="skill-picker-popover__hint">Skill 加载中…</p> : null}
          {!loading && error ? (
            <button
              type="button"
              className="skill-picker-popover__retry"
              onClick={() => onRequestLoad?.()}
            >
              <span>{error}</span>
              <strong>重新加载</strong>
            </button>
          ) : null}
          {!loading && !error && cards.length === 0 ? (
            <p className="skill-picker-popover__hint">暂无可用 Skill</p>
          ) : null}
          <div className="skill-picker-popover__list">
            {cards.map((item) => {
              const active = item.skillCode === selectedSkillCode
              const showCode = Boolean(
                showSkillCode && item.skillCode && item.skillCode !== item.name
              )
              return (
                <button
                  key={item.skillCode}
                  type="button"
                  role="option"
                  aria-selected={active}
                  className={`skill-picker-popover__item${active ? ' is-active' : ''}`}
                  onClick={() => selectSkill(item.skillCode)}
                >
                  <span className="skill-picker-popover__glyph">
                    <img src={item.iconUrl || skillListFallbackIconUrl} alt="" />
                  </span>
                  <span className="skill-picker-popover__meta">
                    <span className="skill-picker-popover__title">
                      <strong>{item.name}</strong>
                      {showCode ? <em>/{item.skillCode}</em> : null}
                    </span>
                    {item.description ? <small>{item.description}</small> : null}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      }
    >
      {renderSkillTrigger(children, open, disabled, tooltip)}
    </Popover>
  )
}

function renderSkillTrigger(
  children: ReactNode,
  open: boolean,
  disabled: boolean,
  tooltip: string
) {
  if (isValidElement(children)) {
    const trigger = children as ReactElement<{
      className?: string
      disabled?: boolean
      'aria-expanded'?: boolean
    }>
    return cloneElement(trigger, {
      'aria-expanded': open,
      disabled: trigger.props.disabled ?? disabled,
      className: [trigger.props.className, open ? 'is-open' : ''].filter(Boolean).join(' ')
    })
  }
  return (
    <Tooltip title={open ? undefined : tooltip} placement="top">
      <button
        type="button"
        className={`skill-picker-trigger${open ? ' is-open' : ''}`}
        aria-label={tooltip}
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
      >
        <img src={skillTriggerIconUrl} alt="" width={24} height={24} />
      </button>
    </Tooltip>
  )
}

export default SkillPickerPopover
