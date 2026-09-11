import type { UserModelListItem } from '~/types/business-api'
import type { ModelParameter, ModelParameterCondition } from '~/types/modelParameters'

type Values = Record<string, unknown>
export interface ParameterConstraint {
  choices?: unknown[]
  minimum?: number
  maximum?: number
  step?: number
  stepBase?: number
  forbidden?: boolean
}
const read = (values: Values, path = ''): unknown => path.split('.').reduce<unknown>((value, key) => value && typeof value === 'object' && !Array.isArray(value) ? (value as Values)[key] : undefined, values)
const empty = (value: unknown) => value == null || typeof value === 'string' && !value.trim() || Array.isArray(value) && value.length === 0
const same = (left: unknown, right: unknown): boolean => {
  if (left === right || left == null && right == null) return true
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((item, index) => same(item, right[index]))
  if (left && right && typeof left === 'object' && typeof right === 'object') {
    const a = left as Values, b = right as Values
    return Object.keys(a).length === Object.keys(b).length && Object.keys(a).every((key) => Object.hasOwn(b, key) && same(a[key], b[key]))
  }
  return false
}
const matches = (condition: ModelParameterCondition, values: Values): boolean | undefined => {
  if (condition.match) {
    const results = (condition.conditions || []).map((item) => matches(item, values))
    return condition.match === 'any' ? results.includes(true) ? true : results.includes(undefined) ? undefined : false
      : results.includes(false) ? false : results.includes(undefined) ? undefined : true
  }
  const current = read(values, condition.field)
  if (condition.field?.startsWith('materials.') && current == null) return undefined
  switch (condition.operator) {
    case 'present': return !empty(current)
    case 'absent': return empty(current)
    case 'eq': return same(current, condition.value)
    case 'neq': return !same(current, condition.value)
    case 'in': return Array.isArray(condition.value) && condition.value.some((value) => same(value, current))
    default: {
      if (current == null) return false
      const numeric = condition.operator?.startsWith('count_') && (Array.isArray(current) || typeof current === 'string') ? current.length : Number(current)
      const expected = Number(condition.value)
      switch (condition.operator?.replace('count_', '')) {
        case 'gt': return numeric > expected
        case 'gte': return numeric >= expected
        case 'lt': return numeric < expected
        case 'lte': return numeric <= expected
        default: return false
      }
    }
  }
}

/** 公共参数组件只收窄当前能力已声明的候选；未知素材时长留给服务端核验。 */
export function modelParameterConstraints(model: UserModelListItem | null | undefined, supplied: Values = {}): Map<string, ParameterConstraint> {
  const constraints = new Map<string, ParameterConstraint>()
  const collect = (fields: ModelParameter[], values: Values, prefix = ''): Values => {
    const result = { ...values }
    for (const field of fields) {
      const path = prefix + field.name
      if (result[field.name] == null && field.defaultValue != null) result[field.name] = field.defaultValue
      if (['size', 'aspectRatio', 'options.aspectRatio', 'options.size', 'options.resolution'].includes(path) && typeof result[field.name] === 'string') {
        const canonical = field.choices?.find((choice) => typeof choice === 'string' && choice.trim().toLowerCase() === String(result[field.name]).trim().toLowerCase())
        if (canonical !== undefined) result[field.name] = canonical
      }
      constraints.set(path, { choices: field.choices?.length ? field.choices : undefined, minimum: field.minimum, maximum: field.maximum, step: field.step, stepBase: field.minimum ?? 0 })
      const current = result[field.name]
      if (field.type === 'object' && current && typeof current === 'object' && !Array.isArray(current)) result[field.name] = collect(field.properties || [], current as Values, `${path}.`)
      else if (field.properties) collect(field.properties, {}, `${path}.`)
    }
    return result
  }
  const values = collect(model?.parameterSchema || [], supplied)
  for (const rule of model?.parameterRules || []) {
    if (matches({ match: rule.match, conditions: rule.conditions }, values) !== true) continue
    for (const action of rule.actions) {
      const constraint = constraints.get(action.field) || {}
      const number = Number(action.value)
      if (action.operator === 'forbidden') constraint.forbidden = true
      if (action.operator === 'minimum') constraint.minimum = Math.max(constraint.minimum ?? -Infinity, number)
      if (action.operator === 'maximum') constraint.maximum = Math.min(constraint.maximum ?? Infinity, number)
      if (action.operator === 'maximum_sum' && action.valueField && read(values, action.valueField) != null) constraint.maximum = Math.min(constraint.maximum ?? Infinity, number - Number(read(values, action.valueField)))
      const choices = action.operator === 'fixed' ? [action.value] : action.operator === 'choices' && Array.isArray(action.value) ? action.value : undefined
      if (choices) constraint.choices = constraint.choices ? constraint.choices.filter((value) => choices.some((choice) => same(value, choice))) : choices
      constraints.set(action.field, constraint)
    }
  }
  return constraints
}

export function allowedParameterOptions<T extends string | number | boolean>(options: T[], constraint?: ParameterConstraint): T[] {
  if (!constraint) return options
  if (constraint.forbidden) return []
  return options.filter((value) => (constraint.choices == null || constraint.choices.some((choice) => typeof value === 'string' && typeof choice === 'string' ? value.toLowerCase() === choice.toLowerCase() : same(value, choice)))
    && (typeof value !== 'number' || (constraint.minimum == null || value >= constraint.minimum) && (constraint.maximum == null || value <= constraint.maximum)
      && (constraint.step == null || Math.abs((value - (constraint.stepBase ?? 0)) / constraint.step - Math.round((value - (constraint.stepBase ?? 0)) / constraint.step)) < 1e-9)))
}
