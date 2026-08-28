import React from 'react';
import { Avatar, Badge, Button, Input, Select, Space, Switch, Table, Tag, Tooltip, message } from 'antd';
import { DeleteOutlined, EditOutlined, GlobalOutlined, PlusOutlined, SyncOutlined, ClearOutlined, ExperimentOutlined } from '@ant-design/icons';
import type { Model, Provider } from './types';
import { MODEL_TYPE_OPTIONS, GENERATE_MODE_OPTIONS, INPUT_REQUIREMENT_OPTIONS, BILLING_MODE_OPTIONS, METER_TYPE_OPTIONS, IMAGE_REFINE_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';
import { buildCapSummary, parseMaxConcurrency, resolveMeterType } from './helpers';
import { runConfigTest, type ConfigTestResult } from '@/api/system/configTest';
import TestResultModal from '@/components/TestResultModal';

interface Props {
  provider: Provider | null;
  list: Model[];
  loading: boolean;
  query: { modelType: string | null; generateMode: string | null; inputRequirement: string | null; keyword: string };
  onQueryChange: (q: any) => void;
  onAdd: () => void;
  onEdit: (row: Model) => void;
  onDelete: (row: Model) => void;
  /** 行内开关：直接启用或停用模型，无需进入编辑弹窗 */
  onToggleStatus: (row: Model, enabled: boolean) => Promise<void>;
  onSyncVoice?: () => void;
  onCleanExpired?: () => void;
  /** 打开真实模型总览抽屉 */
  onOpenOverview?: () => void;
}

export default function ModelTable({ provider, list, loading, query, onQueryChange, onAdd, onEdit, onDelete, onToggleStatus, onSyncVoice, onCleanExpired, onOpenOverview }: Props) {
  const [testingId, setTestingId] = React.useState<number | null>(null);
  const [togglingId, setTogglingId] = React.useState<number | null>(null);
  const [testOpen, setTestOpen] = React.useState(false);
  const [testResult, setTestResult] = React.useState<ConfigTestResult | null>(null);

  const handleTestModel = async (row: Model) => {
    if (testingId != null) return;
    setTestingId(row.id ?? null);
    try {
      const res = await runConfigTest('ai-model', { modelId: row.id });
      setTestResult(res.data);
      setTestOpen(true);
    } catch (e: any) {
      message.error(e?.message || '测试请求失败');
    } finally {
      setTestingId(null);
    }
  };

  const handleToggleStatus = async (row: Model, enabled: boolean) => {
    if (togglingId != null || row.id == null) return;
    setTogglingId(row.id);
    try {
      await onToggleStatus(row, enabled);
    } catch {
      // 请求失败由统一拦截器提示，列表保留原状态
    } finally {
      setTogglingId(null);
    }
  };

  if (!provider) {
    return <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94a3b8' }}>请选择左侧的服务商查看模型配置</div>;
  }

  const columns: any[] = [
    { title: '模型代码', dataIndex: 'modelCode', width: 160, ellipsis: true },
    { title: '真实模型名', dataIndex: 'realModelCode', width: 150, ellipsis: true, render: (v: string) => v || '--' },
    { title: '模型名称', dataIndex: 'modelName', width: 140, ellipsis: true },
    {
      title: 'LOGO', dataIndex: 'logoUrl', width: 65,
      render: (v: string) => (v || provider.logoUrl)
        ? <Avatar shape="square" size={32} src={v || provider.logoUrl} />
        : <span style={{ color: '#cbd5e1' }}>--</span>
    },
    { title: '分类', dataIndex: 'modelType', width: 80, render: (v: string) => <Tag color={getAntdTagColor(MODEL_TYPE_OPTIONS, v)}>{getLabelByValue(MODEL_TYPE_OPTIONS, v)}</Tag> },
    { title: '生成模式', dataIndex: 'generateMode', width: 100, render: (v: string) => v ? <Tag color={getAntdTagColor(GENERATE_MODE_OPTIONS, v)}>{getLabelByValue(GENERATE_MODE_OPTIONS, v)}</Tag> : '--' },
    { title: '输入要求', dataIndex: 'inputRequirement', width: 95, render: (v: string) => v ? <Tag color={getAntdTagColor(INPUT_REQUIREMENT_OPTIONS, v)}>{getLabelByValue(INPUT_REQUIREMENT_OPTIONS, v)}</Tag> : '--' },
    { title: '图片类型', dataIndex: 'imageRefine', width: 90, render: (v: any, r: Model) => r.modelType === 'image' && v ? <Tag color={getAntdTagColor(IMAGE_REFINE_OPTIONS, v)}>{getLabelByValue(IMAGE_REFINE_OPTIONS, v)}</Tag> : '--' },
    { title: '能力', dataIndex: 'id', key: 'cap', width: 140, render: (_: any, r: Model) => buildCapSummary(r) },
    { title: '原价（元）', dataIndex: 'costCredits', width: 90 },
    { title: '单模型倍率', dataIndex: 'billingMultiplier', width: 100, render: (v: any) => v == null ? '1.00' : Number(v).toFixed(2) },
    { title: '收费状态', dataIndex: 'isFree', width: 90, render: (v: boolean) => <Tag color={v === true ? 'green' : 'default'}>{v === true ? '免费' : '正常计费'}</Tag> },
    { title: '计费模式', dataIndex: 'billingMode', width: 90, render: (v: string) => v ? <Tag color={getAntdTagColor(BILLING_MODE_OPTIONS, v)}>{getLabelByValue(BILLING_MODE_OPTIONS, v)}</Tag> : '--' },
    { title: '计费口径', key: 'meter', width: 100, render: (_: any, r: Model) => { const mt = resolveMeterType(r); return <Tag color={getAntdTagColor(METER_TYPE_OPTIONS, mt)}>{getLabelByValue(METER_TYPE_OPTIONS, mt)}</Tag>; } },
    { title: '模型接口路径', dataIndex: 'apiSuffix', width: 130, ellipsis: true },
    { title: '并发', key: 'maxConcurrency', width: 70, render: (_: any, r: Model) => { const n = parseMaxConcurrency(r.scheduleStrategyJson); return n ? <Tag color="purple">{n}</Tag> : <span style={{ color: '#cbd5e1' }}>不限</span>; } },
    { title: '优先级', dataIndex: 'priority', width: 70 },
    { title: 'API版本', dataIndex: 'apiVersion', width: 90, ellipsis: true },
    {
      title: '操作',
      key: 'ops',
      width: 250,
      fixed: 'right',
      render: (_: any, r: Model) => {
        const enabled = r.status === '0';
        return (
          <Space size={0}>
            <Tooltip title={enabled ? '点击停用' : '点击启用'}>
              <Switch
                size="small"
                checked={enabled}
                checkedChildren="启用"
                unCheckedChildren="停用"
                loading={togglingId === r.id}
                onChange={(checked) => handleToggleStatus(r, checked)}
              />
            </Tooltip>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => onEdit(r)}>修改</Button>
            <Button type="link" size="small" icon={<ExperimentOutlined />} loading={testingId === r.id} onClick={() => handleTestModel(r)}>测试</Button>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(r)}>删除</Button>
          </Space>
        );
      }
    }
  ];

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', background: '#fff', padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <h3 style={{ margin: 0 }}>{provider.providerName}</h3>
          <Tag>{provider.providerCode}</Tag>
          {provider.status === '1' && <Tag color="red">已停用</Tag>}
          <span style={{ color: '#94a3b8', fontSize: 12 }}>{provider.baseUrl}</span>
        </div>
        <Space size={8} wrap>
          <Select size="small" style={{ width: 120 }} placeholder="模型分类" allowClear value={query.modelType} onChange={(v) => onQueryChange({ ...query, modelType: v })} options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} />
          <Select size="small" style={{ width: 140 }} placeholder="生成模式" allowClear value={query.generateMode} onChange={(v) => onQueryChange({ ...query, generateMode: v })} options={GENERATE_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} />
          <Select size="small" style={{ width: 120 }} placeholder="输入要求" allowClear value={query.inputRequirement} onChange={(v) => onQueryChange({ ...query, inputRequirement: v })} options={INPUT_REQUIREMENT_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} />
          <Input size="small" style={{ width: 160 }} placeholder="搜索模型..." allowClear value={query.keyword} onChange={(e) => onQueryChange({ ...query, keyword: e.target.value })} />
          {onOpenOverview && (
            <Button size="small" icon={<GlobalOutlined />} onClick={onOpenOverview}>真实模型总览</Button>
          )}
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={onAdd}>新增模型</Button>
          {provider.providerCode?.trim().toLowerCase() === 'minimax' && onCleanExpired && (
            <Button size="small" icon={<ClearOutlined />} danger onClick={onCleanExpired}>
              清除过期
            </Button>
          )}
          {provider.providerCode?.trim().toLowerCase() === 'minimax' && onSyncVoice && (
            <Badge count="推荐" size="small">
              <Button size="small" icon={<SyncOutlined />} onClick={onSyncVoice}>
                同步音色
              </Button>
            </Badge>
          )}
        </Space>
      </div>
      <Table rowKey="id" size="small" loading={loading} dataSource={list} columns={columns} scroll={{ x: 2025 }} pagination={false} />
      <TestResultModal open={testOpen} result={testResult} onClose={() => setTestOpen(false)} />
    </div>
  );
}
