'use client'

import { useEffect, useRef } from 'react'
import { Popover } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import { useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { useStudioPreferredModelCode } from '@/hooks/studio/useStudioPreferredModelCode'
import type { UserModelListItem } from '@/types/business-api'
import type { StudioMediaKind } from '@/types/studio'
import { resolveUserModelProviderLogo } from '@/utils/userModelOption'
import {
  isStudioComposerModelInOptions,
  pickStudioComposerDefaultModel
} from '@/utils/studio/studioComposerDefaultModel'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioModelProviderIcon } from './StudioModelProviderIcon'

export function StudioModelPicker({
  value,
  mediaKind,
  sceneCode,
  options: optionsProp,
  preferredModelCode: preferredModelCodeProp,
  onChange
}: {
  value?: string
  mediaKind?: StudioMediaKind
  /** 生成配置 / 出片智能体 sceneCode；有项目时用于默认模型 */
  sceneCode?: string
  /** 外部模型池（流程节点专用池）；不传则用通用 catalog */
  options?: UserModelListItem[]
  preferredModelCode?: string
  onChange: (value: string, model: UserModelListItem) => void
}) {
  const catalog = useStudioModels(mediaKind ?? 'image', { enabled: optionsProp == null })
  const options = optionsProp ?? catalog.data
  const loading = optionsProp ? false : catalog.loading
  const error = optionsProp ? '' : catalog.error
  const preferredFromConfig = useStudioPreferredModelCode(sceneCode)
  const preferredModelCode = String(preferredModelCodeProp || preferredFromConfig || '').trim()
  const matched = isStudioComposerModelInOptions(options, value)
    ? options.find((item) => String(item.modelCode || '').trim() === String(value || '').trim())
    : undefined
  const fallback = pickStudioComposerDefaultModel(options, [preferredModelCode])
  const selected = matched ?? fallback
  const selectedValue = selected?.modelCode
  const label = selected?.modelName
    ?? (loading ? '加载模型' : options.length ? '选择模型' : '暂无模型')
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    if (!options.length) return
    if (value && isStudioComposerModelInOptions(options, value)) return
    const next = pickStudioComposerDefaultModel(options, [preferredModelCode])
    if (!next) return
    onChangeRef.current(next.modelCode, next)
  }, [options, preferredModelCode, value])

  return (
    <Popover
      trigger="click"
      placement="topLeft"
      arrow={false}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
      content={(
        <div className="studio-model-menu" role="listbox" aria-label="选择生成模型">
          <header>模型</header>
          <div className="studio-model-menu__list">
            {loading ? <p className="studio-model-menu__state">正在读取可用模型…</p> : null}
            {!loading && error ? <p className="studio-model-menu__state is-error">{error}</p> : null}
            {!loading && !error && !options.length ? <p className="studio-model-menu__state">暂无可用模型</p> : null}
            {options.map((item) => (
              <button
                key={item.modelCode}
                type="button"
                role="option"
                aria-selected={item.modelCode === selectedValue}
                className={item.modelCode === selectedValue ? 'is-active' : ''}
                onClick={() => onChange(item.modelCode, item)}
              >
                <i>
                  <StudioModelProviderIcon
                    logo={resolveUserModelProviderLogo(item)}
                    name={item.modelName}
                  />
                </i>
                <span><strong>{item.modelName}</strong><small>{item.providerName || item.modelCode}</small></span>
                <em>{item.isFree ? '免费' : item.costCredits == null ? '按配置计费' : `${item.costCredits} 积分`}</em>
                {item.modelCode === selectedValue ? <CheckOutlined className="studio-model-menu__check" /> : null}
              </button>
            ))}
          </div>
        </div>
      )}
    >
      <StudioComposerChip
        className="studio-composer-chip--model"
        icon={(
          <StudioModelProviderIcon
            logo={selected ? resolveUserModelProviderLogo(selected) : ''}
            name={selected?.modelName}
          />
        )}
        label={label}
        title={typeof label === 'string' ? label : undefined}
      />
    </Popover>
  )
}
