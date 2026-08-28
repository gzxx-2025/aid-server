import React, { useEffect, useRef, useState } from 'react';
import { Button, Input, InputNumber, Select, Space, Switch, Tag, Tooltip, Empty } from 'antd';
import { DeleteOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';

/**
 * 通用 JSON 对象可视化编辑器：
 * - 受控接受 JSON 字符串作为外部值（与 antd Form.Item 兼容）
 * - 内部把对象拆成 key + type + value 行，运营点点就能配
 * - 支持 string / number / boolean / json 四种值类型
 * - "对象" 类型递归渲染嵌套键值编辑器，运营无需手输 JSON 文本
 *   （仅数组等特殊结构或嵌套过深时降级为 textarea 逃生通道）
 * - 顶部支持"快捷参数"按钮，一键追加常用 key
 *
 * @author aid_author
 * @date 2026-05-27
 */

type ValueType = 'string' | 'number' | 'boolean' | 'json';

interface Row {
  id: number;
  key: string;
  type: ValueType;
  value: any;
}

export interface KvPreset {
  key: string;
  /** 推荐默认值（决定类型） */
  value: any;
  /** 显示名（不传则用 key） */
  label?: string;
  /** 鼠标悬浮说明 */
  tooltip?: string;
}

interface Props {
  /** 外部受控的 JSON 字符串值；空字符串 / null / undefined 表示空对象 */
  value?: string | null;
  /** 变化回调；输出 JSON 字符串，对象为空时输出 null */
  onChange?: (jsonStr: string | null) => void;
  /** 顶部快捷按钮，点击追加一行 */
  presets?: KvPreset[];
  /** 是否禁用整个编辑器（仅查看） */
  disabled?: boolean;
  /** 行内 value 输入框占位 */
  placeholder?: string;
  /** 仅允许 string 类型（如 extra_headers） */
  stringOnly?: boolean;
  /** 空状态文字 */
  emptyText?: string;
  /** 嵌套深度（内部递归用，外部无需传）；超过上限后对象值降级为 textarea */
  depth?: number;
}

/** 对象类型最大可视化嵌套层级，超过后降级为 textarea 逃生通道 */
const MAX_NESTED_DEPTH = 2;

/** 判断 JSON 字符串是否为「可用嵌套行编辑」的普通对象（空串视为空对象；数组/标量不算） */
function isPlainObjectJson(raw: string): boolean {
  const s = (raw ?? '').trim();
  if (!s) return true;
  try {
    const parsed = JSON.parse(s);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed);
  } catch {
    return false;
  }
}

let nextRowId = 1;

function detectType(v: any): ValueType {
  if (typeof v === 'boolean') return 'boolean';
  if (typeof v === 'number') return 'number';
  if (v !== null && typeof v === 'object') return 'json';
  return 'string';
}

function parseJsonStringToRows(jsonStr?: string | null): Row[] {
  if (!jsonStr || !jsonStr.trim()) return [];
  try {
    const obj = JSON.parse(jsonStr);
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return [];
    return Object.entries(obj).map(([k, v]) => {
      const type = detectType(v);
      return {
        id: nextRowId++,
        key: k,
        type,
        value: type === 'json' ? JSON.stringify(v, null, 2) : (v as any)
      };
    });
  } catch {
    return [];
  }
}

function rowsToJsonString(rows: Row[]): string | null {
  const obj: Record<string, any> = {};
  for (const r of rows) {
    const k = r.key?.trim();
    if (!k) continue;
    if (r.type === 'json') {
      const raw = String(r.value ?? '').trim();
      if (!raw) continue;
      try {
        obj[k] = JSON.parse(raw);
      } catch {
        // 非法 JSON 跳过该行（行内会有红色提示）
      }
    } else if (r.type === 'number') {
      if (r.value === '' || r.value === null || r.value === undefined) continue;
      const n = Number(r.value);
      if (!Number.isNaN(n)) obj[k] = n;
    } else if (r.type === 'boolean') {
      obj[k] = !!r.value;
    } else {
      const s = String(r.value ?? '');
      if (s !== '') obj[k] = s;
    }
  }
  if (Object.keys(obj).length === 0) return null;
  return JSON.stringify(obj);
}

const TYPE_OPTIONS = [
  { value: 'string', label: '文本' },
  { value: 'number', label: '数字' },
  { value: 'boolean', label: '开关' },
  { value: 'json', label: '对象' }
];

