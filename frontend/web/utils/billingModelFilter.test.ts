import { describe,expect,it } from 'vitest'
import type { ModelBillingDetailVO } from '../types/business-api'
import { filterBillingModels,listBillingModelProviders } from './billingModelFilter'
function model(
  id: number,
  modelName: string,
  modelType: string,
  providerName?: string
): ModelBillingDetailVO {
  return {
    id,
    modelCode: `model-${id}`,
    modelName,
    modelType,
    providerName,
    columns: [],
    rules: []
  }
}

const models = [
  model(1, '千问文本', 'text', '阿里百炼'),
  model(2, '万相生图', 'image', '阿里百炼'),
  model(3, 'Seedream 生图', 'image', '火山方舟'),
  model(4, '配音模型', 'voice', 'MiniMax'),
  model(5, '无厂商模型', 'video')
]

describe('billing model filters', () => {
  it('derives unique providers only from the supplied API result', () => {
    expect(listBillingModelProviders(models)).toEqual(['阿里百炼', '火山方舟', 'MiniMax'])
  })

  it('combines model type, provider and keyword filters', () => {
    expect(filterBillingModels(models, {
      modelType: 'image',
      providerName: '阿里百炼',
      keyword: '万相'
    }).map((item) => item.id)).toEqual([2])

    expect(filterBillingModels(models, {
      modelType: 'image',
      providerName: '火山方舟',
      keyword: 'model-3'
    }).map((item) => item.id)).toEqual([3])
  })

  it('treats voice API rows as the audio tab', () => {
    expect(filterBillingModels(models, {
      modelType: 'audio',
      providerName: '',
      keyword: ''
    }).map((item) => item.id)).toEqual([4])
  })
})
