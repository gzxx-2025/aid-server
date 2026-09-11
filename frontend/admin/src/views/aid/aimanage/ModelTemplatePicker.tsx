import React, { useState } from 'react';
import { Alert, Button, Modal, Select, Space, Table } from 'antd';
import { sharedReadRequest } from '@/utils/sharedReadRequest';
import type { Model } from './types';
import { parameterPaths, type ModelCapabilityDefinition, type ModelParameter } from './modelDefinition';

function addMissingFields(current: ModelParameter[], incoming: ModelParameter[]): ModelParameter[] {
  return [...current.map((field) => {
    const source = incoming.find((item) => item.name === field.name && item.type === field.type);
    return source && field.type === 'object' ? { ...field, properties: addMissingFields(field.properties || [], source.properties || []) } : field;
  }), ...incoming.filter((field) => !current.some((item) => item.name === field.name)).map((field) => structuredClone(field))];
}

/** 复用已配置模型的结构，价格和业务绑定由新模型单独确认。 */
export default function ModelTemplatePicker({ modelType, upstreamModel, current, onChange }: { modelType: string; upstreamModel?: string; current: ModelCapabilityDefinition[]; onChange: (value: ModelCapabilityDefinition[]) => void }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [models, setModels] = useState<Model[]>([]);
  const [selected, setSelected] = useState<number>();
  const [error, setError] = useState('');
  const template = models.find((model) => model.id === selected);
  const incoming = template?.capabilities || [];
  const additions = incoming.filter((capability) => !current.some((existing) => existing.code === capability.code));
  const differences = incoming.map((capability) => {
    const existing = current.find((item) => item.code === capability.code);
    return { capability, existing, fields: parameterPaths(capability.parameters || []).filter((path) => !parameterPaths(existing?.parameters || []).includes(path)),
      routes: capability.bindings.filter((route) => !existing?.bindings.some((item) => item.protocol === route.protocol)) };
  });
  const canApply = differences.some((difference) => !difference.existing || difference.fields.length || difference.routes.length);
  const newRoute = (route: ModelCapabilityDefinition['bindings'][number]) => ({ ...structuredClone(route), upstreamModel, enabled: false, billingMode: 'FIXED', costCredits: undefined, billingRule: undefined,
    fixedParameters: Object.fromEntries(Object.entries(route.fixedParameters || {}).filter(([key]) => !['model', 'model_name', 'api_key', 'apiKey', 'Authorization', 'authorization'].includes(key))) });
  return <>
    <Button disabled={!modelType} style={{ marginBottom: 16 }} onClick={async () => {
      setOpen(true); setLoading(true); setError(''); setSelected(undefined);
      try { const result = await sharedReadRequest('/aid/aidmodel/list', { modelType, pageNum: 1, pageSize: 1000 }); setModels((result.rows || []).filter((model: Model) => model.capabilities?.length)); }
      catch { setError('模型模板读取失败，请重试'); }
      finally { setLoading(false); }
    }}>选择已有模型模板</Button>
    <Modal open={open} title="预览模型模板" confirmLoading={loading} okButtonProps={{ disabled: !template || !canApply }} onCancel={() => setOpen(false)} onOk={() => {
      const next = additions.map((capability) => ({ ...structuredClone(capability), defaultCapability: current.length === 0 && capability.defaultCapability,
        bindings: capability.bindings.map(newRoute) }));
      const updated = current.map((capability) => {
        const difference = differences.find((item) => item.capability.code === capability.code);
        if (!difference) return capability;
        const codes = new Set(capability.bindings.map((route) => route.code));
        const routes = difference.routes.map((route) => {
          let code = route.code, suffix = 2;
          while (codes.has(code)) code = `${route.code.slice(0, 85)}_${suffix++}`;
          codes.add(code);
          return { ...newRoute(route), code, defaultBinding: false, enabled: false };
        });
        return { ...capability, parameters: addMissingFields(capability.parameters || [], difference.capability.parameters || []), bindings: [...capability.bindings, ...routes] };
      });
      onChange([...updated, ...next]); setOpen(false);
    }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert type="info" message="补充缺失字段与协议，保留已有参数、规则、价格和业务绑定。新增协议默认停用；模型标识、固定路由参数与价格需逐项核对。" />
        {error && <Alert type="error" message={error} />}
        <Select style={{ width: '100%' }} loading={loading} showSearch optionFilterProp="label" value={selected} placeholder="选择模板模型" options={models.map((model) => ({ value: model.id, label: model.modelName }))} onChange={setSelected} />
        <Table rowKey="code" size="small" pagination={false} dataSource={incoming} columns={[
          { title: '能力', dataIndex: 'label' },
          { title: '参数', render: (_, capability) => parameterPaths(capability.parameters || []).join('、') || '沿用协议参数' },
          { title: '协议', render: (_, capability) => capability.bindings.map((route) => route.protocol).join('、') },
          { title: '变更预览', render: (_, capability) => { const difference = differences.find((item) => item.capability.code === capability.code)!; return difference.existing ? `补充 ${difference.fields.length} 个字段、${difference.routes.length} 个协议；保留已有配置` : '新增能力'; } }
        ]} />
      </Space>
    </Modal>
  </>;
}