export default function JsonObjectEditor({
  value,
  onChange,
  presets,
  disabled,
  placeholder,
  stringOnly,
  emptyText,
  depth = 0
}: Props) {
  const [rows, setRows] = useState<Row[]>(() => parseJsonStringToRows(value));
  // 跟踪上一次外部 value，避免内部修改触发的回流死循环
  const lastExternalValue = useRef<string | null | undefined>(value);

  useEffect(() => {
    if (value === lastExternalValue.current) return;
    const currentSerialized = rowsToJsonString(rows);
    // 外部值改变且与当前序列化结果不同时才重置
    if ((value || null) !== (currentSerialized || null)) {
      setRows(parseJsonStringToRows(value));
    }
    lastExternalValue.current = value;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const fireChange = (next: Row[]) => {
    setRows(next);
    const serialized = rowsToJsonString(next);
    lastExternalValue.current = serialized;
    onChange?.(serialized);
  };

  const updateRow = (id: number, patch: Partial<Row>) => {
    fireChange(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  };

  const removeRow = (id: number) => {
    fireChange(rows.filter((r) => r.id !== id));
  };

  const addEmptyRow = () => {
    fireChange([...rows, { id: nextRowId++, key: '', type: stringOnly ? 'string' : 'string', value: '' }]);
  };

  const addPresetRow = (p: KvPreset) => {
    // 已存在同名 key 不重复添加
    if (rows.some((r) => r.key === p.key)) return;
    const type = stringOnly ? 'string' : detectType(p.value);
    const v = type === 'json' ? JSON.stringify(p.value, null, 2) : p.value;
    fireChange([...rows, { id: nextRowId++, key: p.key, type, value: v }]);
  };

  /** 单行的 value 输入控件（按类型分发） */
  const renderValueInput = (r: Row) => {
    if (r.type === 'boolean') {
      return (
        <Switch
          checked={!!r.value}
          disabled={disabled}
          onChange={(c) => updateRow(r.id, { value: c })}
        />
      );
    }
    if (r.type === 'number') {
      return (
        <InputNumber
          value={r.value}
          disabled={disabled}
          placeholder={placeholder || '数字'}
          style={{ width: '100%' }}
          onChange={(v) => updateRow(r.id, { value: v })}
        />
      );
    }
    if (r.type === 'json') {
      const raw = String(r.value ?? '');
      // 普通对象且未超嵌套层级：递归渲染键值编辑器，运营点点即可，无需手输 JSON
      if (depth < MAX_NESTED_DEPTH && isPlainObjectJson(raw)) {
        return (
          <div
            style={{
              border: '1px solid #e2e8f0',
              borderRadius: 6,
              padding: 6,
              background: '#ffffff'
            }}
          >
            <JsonObjectEditor
              value={raw.trim() ? raw : null}
              disabled={disabled}
              depth={depth + 1}
              emptyText="空对象，点击添加子参数"
              onChange={(jsonStr) => updateRow(r.id, { value: jsonStr ?? '' })}
            />
          </div>
        );
      }
      // 数组等特殊结构 / 嵌套过深：降级 textarea 逃生通道，校验是否合法 JSON，不合法时输入框红框
      let invalid = false;
      const trimmed = raw.trim();
      if (trimmed) {
        try { JSON.parse(trimmed); } catch { invalid = true; }
      }
      return (
        <Input.TextArea
          value={r.value}
          disabled={disabled}
          rows={2}
          placeholder='例如 ["a","b"]'
          status={invalid ? 'error' : ''}
          style={{ fontFamily: 'JetBrains Mono, Consolas, Menlo, monospace', fontSize: 12 }}
          onChange={(e) => updateRow(r.id, { value: e.target.value })}
        />
      );
    }
    return (
      <Input
        value={r.value}
        disabled={disabled}
        placeholder={placeholder || '文本'}
        onChange={(e) => updateRow(r.id, { value: e.target.value })}
      />
    );
  };

  return (
    <div className="json-object-editor">
      {presets && presets.length > 0 && !disabled && (
        <div style={{ marginBottom: 8 }}>
          <span style={{ fontSize: 12, color: '#94a3b8', marginRight: 8 }}>
            <ThunderboltOutlined /> 快速添加:
          </span>
          <Space size={4} wrap>
            {presets.map((p) => {
              const exists = rows.some((r) => r.key === p.key);
              return (
                <Tooltip key={p.key} title={p.tooltip}>
                  <Tag.CheckableTag
                    checked={exists}
                    onChange={() => {
                      if (exists) {
                        const target = rows.find((r) => r.key === p.key);
                        if (target) removeRow(target.id);
                      } else {
                        addPresetRow(p);
                      }
                    }}
                    style={{
                      borderRadius: 4,
                      padding: '2px 10px',
                      cursor: 'pointer',
                      fontSize: 12
                    }}
                  >
                    {p.label || p.key}
                  </Tag.CheckableTag>
                </Tooltip>
              );
            })}
          </Space>
        </div>
      )}

      {rows.length === 0 ? (
        <div style={{
          border: '1px dashed #cbd5e1',
          borderRadius: 6,
          padding: '20px 12px',
          textAlign: 'center',
          background: '#f8fafc'
        }}>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<span style={{ color: '#94a3b8', fontSize: 12 }}>{emptyText || '暂无参数'}</span>}
          />
          {!disabled && (
            <Button size="small" type="primary" ghost icon={<PlusOutlined />} onClick={addEmptyRow}>
              添加参数
            </Button>
          )}
        </div>
      ) : (
        <div className="kv-rows" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {rows.map((r) => (
            <div
              key={r.id}
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 6,
                background: '#f8fafc',
                padding: 6,
                borderRadius: 6,
                border: '1px solid #e2e8f0'
              }}
            >
              <Input
                value={r.key}
                disabled={disabled}
                placeholder="参数名"
                style={{ width: 180, fontFamily: 'JetBrains Mono, Consolas, monospace', fontSize: 12 }}
                onChange={(e) => updateRow(r.id, { key: e.target.value })}
              />
              {!stringOnly && (
                <Select
                  value={r.type}
                  disabled={disabled}
                  style={{ width: 84 }}
                  size="middle"
                  options={TYPE_OPTIONS}
                  onChange={(t: ValueType) => {
                    // 切换类型时给 value 一个合理的初值
                    let v: any = r.value;
                    if (t === 'boolean') v = false;
                    else if (t === 'number') v = 0;
                    else if (t === 'json') v = '{}';
                    else v = '';
                    updateRow(r.id, { type: t, value: v });
                  }}
                />
              )}
              <div style={{ flex: 1, minWidth: 0 }}>{renderValueInput(r)}</div>
              {!disabled && (
                <Button
                  type="text"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={() => removeRow(r.id)}
                />
              )}
            </div>
          ))}
          {!disabled && (
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              onClick={addEmptyRow}
              size="small"
              style={{ alignSelf: 'flex-start' }}
            >
              添加参数
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
