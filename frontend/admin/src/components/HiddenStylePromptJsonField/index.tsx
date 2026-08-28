import React, { useMemo } from 'react';
import { Alert, Collapse, Input, Typography } from 'antd';

type HiddenStylePromptKey = 'character' | 'scene' | 'prop';

interface HiddenStylePromptValue {
  character: string;
  scene: string;
  prop: string;
}

interface HiddenStylePromptJsonFieldProps {
  value?: string | Partial<HiddenStylePromptValue> | null;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  readOnlyKeys?: HiddenStylePromptKey[];
}

const EMPTY_VALUE: HiddenStylePromptValue = {
  character: '',
  scene: '',
  prop: ''
};

const PANEL_META: Array<{ key: HiddenStylePromptKey; label: string }> = [
  { key: 'character', label: 'Character / 角色' },
  { key: 'scene', label: 'Scene / 场景' },
  { key: 'prop', label: 'Prop / 道具' }
];

function normalizePromptValue(value: unknown): { data: HiddenStylePromptValue; invalid: boolean } {
  if (value === null || value === undefined || value === '') {
    return { data: { ...EMPTY_VALUE }, invalid: false };
  }

  let raw: unknown = value;
  if (typeof value === 'string') {
    try {
      raw = JSON.parse(value);
    } catch {
      return { data: { ...EMPTY_VALUE }, invalid: true };
    }
  }

  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return { data: { ...EMPTY_VALUE }, invalid: true };
  }

  const record = raw as Record<string, unknown>;
  return {
    data: {
      character: typeof record.character === 'string' ? record.character : '',
      scene: typeof record.scene === 'string' ? record.scene : '',
      prop: typeof record.prop === 'string' ? record.prop : ''
    },
    invalid: false
  };
}

/** 隐藏风格 JSON 的统一编辑与只读展示组件。 */
export default function HiddenStylePromptJsonField({
  value,
  onChange,
  readOnly = false,
  readOnlyKeys = []
}: HiddenStylePromptJsonFieldProps) {
  const normalized = useMemo(() => normalizePromptValue(value), [value]);

  const updatePrompt = (key: HiddenStylePromptKey, prompt: string) => {
    if (readOnly || readOnlyKeys.includes(key)) return;
    onChange?.(JSON.stringify({ ...normalized.data, [key]: prompt }));
  };

  const items = PANEL_META.map(({ key, label }) => ({
    key,
    label,
    children: readOnly && !normalized.data[key] ? (
      <Typography.Text type="secondary">暂无内容</Typography.Text>
    ) : (
      <Input.TextArea
        value={normalized.data[key]}
        autoSize={{ minRows: 5, maxRows: 16 }}
        readOnly={readOnly || readOnlyKeys.includes(key)}
        placeholder={readOnly || readOnlyKeys.includes(key) ? undefined : `请输入 ${label} 隐藏提示词`}
        style={{ fontFamily: 'Consolas, Menlo, Monaco, "Courier New", monospace', lineHeight: 1.7 }}
        onChange={(event) => updatePrompt(key, event.target.value)}
      />
    )
  }));

  return (
    <div style={{ width: '100%' }}>
      {normalized.invalid && (
        <Alert
          type="warning"
          showIcon
          message="隐藏风格提示词 JSON 格式无效"
          description={readOnly ? '请检查该记录中的原始数据。' : '编辑任一面板后将按标准结构重新保存。'}
          style={{ marginBottom: 12 }}
        />
      )}
      <Collapse items={items} defaultActiveKey={[]} />
    </div>
  );
}
