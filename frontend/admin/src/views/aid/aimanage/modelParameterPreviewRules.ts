import type { ModelParameter, ModelParameterRule } from './modelDefinition';
import type { ConfigValue } from './StructuredValueEditor';

const read = (value: Record<string, ConfigValue>, path: string): ConfigValue | undefined => path.split('.').reduce<ConfigValue | undefined>((current, key) => current && typeof current === 'object' && !Array.isArray(current) ? current[key] : undefined, value);
const empty = (value: ConfigValue | undefined) => value == null || typeof value === 'string' && !value.trim() || Array.isArray(value) && !value.length;
const ordered = (value: unknown): unknown => Array.isArray(value) ? value.map(ordered)
  : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, ordered(item)])) : value;
const equal = (left: unknown, right: unknown) => JSON.stringify(ordered(left)) === JSON.stringify(ordered(right));

export function previewDefaults(fields: ModelParameter[], value: Record<string, ConfigValue>): Record<string, ConfigValue> {
  const result = { ...value };
  for (const field of fields) {
    if (result[field.name] == null && field.defaultValue !== undefined) result[field.name] = field.defaultValue;
    const current = result[field.name];
    if (current && typeof current === 'object' && !Array.isArray(current) && field.type === 'object') result[field.name] = previewDefaults(field.properties || [], current);
    if (Array.isArray(current) && field.type === 'array' && field.items) {
      const item = field.items;
      result[field.name] = current.map((entry) => previewDefaults([item], { [item.name]: entry })[item.name]);
    }
  }
  return result;
}

export function previewActions(rules: ModelParameterRule[], value: Record<string, ConfigValue>) {
  const matches = (condition: ModelParameterRule['conditions'][number]): boolean => {
    if (condition.match) return condition.match === 'any' ? (condition.conditions || []).some(matches) : (condition.conditions || []).every(matches);
    const current = read(value, condition.field);
    switch (condition.operator) {
      case 'present': return !empty(current);
      case 'absent': return empty(current);
      case 'eq': return equal(current, condition.value);
      case 'neq': return !equal(current, condition.value);
      case 'in': return Array.isArray(condition.value) && condition.value.some((item) => equal(item, current));
      default: {
        if (current == null) return false;
        const number = condition.operator.startsWith('count_') && (typeof current === 'string' || Array.isArray(current)) ? current.length : Number(current);
        const expected = Number(condition.value);
        switch (condition.operator.replace('count_', '')) {
          case 'gt': return number > expected;
          case 'gte': return number >= expected;
          case 'lt': return number < expected;
          case 'lte': return number <= expected;
          default: return false;
        }
      }
    }
  };
  return rules.filter((rule) => rule.match === 'any' ? rule.conditions.some(matches) : rule.conditions.every(matches)).flatMap((rule) => rule.actions).map((action) => action.operator === 'maximum_sum' && action.valueField
    ? { ...action, operator: 'maximum', value: Number(action.value) - Number(read(value, action.valueField) ?? 0) } : action);
}
