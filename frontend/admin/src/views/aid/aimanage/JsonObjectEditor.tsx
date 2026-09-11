import React from 'react';
import { Alert, Button, Space, Tooltip } from 'antd';
import StructuredValueEditor, { type ConfigValue } from './StructuredValueEditor';

export interface KvPreset {
  key: string;
  value: ConfigValue;
  label?: string;
  tooltip?: string;
}
interface Props {
  value?: string | null;
  onChange?: (value: string | null) => void;
  presets?: KvPreset[];
  disabled?: boolean;
  placeholder?: string;
  stringOnly?: boolean;
  emptyText?: string;
  depth?: number;
}

/** 兼容存量接口的可视化对象编辑器。 */
export default function JsonObjectEditor({ value, onChange, presets, disabled, stringOnly }: Props) {
  let object: Record<string, ConfigValue>;
  try {
    object = value ? JSON.parse(value) : {};
    if (!object || Array.isArray(object) || typeof object !== 'object') throw new Error('invalid object');
  } catch {
    return <Alert type="error" showIcon message="已有配置无法读取，请先修复配置来源；当前内容不会被覆盖。" />;
  }
  const change = (next: ConfigValue) => onChange?.(next && typeof next === 'object' && Object.keys(next).length ? JSON.stringify(next) : null);
  return <Space direction="vertical" style={{ width: '100%' }}>
    {presets?.length ? <Space wrap>{presets.map((preset) => <Tooltip key={preset.key} title={preset.tooltip}>
      <Button disabled={disabled || Object.prototype.hasOwnProperty.call(object, preset.key)} onClick={() => change({ ...object, [preset.key]: preset.value })}>{preset.label || preset.key}</Button>
    </Tooltip>)}</Space> : null}
    <StructuredValueEditor value={object} onChange={change} disabled={disabled} fixedType="object" stringValuesOnly={stringOnly} label="附加参数" />
    {stringOnly && Object.values(object).some((item) => typeof item !== 'string') && <Alert type="error" message="此处的参数值必须使用文本类型" />}
  </Space>;
}
