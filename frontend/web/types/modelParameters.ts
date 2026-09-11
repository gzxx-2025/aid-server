/** 服务端按当前业务能力返回的表单定义，不包含调用凭证或协议配置。 */
export interface ModelParameter {
  name: string
  label: string
  type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array'
  widget?: string
  description?: string
  unit?: string
  required?: boolean
  defaultValue?: unknown
  minimum?: number
  maximum?: number
  step?: number
  choices?: unknown[]
  properties?: ModelParameter[]
  items?: ModelParameter
  materialRole?: string
  formats?: string[]
  minDurationSeconds?: number
  maxDurationSeconds?: number
  maxTotalDurationSeconds?: number
  maxFileSizeMb?: number
}

export interface ModelParameterCondition {
  match?: 'all' | 'any'
  conditions?: ModelParameterCondition[]
  field?: string
  operator?: string
  value?: unknown
}

export interface ModelParameterRule {
  label?: string
  match: 'all' | 'any'
  conditions: ModelParameterCondition[]
  actions: Array<{ field: string; operator: string; value?: unknown; valueField?: string }>
}
