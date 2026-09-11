import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Collapse, Form, Input, Modal, Select, Space, Table, Tag, message } from 'antd';
import { request } from '@/utils/request';
import { sharedReadRequest, invalidateSharedReads } from '@/utils/sharedReadRequest';
import ModelCapabilitiesEditor from './ModelCapabilitiesEditor';
import ModelBusinessBindingEditor, { type BusinessModelBinding } from './ModelBusinessBindingEditor';
import { definitionErrors, type ModelCapabilityDefinition } from './modelDefinition';
import type { Model } from './types';
import StructuredValueEditor from './StructuredValueEditor';
import JsonObjectEditor from './JsonObjectEditor';

interface Group { identityNote?: string; providerId: number; upstreamIdentity: string; models: Model[]; conflicts: string[]; capabilities: ModelCapabilityDefinition[]; businessBindings: BusinessModelBinding[]; definitionSources?: Record<number, ModelCapabilityDefinition[]>; businessBindingSources?: Record<number, BusinessModelBinding[]>; capabilityConflicts?: string[]; businessDefaultsConflicts?: string[] }
interface Decision { selected: boolean; modelIds: number[]; expectedVersions: Record<number, number>; canonicalModelId?: number; operationalModelId?: number; modelName: string; identityVerified: boolean; capabilities: ModelCapabilityDefinition[]; businessBindings: BusinessModelBinding[]; capabilitySources?: Record<string, number>; businessDefaultSources?: Record<string, number> }
const fields: Record<string, string> = { status: '启停状态', billingMultiplier: '倍率', priority: '优先级', scheduleStrategyJson: '调度策略', isFree: '免费状态' };
const load = () => sharedReadRequest('/aid/aidmodel/migration-preview', undefined, true);

