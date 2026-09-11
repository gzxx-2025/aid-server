import React from 'react';
import { Button, Card, Form, Input, InputNumber, Select, Space, Switch } from 'antd';
import type { ConfigValue } from './StructuredValueEditor';
import type { ModelParameter, ModelParameterRule } from './modelDefinition';
import { MATERIAL_STATISTICS } from './modelDefinition';
import { previewActions, previewDefaults } from './modelParameterPreviewRules';

const ordered = (value: unknown): unknown => Array.isArray(value) ? value.map(ordered) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, ordered((value as Record<string, unknown>)[key])])) : value;
const equalChoice = (left: unknown, right: unknown) => JSON.stringify(ordered(left)) === JSON.stringify(ordered(right));

export default function ModelParameterPreview({ fields, value, onChange, rules = [], actions, prefix = '', disabled = false, showMaterialStatistics = true, errors = {} }: { fields: ModelParameter[]; value: Record<string, ConfigValue>; onChange: (value: Record<string, ConfigValue>) => void; rules?: ModelParameterRule[]; actions?: ModelParameterRule['actions']; prefix?: string; disabled?: boolean; showMaterialStatistics?: boolean; errors?: Record<string, string> }) {
  const usesStatistics = showMaterialStatistics && prefix === '' && rules.some((rule) => JSON.stringify(rule).includes('materials.'));
  const statistics = { ...Object.fromEntries(MATERIAL_STATISTICS.map((item) => [item.value.split('.')[1], 0])),
    ...(value.materials && typeof value.materials === 'object' && !Array.isArray(value.materials) ? value.materials : {}) };
  const defaults = previewDefaults(fields, usesStatistics ? { ...value, materials: statistics } : value);
  const constraints = actions || previewActions(showMaterialStatistics ? rules : rules.filter((rule) => !JSON.stringify(rule).includes('materials.')), defaults);
  return <>{usesStatistics && <Card size="small" title="预览素材统计" extra="实际提交时由服务端核验素材后计算"><Space wrap>{MATERIAL_STATISTICS.map((item) => <Form.Item key={item.value} label={item.label}><InputNumber min={0} value={Number(statistics[item.value.split('.')[1]] || 0)} onChange={(next) => onChange({ ...value, materials: { ...statistics, [item.value.split('.')[1]]: next || 0 } })} /></Form.Item>)}</Space></Card>}{fields.map((field) => {
    const rulesForField = constraints.filter((action) => action.field === prefix + field.name);
    const fixed = rulesForField.find((action) => action.operator === 'fixed');
    const forbidden = rulesForField.some((action) => action.operator === 'forbidden');
    const locked = disabled || forbidden || Boolean(fixed);
    const current = fixed ? fixed.value : defaults[field.name];
    const minimum = Math.max(field.minimum ?? -Infinity, ...rulesForField.filter((a) => a.operator === 'minimum').map((a) => Number(a.value)));
    const maximum = Math.min(field.maximum ?? Infinity, ...rulesForField.filter((a) => a.operator === 'maximum').map((a) => Number(a.value)));
    let choices = field.choices;
    for (const action of rulesForField.filter((a) => a.operator === 'choices')) if (Array.isArray(action.value)) choices = choices ? choices.filter((choice) => (action.value as ConfigValue[]).some((allowed) => equalChoice(allowed, choice))) : action.value;
    const change = (next: ConfigValue) => onChange({ ...value, [field.name]: next });
    let control: React.ReactNode;
    if (choices) {
      const available = choices;
      const keyOf = (value: unknown) => { const index = available.findIndex((choice) => equalChoice(choice, value)); return index < 0 ? undefined : String(index); };
      const selected = field.type === 'array' ? (Array.isArray(current) ? current : []).map(keyOf).filter((key) => key !== undefined) : keyOf(current);
      control = <Select disabled={locked || !available.length} placeholder={available.length ? undefined : '当前条件下无可用选项'} mode={field.type === 'array' ? 'multiple' : undefined} value={selected} options={available.map((option, index) => ({ value: String(index), label: typeof option === 'object' ? `选项 ${index + 1}` : String(option) }))} onChange={(next) => change(Array.isArray(next) ? next.map((key) => available[Number(key)]) : available[Number(next)])} />;
    }
    else if (field.type === 'boolean') control = <Switch disabled={locked} checked={current === true} onChange={change} />;
    else if (field.type === 'number' || field.type === 'integer') control = <InputNumber disabled={locked} min={Number.isFinite(minimum) ? minimum : undefined} max={Number.isFinite(maximum) ? maximum : undefined} step={field.step || 1} precision={field.type === 'integer' ? 0 : undefined} value={typeof current === 'number' ? current : undefined} onChange={(next) => { if (next !== null) change(next); }} addonAfter={field.unit} />;
    else if (field.type === 'object') control = <ModelParameterPreview disabled={locked} fields={field.properties || []} value={current && typeof current === 'object' && !Array.isArray(current) ? current : {}} onChange={change} actions={constraints} errors={errors} prefix={`${prefix}${field.name}.`} />;
    else if (field.type === 'array' && field.items) {
      const list = Array.isArray(current) ? current : [];
      const item = field.items;
      const initial: ConfigValue = item.defaultValue ?? (item.type === 'object' ? {} : item.type === 'array' ? [] : item.type === 'boolean' ? false : item.type === 'string' ? '' : 0);
      control = <Space direction="vertical" style={{ width: '100%' }}>{list.map((entry, index) => <Card key={index} size="small" title={`第 ${index + 1} 项`} extra={<Button disabled={locked} onClick={() => change(list.filter((_, i) => i !== index))}>删除</Button>}><ModelParameterPreview disabled={locked} fields={[item]} value={{ [item.name]: entry }} onChange={(next) => change(list.map((old, i) => i === index ? next[item.name] : old))} /></Card>)}<Button disabled={locked || list.length >= maximum} onClick={() => change([...list, initial])}>添加列表项</Button></Space>;
    }
    else if (field.widget === 'textarea') control = <Input.TextArea disabled={locked} value={typeof current === 'string' ? current : ''} onChange={(e) => change(e.target.value)} />;
    else control = <Input disabled={locked} value={typeof current === 'string' ? current : ''} onChange={(e) => change(e.target.value)} placeholder={field.materialRole ? '输入素材地址预览参数' : undefined} />;
    const error = errors[prefix + field.name] || Object.entries(errors).find(([path]) => path.startsWith(`${prefix}${field.name}[`))?.[1];
    return <Form.Item key={field.name} label={field.label} validateStatus={error ? 'error' : undefined} help={error} required={field.required || rulesForField.some((a) => a.operator === 'required')} extra={<>{field.description}{forbidden && ' 当前组合禁用此参数。'}{fixed && ' 当前组合使用固定值。'}{(forbidden || fixed) && value[field.name] != null && <Button size="small" type="link" onClick={() => { const next = { ...value }; delete next[field.name]; onChange(next); }}>清除原值</Button>}</>}>{control}</Form.Item>;
  })}</>;
}
