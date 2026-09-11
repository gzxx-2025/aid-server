import React from 'react';
import { Card, Form, Select, Space } from 'antd';
import { BusinessDefaults } from '../aimanage/ModelBusinessBindingEditor';
import type { BusinessModelBinding } from '../aimanage/ModelBusinessBindingEditor';
import type { ModelCapabilityDefinition } from '../aimanage/modelDefinition';
import type { PoolModel } from './ModelPoolSelector';

export default function FunctionCapabilityEditor({ models, value, onChange }: { models: PoolModel[]; value: BusinessModelBinding[]; onChange: (next: BusinessModelBinding[]) => void }) {
  return <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>{models.map((model) => {
    const capabilities: ModelCapabilityDefinition[] = model.capabilities || [];
    if (!capabilities.length) return null;
    const selected = value.filter((row) => row.modelId === model.id);
    const replace = (rows: BusinessModelBinding[]) => onChange([...value.filter((row) => row.modelId !== model.id), ...rows]);
    return <Card key={model.id} size="small" title={model.modelName}>
      <Form.Item label="业务允许的能力" required><Select mode="multiple" value={selected.map((row) => row.capabilityCode)} options={capabilities.map((capability) => ({ value: capability.code, label: capability.label, disabled: !capability.enabled }))} onChange={(codes: string[]) => replace(codes.map((code) => selected.find((row) => row.capabilityCode === code) || { modelId: model.id, funcCode: '', capabilityCode: code, defaultCapability: codes.length === 1 }))} /></Form.Item>
      <Form.Item label="默认能力" required><Select value={selected.find((row) => row.defaultCapability)?.capabilityCode} options={selected.map((row) => ({ value: row.capabilityCode, label: capabilities.find((capability) => capability.code === row.capabilityCode)?.label || row.capabilityCode }))} onChange={(code) => replace(selected.map((row) => ({ ...row, defaultCapability: row.capabilityCode === code })))} /></Form.Item>
      {selected.map((row) => {
        const capability = capabilities.find((item) => item.code === row.capabilityCode);
        if (!capability?.parameters.length) return null;
        return <Card key={row.capabilityCode} size="small" title={capability.label}><BusinessDefaults definition={capability} value={row.defaultsJson} onChange={(defaultsJson) => replace(selected.map((item) => item === row ? { ...item, defaultsJson } : item))} /></Card>;
      })}
    </Card>;
  })}</Space>;
}