export default function ModelMigrationModal({ open, onClose, onComplete }: { open: boolean; onClose: () => void; onComplete: () => void }) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const flight = useRef(false);
  const submission = useRef<{ body: string; key: string }>();
  useEffect(() => {
    let active = true;
    if (!open) return;
    setBusy(true); setError('');
    load().then((result) => {
      if (!active) return;
      const incoming: Group[] = result.data || [];
      setGroups(incoming);
      setDecisions(incoming.map((group) => ({ selected: false, canonicalModelId: group.models.length === 1 ? group.models[0].id : undefined, operationalModelId: group.models.length === 1 ? group.models[0].id : undefined, modelIds: group.models.map((m) => m.id!), expectedVersions: Object.fromEntries(group.models.map((m) => [m.id!, m.configVersion || 0])), modelName: group.models.length === 1 ? group.models[0].modelName : '', identityVerified: false, capabilities: group.capabilities, businessBindings: group.businessBindings })));
    }).catch(() => { if (active) setError('预检读取失败，请重新打开后重试'); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [open]);
  const update = (index: number, patch: Partial<Decision>) => setDecisions((all) => all.map((item, i) => i === index ? { ...item, ...patch } : item));
  const apply = async () => {
    if (flight.current) return;
    const selected = decisions.filter((d) => d.selected);
    if (!selected.length) { setError('请选择需要迁移的模型'); return; }
    for (const decision of selected) {
      if (!decision.identityVerified || !decision.canonicalModelId || !decision.operationalModelId || !decision.modelName.trim()) { setError('请核验所选模型身份，并选择主记录、统一配置来源和模型名称'); return; }
      const issues = definitionErrors(decision.capabilities);
      if (issues.length) { setError(issues[0]); return; }
      const group = groups.find((item) => item.models.some((model) => model.id === decision.canonicalModelId));
      if (group?.capabilityConflicts?.some((code) => !decision.capabilitySources?.[code]) || group?.businessDefaultsConflicts?.some((key) => !decision.businessDefaultSources?.[key])) {
        setError('请逐项选择冲突参数的来源，不能自动覆盖已有配置'); return;
      }
    }
    flight.current = true; setBusy(true); setError('');
    const body = JSON.stringify(selected);
    if (submission.current?.body !== body) submission.current = { body, key: crypto.randomUUID() };
    try { await request({ url: '/aid/aidmodel/migrate', method: 'post', data: { decisions: selected, requestKey: submission.current.key } }); invalidateSharedReads('/aid/'); message.success('模型迁移完成'); onComplete(); onClose(); }
    catch (error: any) { setError(error?.message || '迁移未完成，可核对后重试同一份配置'); }
    finally { flight.current = false; setBusy(false); }
  };
  return <Modal open={open} title="模型统一迁移预检" width={1120} onCancel={() => { if (!flight.current) onClose(); }} onOk={apply} okText="应用已核验的迁移" confirmLoading={busy} destroyOnClose maskClosable={false}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="相同上游标识仅作为候选分组，须核对实际模型及版本。不同供应商始终独立。" description="选择统一配置来源后，该记录的启停、倍率、优先级、调度与免费状态用于合并后的模型。协议、能力和计费规则分别保留；默认能力及默认协议需要明确选择。" />
      {error && <Alert type="error" message={error} />}
      <Collapse items={groups.map((group, index) => ({ key: String(index), label: <Space><Checkbox checked={decisions[index]?.selected} onClick={(e) => e.stopPropagation()} onChange={(e) => update(index, { selected: e.target.checked })} /><span>{group.upstreamIdentity || group.models[0].modelCode}</span><Tag>供应商 {group.providerId}</Tag><Tag>{group.models.length} 条记录</Tag>{group.conflicts.length > 0 && <Tag color="warning">配置有差异</Tag>}</Space>, children: <>
        {group.identityNote && <Alert type="info" message={group.identityNote} />}
        <Table size="small" rowKey="id" pagination={false} dataSource={group.models} columns={[{ title: 'ID', dataIndex: 'id' }, { title: '现有名称', dataIndex: 'modelName' }, { title: '调用代码', dataIndex: 'modelCode' }, { title: '状态', dataIndex: 'status', render: (s) => s === '0' ? '启用' : '停用' }, { title: '倍率', dataIndex: 'billingMultiplier' }, { title: '优先级', dataIndex: 'priority' }]} />
        {group.conflicts.length > 0 && <Alert type="warning" message={`需要统一：${group.conflicts.map((field) => fields[field] || field).join('、')}`} />}
        <Collapse items={[{ key: 'source-operations', label: '查看各记录的调度与免费配置', children: group.models.map((model) => <Form.Item key={model.id} label={`${model.id} · ${model.modelName}（${model.isFree ? '免费' : '正常收费'}）`}><JsonObjectEditor disabled value={model.scheduleStrategyJson} onChange={() => {}} /></Form.Item>) }]} />
        {(group.capabilityConflicts || []).map((code) => <Form.Item key={code} label={`${code}：参数与条件规则来源`} required>
          <Select value={decisions[index]?.capabilitySources?.[code]} options={group.models.filter((model) => group.definitionSources?.[model.id!]?.some((cap) => cap.code === code)).map((model) => ({ value: model.id, label: `${model.id} · ${model.modelName}` }))} onChange={(id) => {
            const source = group.definitionSources?.[id]?.find((cap) => cap.code === code);
            if (source) update(index, { capabilitySources: { ...decisions[index]?.capabilitySources, [code]: id }, capabilities: decisions[index].capabilities.map((cap) => cap.code === code ? { ...cap, parameters: structuredClone(source.parameters), rules: structuredClone(source.rules) } : cap) });
          }} />
          <Collapse items={group.models.filter((model) => group.definitionSources?.[model.id!]?.some((cap) => cap.code === code)).map((model) => { const source = group.definitionSources![model.id!]!.find((cap) => cap.code === code)!; return { key: String(model.id), label: `查看 ${model.modelName} 的原参数`, children: <StructuredValueEditor disabled value={JSON.parse(JSON.stringify({ parameters: source.parameters, rules: source.rules }))} onChange={() => {}} /> }; })} />
        </Form.Item>)}
        {(group.businessDefaultsConflicts || []).map((key) => <Form.Item key={key} label={`${key}：业务默认参数来源`} required>
          <Select value={decisions[index]?.businessDefaultSources?.[key]} options={group.models.filter((model) => group.businessBindingSources?.[model.id!]?.some((row) => `${row.funcCode}/${row.capabilityCode}` === key)).map((model) => ({ value: model.id, label: `${model.id} · ${model.modelName}` }))} onChange={(id) => {
            const source = group.businessBindingSources?.[id]?.find((row) => `${row.funcCode}/${row.capabilityCode}` === key);
            if (source) update(index, { businessDefaultSources: { ...decisions[index]?.businessDefaultSources, [key]: id }, businessBindings: decisions[index].businessBindings.map((row) => `${row.funcCode}/${row.capabilityCode}` === key ? { ...row, defaultsJson: source.defaultsJson } : row) });
          }} />
          <Collapse items={group.models.filter((model) => group.businessBindingSources?.[model.id!]?.some((row) => `${row.funcCode}/${row.capabilityCode}` === key)).map((model) => { const source = group.businessBindingSources![model.id!]!.find((row) => `${row.funcCode}/${row.capabilityCode}` === key)!; return { key: String(model.id), label: `查看 ${model.modelName} 的原默认值`, children: <JsonObjectEditor disabled value={source.defaultsJson} onChange={() => {}} /> }; })} />
        </Form.Item>)}
        <Form.Item label="保留的模型主记录"><Select value={decisions[index]?.canonicalModelId} options={group.models.map((m) => ({ value: m.id, label: `${m.id} · ${m.modelName}` }))} onChange={(canonicalModelId) => update(index, { canonicalModelId })} /></Form.Item>
        <Form.Item label="统一运营配置来源"><Select value={decisions[index]?.operationalModelId} options={group.models.map((m) => ({ value: m.id, label: `${m.id} · ${m.modelName}` }))} onChange={(operationalModelId) => update(index, { operationalModelId })} /></Form.Item>
        <Form.Item label="合并后的模型名称"><Input value={decisions[index]?.modelName} onChange={(e) => update(index, { modelName: e.target.value })} placeholder="填写模型名称，不附加业务能力名称" /></Form.Item>
        <Checkbox checked={decisions[index]?.identityVerified} onChange={(e) => update(index, { identityVerified: e.target.checked })}>已核对官方资料，这些记录属于同一真实模型和版本</Checkbox>
        <ModelCapabilitiesEditor value={decisions[index]?.capabilities || []} modelType={group.models[0].modelType} upstreamModel={group.upstreamIdentity} onChange={(capabilities) => update(index, { capabilities })} />
        <Collapse items={[{ key: 'business', label: '确认业务能力与默认参数', children: <ModelBusinessBindingEditor value={decisions[index]?.businessBindings || []} capabilities={decisions[index]?.capabilities || []} modelType={group.models[0].modelType} onChange={(businessBindings) => update(index, { businessBindings })} /> }]} />
      </> }))} />
    </Space>
  </Modal>;
}
