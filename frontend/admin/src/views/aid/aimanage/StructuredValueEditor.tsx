import React from 'react';
import { Button, Card, Input, InputNumber, Select, Space, Switch, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';

export type ConfigValue = string | number | boolean | null | ConfigValue[] | { [key: string]: ConfigValue };
type ValueType = 'string' | 'number' | 'boolean' | 'null' | 'object' | 'array';
const options = [
  { value: 'string', label: '文本' }, { value: 'number', label: '数字' },
  { value: 'boolean', label: '开关' }, { value: 'object', label: '分组' },
  { value: 'array', label: '列表' }, { value: 'null', label: '空值' }
];
const initial = (type: ValueType): ConfigValue => ({ string: '', number: 0, boolean: false, null: null, object: {}, array: [] }[type]);
const typeOf = (value: ConfigValue): ValueType => value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value as ValueType;

interface Props {
  value: ConfigValue;
  onChange: (value: ConfigValue) => void;
  disabled?: boolean;
  stringOnly?: boolean;
  label?: string;
  depth?: number;
  fixedType?: ValueType;
  stringValuesOnly?: boolean;
}

/** 通过分组和列表控件编辑结构化值。 */
export default function StructuredValueEditor({ value, onChange, disabled, stringOnly, stringValuesOnly, label = '参数值', depth = 0, fixedType }: Props) {
  const type = fixedType || typeOf(value);
  const entries = type === 'object' && value && !Array.isArray(value) && typeof value === 'object' ? Object.entries(value) : [];
  const list = type === 'array' && Array.isArray(value) ? value : [];
  const updateEntry = (index: number, key: string, item: ConfigValue) => {
    const next = entries.map(([oldKey, oldValue], i) => i === index ? [key, item] : [oldKey, oldValue]);
    onChange(Object.fromEntries(next));
  };
  const addEntry = () => {
    let index = 1;
    while (entries.some(([key]) => key === `parameter_${index}`)) index++;
    onChange({ ...Object.fromEntries(entries), [`parameter_${index}`]: '' });
  };
  return <Space direction="vertical" style={{ width: '100%' }} size="small">
    {!fixedType && !stringOnly && <Select aria-label={`${label}类型`} value={type} options={options}
      disabled={disabled} onChange={(next) => onChange(initial(next))} style={{ width: 112 }} />}
    {(type === 'string' || stringOnly) && <Input aria-label={label} value={typeof value === 'string' ? value : ''} disabled={disabled} onChange={(e) => onChange(e.target.value)} />}
    {!stringOnly && type === 'number' && <InputNumber aria-label={label} value={typeof value === 'number' ? value : undefined} disabled={disabled} onChange={(next) => { if (next !== null) onChange(next); }} style={{ width: '100%' }} />}
    {!stringOnly && type === 'boolean' && <Switch aria-label={label} checked={value === true} disabled={disabled} onChange={onChange} />}
    {!stringOnly && type === 'null' && <Typography.Text type="secondary">明确发送空值</Typography.Text>}
    {!stringOnly && type === 'object' && <>
      {entries.map(([key, item], index) => <Card size="small" key={index}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input aria-label={`${label}字段名称 ${index + 1}`} value={key} disabled={disabled}
              onChange={(e) => { const next = e.target.value; if (!entries.some(([other], i) => i !== index && other === next)) updateEntry(index, next, item); }} />
            <Button aria-label={`删除字段 ${key}`} icon={<DeleteOutlined />} disabled={disabled} onClick={() => onChange(Object.fromEntries(entries.filter((_, i) => i !== index)))} />
          </Space.Compact>
          {!key.trim() && <Typography.Text type="danger">请填写字段名称</Typography.Text>}
          <StructuredValueEditor value={item} label={key || label} depth={depth + 1} disabled={disabled} stringOnly={stringValuesOnly} onChange={(next) => updateEntry(index, key, next)} />
        </Space>
      </Card>)}
      <Button icon={<PlusOutlined />} disabled={disabled || depth >= 16} onClick={addEntry}>添加字段</Button>
    </>}
    {!stringOnly && type === 'array' && <>
      {list.map((item, index) => <Card size="small" key={index} title={`第 ${index + 1} 项`} extra={<Button aria-label={`删除第 ${index + 1} 项`} disabled={disabled} icon={<DeleteOutlined />} onClick={() => onChange(list.filter((_, i) => i !== index))} />}>
        <StructuredValueEditor value={item} label={`${label}第 ${index + 1} 项`} depth={depth + 1} disabled={disabled} onChange={(next) => onChange(list.map((old, i) => i === index ? next : old))} />
      </Card>)}
      <Button icon={<PlusOutlined />} disabled={disabled || depth >= 16} onClick={() => onChange([...list, ''])}>添加列表项</Button>
    </>}
  </Space>;
}
