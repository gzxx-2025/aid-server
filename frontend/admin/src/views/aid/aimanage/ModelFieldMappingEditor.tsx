import React, { useMemo, useState } from 'react';
import { Button, Card, Form, Input, Select, Space, TreeSelect, message } from 'antd';
import type { ModelProtocolBinding } from './modelDefinition';
import type { ConfigValue } from './StructuredValueEditor';

type Mapping = NonNullable<ModelProtocolBinding['mappings']>[number];
type Node = { title: string; value: string; children: Node[] };
const types = [['preserve', '保持来源类型'], ['string', '文本'], ['number', '数字'], ['integer', '整数'], ['boolean', '开关'], ['object', '对象'], ['array', '列表']].map(([value, label]) => ({ value, label }));

export default function ModelFieldMappingEditor({ value, sources, fixedParameters, onChange }: { value: Mapping[]; sources: string[]; fixedParameters?: Record<string, ConfigValue>; onChange: (next: Mapping[]) => void }) {
  const [paths, setPaths] = useState<string[]>([]);
  const [parent, setParent] = useState<string>();
  const [name, setName] = useState('');
  const tree = useMemo(() => {
    const targets = new Set([...paths, ...value.map((mapping) => mapping.target).filter(Boolean)]);
    const collect = (object: Record<string, ConfigValue>, prefix = '') => Object.entries(object).forEach(([key, item]) => {
      const path = prefix + key; targets.add(path);
      if (item && typeof item === 'object' && !Array.isArray(item)) collect(item, path + '.');
    });
    collect(fixedParameters || {});
    const roots: Node[] = [];
    [...targets].sort().forEach((target) => {
      let nodes = roots; let path = '';
      target.split('.').forEach((part) => {
        path = path ? `${path}.${part}` : part;
        let node = nodes.find((item) => item.value === path);
        if (!node) { node = { title: part, value: path, children: [] }; nodes.push(node); }
        nodes = node.children;
      });
    });
    return roots;
  }, [paths, value, fixedParameters]);
  const update = (index: number, patch: Partial<Mapping>) => onChange(value.map((mapping, i) => i === index ? { ...mapping, ...patch } : mapping));
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Card size="small" title="上游字段树">
      <Form.Item label="所在分组"><TreeSelect allowClear treeDefaultExpandAll value={parent} treeData={tree} placeholder="留空表示请求体根级" onChange={setParent} /></Form.Item>
      <Space.Compact style={{ width: '100%' }}><Input aria-label="上游字段名称" value={name} placeholder="填写官方参数名称，例如 duration" onChange={(event) => setName(event.target.value)} /><Button onClick={() => {
        if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(name) || ['constructor', 'prototype', '__proto__'].includes(name)) { message.error('请输入合法的单个字段名称'); return; }
        const path = parent ? `${parent}.${name}` : name;
        setPaths((previous) => [...new Set([...previous, path])]); setName('');
      }}>添加字段或分组</Button></Space.Compact>
    </Card>
    {value.map((mapping, index) => <Card key={index} size="small" title={`映射 ${index + 1}`} extra={<Button onClick={() => onChange(value.filter((_, i) => i !== index))}>删除映射</Button>}>
      <Form.Item label="参数来源"><Select value={mapping.source || undefined} options={sources.map((value) => ({ value, label: value }))} onChange={(source) => update(index, { source })} /></Form.Item>
      <Form.Item label="上游目标字段"><TreeSelect treeDefaultExpandAll value={mapping.target || undefined} treeData={tree} onChange={(target) => update(index, { target })} /></Form.Item>
      <Form.Item label="目标类型"><Select value={mapping.type || 'preserve'} options={types} onChange={(type) => update(index, { type })} /></Form.Item>
    </Card>)}
    <Button onClick={() => onChange([...value, { source: '', target: '', type: 'preserve' }])}>添加参数映射</Button>
  </Space>;
}
