import React from 'react';
import { Button, Card, Form, Input, Select, Space } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import StructuredValueEditor from './StructuredValueEditor';
import type { ModelParameterRule, ModelRuleCondition } from './modelDefinition';
import { MATERIAL_STATISTICS } from './modelDefinition';

const conditions = [['present', '已填写'], ['absent', '未填写'], ['eq', '等于'], ['neq', '不等于'], ['in', '属于选项'], ['gt', '大于'], ['gte', '大于等于'], ['lt', '小于'], ['lte', '小于等于'], ['count_gte', '数量至少'], ['count_lte', '数量至多']].map(([value, label]) => ({ value, label }));
const actions = [['required', '必须填写'], ['forbidden', '禁止填写'], ['fixed', '固定为'], ['minimum', '最小值'], ['maximum', '最大值'], ['choices', '允许选项'], ['maximum_sum', '合计上限']].map(([value, label]) => ({ value, label }));
const matches = [{ value: 'all' as const, label: '全部满足' }, { value: 'any' as const, label: '任一满足' }];

function RuleItems({ value, fields, onChange, part, statistics, depth = 0 }: { value: ModelRuleCondition[]; fields: string[]; onChange: (value: ModelRuleCondition[]) => void; part: 'conditions' | 'actions'; statistics: boolean; depth?: number }) {
  const update = (index: number, patch: Partial<ModelRuleCondition>) => onChange(value.map((item, i) => i === index ? { ...item, ...patch } : item));
  return <Space direction="vertical" style={{ width: '100%' }}>
    {value.map((item, index) => <Card key={index} size="small" extra={<Button aria-label="删除规则项" icon={<DeleteOutlined />} onClick={() => onChange(value.filter((_, i) => i !== index))} />}>
      {item.match ? <>
        <Form.Item label="子条件组"><Select value={item.match} options={matches} onChange={(match) => update(index, { match })} /></Form.Item>
        <RuleItems value={item.conditions || []} fields={fields} part="conditions" statistics={statistics} depth={depth + 1} onChange={(conditions) => update(index, { conditions })} />
      </> : <>
        <Space wrap>
          <Select aria-label="规则字段" value={item.field || undefined} placeholder="选择参数或素材统计" options={[...fields.map((value) => ({ value, label: value })), ...(statistics ? MATERIAL_STATISTICS : [])]} style={{ minWidth: 180 }} onChange={(field) => update(index, { field })} />
          <Select aria-label="规则操作" value={item.operator} options={part === 'conditions' ? conditions : actions.filter((action) => statistics || action.value !== 'maximum_sum')} style={{ width: 135 }} onChange={(operator) => update(index, { operator, value: ['in', 'choices'].includes(operator) ? [] : ['present', 'absent', 'required', 'forbidden'].includes(operator) ? undefined : 0 })} />
        </Space>
        {!['present', 'absent', 'required', 'forbidden'].includes(item.operator) && <StructuredValueEditor value={item.value ?? (['in', 'choices'].includes(item.operator) ? [] : 0)} onChange={(value) => update(index, { value })} />}
        {item.operator === 'maximum_sum' && <Form.Item label="加上哪项素材统计"><Select value={item.valueField} options={MATERIAL_STATISTICS} onChange={(valueField) => update(index, { valueField })} /></Form.Item>}
      </>}
    </Card>)}
    <Space>
      <Button icon={<PlusOutlined />} onClick={() => onChange([...value, { field: fields[0] || '', operator: part === 'conditions' ? 'present' : 'required' }])}>{part === 'conditions' ? '添加条件' : '添加约束'}</Button>
      {part === 'conditions' && depth < 8 && <Button onClick={() => onChange([...value, { match: 'all', field: '', operator: '', conditions: [{ field: fields[0] || '', operator: 'present' }] }])}>添加条件组</Button>}
    </Space>
  </Space>;
}

export default function ModelRuleEditor({ value, fields, onChange, statistics = false }: { statistics?: boolean; value: ModelParameterRule[]; fields: string[]; onChange: (value: ModelParameterRule[]) => void }) {
  const update = (index: number, patch: Partial<ModelParameterRule>) => onChange(value.map((rule, i) => i === index ? { ...rule, ...patch } : rule));
  return <Space direction="vertical" style={{ width: '100%' }}>
    {value.map((rule, index) => <Card key={index} size="small" title={rule.label || '条件规则'} extra={<Button aria-label={`删除规则 ${rule.label}`} icon={<DeleteOutlined />} onClick={() => onChange(value.filter((_, i) => i !== index))} />}>
      <Form.Item label="规则名称"><Input value={rule.label} onChange={(e) => update(index, { label: e.target.value })} /></Form.Item>
      <Form.Item label="当以下条件"><Select value={rule.match} options={matches} onChange={(match) => update(index, { match })} /></Form.Item>
      <Form.Item label="条件"><RuleItems statistics={statistics} part="conditions" value={rule.conditions} fields={fields} onChange={(conditions) => update(index, { conditions })} /></Form.Item>
      <Form.Item label="需要执行的约束"><RuleItems statistics={statistics} part="actions" value={rule.actions} fields={fields} onChange={(actions) => update(index, { actions })} /></Form.Item>
    </Card>)}
    <Button icon={<PlusOutlined />} disabled={!fields.length} onClick={() => onChange([...value, { label: '新规则', match: 'all', conditions: [{ field: fields[0] || '', operator: 'present' }], actions: [{ field: fields[0] || '', operator: 'required' }] }])}>添加规则</Button>
  </Space>;
}
