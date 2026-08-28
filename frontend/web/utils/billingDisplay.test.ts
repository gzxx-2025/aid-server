/// <reference types="vite/client" />

import { describe,expect,it } from 'vitest'
import billingPanelSource from '../components/home/BillingPanel.tsx?raw'
import type { ModelBillingDetailVO } from '../types/business-api'
import { deriveBillingScenarios,formatRuleExample } from './billingDisplay'
const seedance: ModelBillingDetailVO = {
  id: 14,
  modelCode: 'doubao-seedance-2.0',
  modelName: '豆包Seedance 2.0',
  meterType: 'PER_SECOND',
  columns: [
    { key: 'skuName', label: '档位', type: 'text' },
    { key: 'resolution', label: '分辨率', type: 'text' },
    { key: 'pricePerSecond', label: '每秒单价', unit: 'Credits', type: 'number' },
    { key: 'inputVideoPricePerSecond', label: '输入视频单价', unit: 'Credits/秒', type: 'number' }
  ],
  rules: [
    {
      skuCode: 'SEEDANCE20_720P_INVIDEO',
      skuName: 'Seedance2.0 720P含输入视频',
      resolution: '720P',
      inputVideoCountMin: 1,
      pricePerSecond: 0.66528,
      inputVideoPricePerSecond: 0.66528
    },
    {
      skuCode: 'SEEDANCE20_720P',
      skuName: 'Seedance2.0 720P',
      resolution: '720P',
      pricePerSecond: 1.09296
    }
  ]
}

describe('billing display scenarios', () => {
  it('separates normal generation from input-video pricing', () => {
    const scenarios = deriveBillingScenarios(seedance)

    expect(scenarios.map((scenario) => scenario.type)).toEqual([
      'without-input-video',
      'with-input-video'
    ])
    expect(scenarios[0].title).toBe('图片/文字输入（无输入视频）')
    expect(scenarios[0].rules[0].skuCode).toBe('SEEDANCE20_720P')
    expect(scenarios[1].rules[0].skuCode).toBe('SEEDANCE20_720P_INVIDEO')
    expect(scenarios[1].formula).toContain('输入视频秒数 × 输入视频单价')
  })

  it('shows exact 10-second examples for both scenarios', () => {
    const scenarios = deriveBillingScenarios(seedance)

    expect(formatRuleExample(scenarios[0].rules[0], scenarios[0].type, 'Credits'))
      .toBe('10秒输出费用 10.9296 Credits')
    expect(formatRuleExample(scenarios[1].rules[0], scenarios[1].type, 'Credits'))
      .toBe('10秒输出 + 5秒输入示例：6.6528 + 3.3264 = 9.9792 Credits')
  })

  it('does not expose multiplier instructions in the billing subtitle', () => {
    // 原断言目标 BillingPanel.vue 的 {{ creditUnit }}，迁移后为 JSX 插值
    expect(billingPanelSource).toContain('单位：{creditUnit}')
    expect(billingPanelSource).not.toContain('无需再乘任何倍率')
  })

  it('uses the themed model provider selector instead of the native browser menu', () => {
    // 原断言 <a-select popup-class-name="...">，antd React 版为 <Select classNames.popup.root
    expect(billingPanelSource).toContain('<Select')
    expect(billingPanelSource).toContain("classNames={{ popup: { root: 'billing-page__provider-popup' } }}")
    expect(billingPanelSource).not.toContain('<select')
  })
})
