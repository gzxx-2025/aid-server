import React from 'react';
import { Button, Collapse, Input, Select, Space } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { COMMON_PROVIDERS, commonParamNamesByType } from './constants';
import { buildParamMappingJsonObject } from './helpers';
import type { ParamMapping } from './types';

interface Props {
  modelType?: string;
  rows: ParamMapping[];
  onChange: (rows: ParamMapping[]) => void;
}

export default function ParamMappingEditor({ modelType, rows, onChange }: Props) {
  const paramNames = commonParamNamesByType(modelType);
  const addRow = () => onChange([...rows, { paramName: '', provider: '', providerParamName: '' }]);
  const removeRow = (idx: number) => onChange(rows.filter((_, i) => i !== idx));
  const update = (idx: number, patch: Partial<ParamMapping>) =>
    onChange(rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)));

  return (
    <div>
      <Space style={{ marginBottom: 10 }}>
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={addRow}>添加映射</Button>
        <span className="help-text">统一参数 → 厂商参数，仅当厂商字段名与系统标准参数不一致时配置</span>
      </Space>
      {rows.length === 0 ? (
        <div className="empty-box">暂无映射，点击「添加映射」创建</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {rows.map((row, idx) => (
            <div
              key={idx}
              style={{
                display: 'flex',
                gap: 8,
                alignItems: 'center',
                background: '#f8fafc',
                border: '1px solid rgba(15, 23, 42, 0.06)',
                borderRadius: 8,
                padding: '8px 10px'
              }}
            >
              <Select size="small" style={{ width: 180 }} placeholder="参数名" value={row.paramName || undefined} onChange={(v) => update(idx, { paramName: v })} options={paramNames.map((v) => ({ label: v, value: v }))} showSearch allowClear />
              <span style={{ color: '#94a3b8' }}>→</span>
              <Select size="small" style={{ width: 140 }} placeholder="厂商" value={row.provider || undefined} onChange={(v) => update(idx, { provider: v })} options={COMMON_PROVIDERS.map((v) => ({ label: v, value: v }))} showSearch allowClear />
              <Input size="small" style={{ flex: 1 }} placeholder="厂商参数名" value={row.providerParamName} onChange={(e) => update(idx, { providerParamName: e.target.value })} />
              <Button size="small" danger type="text" icon={<DeleteOutlined />} onClick={() => removeRow(idx)} />
            </div>
          ))}
        </div>
      )}
      {rows.length > 0 && (
        <Collapse
          ghost
          style={{ marginTop: 8 }}
          items={[{
            key: 'preview',
            label: <span style={{ fontSize: 12, color: '#94a3b8' }}>查看生成的 paramMappingJson（只读预览）</span>,
            children: (
              <pre className="readonly-preview">
                {JSON.stringify(buildParamMappingJsonObject(rows), null, 2)}
              </pre>
            )
          }]}
        />
      )}
    </div>
  );
}
