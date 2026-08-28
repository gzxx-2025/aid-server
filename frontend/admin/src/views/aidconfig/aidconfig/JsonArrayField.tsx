import React, { useMemo } from 'react';
import { Button, Empty, Select } from 'antd';
import { DeleteOutlined, PlusOutlined, WarningOutlined } from '@ant-design/icons';

import JsonObjectEditor from '@/views/aid/aimanage/JsonObjectEditor';

interface Props {
  /** 外部受控的 JSON 数组字符串；空串视为空数组 */
  value: string;
  onChange: (v: string) => void;
}

/** 解析结果为三种形态：基本值数组（标签编辑）、对象数组（逐行键值编辑）、非法数据（只读预览） */
type Parsed =
  | { kind: 'primitives'; items: any[] }
  | { kind: 'objects'; items: Record<string, any>[] }
  | { kind: 'invalid' };

function parseValue(value: string): Parsed {
  const s = (value ?? '').trim();
  if (!s) return { kind: 'objects', items: [] };
  try {
    const arr = JSON.parse(s);
    if (!Array.isArray(arr)) return { kind: 'invalid' };
    if (arr.every((it) => it === null || typeof it !== 'object')) {
      return { kind: 'primitives', items: arr };
    }
    if (arr.every((it) => it !== null && typeof it === 'object' && !Array.isArray(it))) {
      return { kind: 'objects', items: arr };
    }
    return { kind: 'invalid' };
  } catch {
    return { kind: 'invalid' };
  }
}

/** 标签值序列化：原本全是数字时按数字写回，避免类型被静默改掉 */
function serializePrimitives(items: string[], allNumber: boolean): any[] {
  return items.map((s) => {
    if (!allNumber) return s;
    const n = Number(s);
    return s.trim() !== '' && !Number.isNaN(n) ? n : s;
  });
}

/**
 * JSON 数组可视化编辑器：基本值数组用标签输入，对象数组逐行用键值编辑器维护，
 * 数据非法时降级为只读预览，任何形态都不需要运营手写 JSON 文本
 */
export default function JsonArrayField({ value, onChange }: Props) {
  const parsed = useMemo(() => parseValue(value), [value]);

  if (parsed.kind === 'invalid') {
    return (
      <div style={{ width: '100%' }}>
        <pre className="readonly-preview" style={{ maxHeight: 160 }}>{value}</pre>
        <div className="help-text" style={{ marginTop: 6, color: '#b45309' }}>
          <WarningOutlined /> 当前配置不是可可视化编辑的结构（需为 JSON 对象数组或基本值数组），已切换为只读预览，请联系开发人员处理。
        </div>
      </div>
    );
  }

  if (parsed.kind === 'primitives') {
    const allNumber = parsed.items.length > 0 && parsed.items.every((it) => typeof it === 'number');
    return (
      <Select
        mode="tags"
        value={parsed.items.map((it) => String(it))}
        tokenSeparators={[',', '，']}
        notFoundContent={null}
        placeholder="输入一项后回车，可连续添加多项"
        style={{ width: '100%' }}
        open={false}
        onChange={(vals) => {
          const list = (vals as string[]).map((s) => s.trim()).filter(Boolean);
          if (list.length === 0) {
            onChange('');
            return;
          }
          onChange(JSON.stringify(serializePrimitives(list, allNumber)));
        }}
      />
    );
  }

  const rows = parsed.items;
  const fireRows = (next: Record<string, any>[]) => {
    if (next.length === 0) {
      onChange('');
      return;
    }
    onChange(JSON.stringify(next));
  };

  return (
    <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 8 }}>
      {rows.length === 0 ? (
        <div className="empty-box">
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<span className="help-text">暂无配置项</span>}
          />
        </div>
      ) : (
        rows.map((item, idx) => (
          <div
            key={idx}
            style={{
              border: '1px solid #e2e8f0',
              borderRadius: 8,
              background: '#ffffff',
              overflow: 'hidden'
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '6px 10px',
                background: '#f8fafc',
                borderBottom: '1px solid #eef0f6',
                fontSize: 12,
                color: '#64748b'
              }}
            >
              <span>第 {idx + 1} 项</span>
              <Button
                type="text"
                danger
                size="small"
                icon={<DeleteOutlined />}
                onClick={() => fireRows(rows.filter((_, i) => i !== idx))}
              >
                删除
              </Button>
            </div>
            <div style={{ padding: 10 }}>
              <JsonObjectEditor
                value={JSON.stringify(item)}
                emptyText="空配置，点击添加参数"
                onChange={(jsonStr) => {
                  const next = rows.slice();
                  next[idx] = jsonStr ? JSON.parse(jsonStr) : {};
                  fireRows(next);
                }}
              />
            </div>
          </div>
        ))
      )}
      <Button
        type="dashed"
        size="small"
        icon={<PlusOutlined />}
        style={{ alignSelf: 'flex-start' }}
        onClick={() => fireRows([...rows, {}])}
      >
        添加一项
      </Button>
    </div>
  );
}
