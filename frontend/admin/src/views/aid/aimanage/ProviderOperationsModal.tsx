import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, DatePicker, Descriptions, Input, Modal, Select, Space, Table, Tabs, Tag, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { getProviderBalance, listProviderUpstreamTasks } from '@/api/aid/aimanage';
import type { Provider, ProviderOperationCapabilities } from './types';
import {
  buildProviderTaskSearchOptions,
  buildProviderTaskPayload,
  ProviderOperationRequestGate,
  resolveProviderTaskSearchType,
  type ProviderOperationRequestScope,
  type ProviderTaskQuerySnapshot
} from './providerOperations';

interface Props {
  open: boolean;
  provider: Provider | null;
  capabilities: ProviderOperationCapabilities;
  initialTab: 'balance' | 'tasks';
  onClose: () => void;
}

const statusColor: Record<string, string> = {
  submitted: 'default', processing: 'processing', succeeded: 'success', failed: 'error'
};

export default function ProviderOperationsModal({ open, provider, capabilities, initialTab, onClose }: Props) {
  const [tab, setTab] = useState<'balance' | 'tasks'>(initialTab);
  const [loading, setLoading] = useState(false);
  const [balance, setBalance] = useState<any>(null);
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(30, 'day'), dayjs()]);
  const [packName, setPackName] = useState('');
  const [tasks, setTasks] = useState<any[]>([]);
  const [statusScope, setStatusScope] = useState<'running' | 'all'>('running');
  const [productType, setProductType] = useState('video');
  const [searchType, setSearchType] = useState('task_ids');
  const [searchValue, setSearchValue] = useState('');
  const [nextCursor, setNextCursor] = useState('');
  const [hasMore, setHasMore] = useState(false);
  const [cursorStack, setCursorStack] = useState<string[]>([]);
  const taskSearchOptions = useMemo(
    () => buildProviderTaskSearchOptions(capabilities.taskSearchTypes),
    [capabilities.taskSearchTypes]
  );
  const requestGateRef = useRef(new ProviderOperationRequestGate());
  const taskQueryRef = useRef<ProviderTaskQuerySnapshot | null>(null);
  const providerId = provider?.id ?? null;
  const requestScopeRef = useRef<ProviderOperationRequestScope>({ open, providerId });
  if (requestScopeRef.current.open !== open || requestScopeRef.current.providerId !== providerId) {
    requestScopeRef.current = { open, providerId };
    requestGateRef.current.invalidate();
  }

  useEffect(() => () => requestGateRef.current.invalidate(), []);

  useEffect(() => {
    setSearchType((current) => resolveProviderTaskSearchType(current, taskSearchOptions));
  }, [providerId, taskSearchOptions]);

  useEffect(() => {
    if (!open) return;
    setTab(initialTab);
    setBalance(null);
    setTasks([]);
    setCursorStack([]);
    setNextCursor('');
    setHasMore(false);
    taskQueryRef.current = null;
    if (initialTab === 'balance') void loadBalance();
    else void loadTasks('', true);
    // 弹窗每次打开以最新供应商为准。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, provider?.id, initialTab]);

  const loadBalance = async () => {
    if (!provider) return;
    const token = requestGateRef.current.begin(provider.id);
    setLoading(true);
    try {
      const res: any = await getProviderBalance(provider.id, {
        startTime: range[0].valueOf(), endTime: range[1].valueOf(),
        resourcePackName: packName || undefined
      });
      if (!requestGateRef.current.isCurrent(token, requestScopeRef.current)) return;
      setBalance(res.data || {});
    } catch (e: any) {
      if (requestGateRef.current.isCurrent(token, requestScopeRef.current)) {
        message.error(e?.message || '余额查询失败');
      }
    } finally {
      if (requestGateRef.current.isCurrent(token, requestScopeRef.current)) setLoading(false);
    }
  };

  const loadTasks = async (cursor: string, resetQuery = false) => {
    if (!provider) return;
    if (resetQuery || !taskQueryRef.current) {
      const supportedSearchType = resolveProviderTaskSearchType(searchType, taskSearchOptions);
      const exactSearch = supportedSearchType ? searchValue.trim() : '';
      taskQueryRef.current = {
        startTime: dayjs().subtract(30, 'day').valueOf(),
        endTime: Date.now(),
        limit: 100,
        status: statusScope === 'running' ? 'submitted,processing' : undefined,
        productType: productType || undefined,
        searchType: supportedSearchType || undefined,
        searchValue: exactSearch || undefined
      };
    }
    const token = requestGateRef.current.begin(provider.id);
    setLoading(true);
    try {
      const exactSearch = taskQueryRef.current.searchValue || '';
      const payload = buildProviderTaskPayload({
        cursor,
        exactSearch,
        searchType: taskQueryRef.current.searchType || '',
        snapshot: taskQueryRef.current
      });
      const res: any = await listProviderUpstreamTasks(provider.id, payload);
      if (!requestGateRef.current.isCurrent(token, requestScopeRef.current)) return;
      const data = res.data || {};
      setTasks(Array.isArray(data.result) ? data.result : []);
      const pageHasMore = Boolean(data.hasMore);
      setHasMore(pageHasMore);
      setNextCursor(pageHasMore && data.nextCursor ? data.nextCursor : '');
    } catch (e: any) {
      if (requestGateRef.current.isCurrent(token, requestScopeRef.current)) {
        message.error(e?.message || '上游任务查询失败');
      }
    } finally {
      if (requestGateRef.current.isCurrent(token, requestScopeRef.current)) setLoading(false);
    }
  };

  const balanceRows = useMemo(() => {
    const rows = balance?.resource_pack_subscribe_infos;
    return Array.isArray(rows) ? rows : [];
  }, [balance]);

  const taskColumns = [
    { title: '任务 ID', dataIndex: 'id', width: 210, ellipsis: true },
    { title: '外部 ID', dataIndex: 'external_id', width: 160, ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <Tag color={statusColor[v] || 'default'}>{v || '-'}</Tag> },
    { title: '创建时间', dataIndex: 'create_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '更新时间', dataIndex: 'update_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '消息', dataIndex: 'message', ellipsis: true }
  ];

  const tabs: any[] = [];
  if (capabilities.balance) tabs.push({
    key: 'balance', label: '资源包余量', children: (
      <>
        <Alert type="info" showIcon message={capabilities.balanceDelayNotice || balance?.delayNotice || '上游余额可能存在统计延迟'} style={{ marginBottom: 12 }} />
        <Space wrap style={{ marginBottom: 12 }}>
          <DatePicker.RangePicker value={range} onChange={(v) => v?.[0] && v?.[1] && setRange([v[0], v[1]])} />
          <Input allowClear placeholder="资源包名称（精确）" value={packName} onChange={(e) => setPackName(e.target.value)} style={{ width: 220 }} />
          <Button type="primary" loading={loading} onClick={loadBalance}>查询</Button>
        </Space>
        <Table rowKey={(r) => r.resource_pack_id} loading={loading} dataSource={balanceRows} pagination={false} size="small" scroll={{ x: 900 }} columns={[
          { title: '资源包', dataIndex: 'resource_pack_name', width: 220 },
          { title: '类型', dataIndex: 'resource_pack_type', width: 150 },
          { title: '总量', dataIndex: 'total_quantity', width: 100 },
          { title: '余量', dataIndex: 'remaining_quantity', width: 100 },
          { title: '状态', dataIndex: 'status', width: 100 },
          { title: '生效时间', dataIndex: 'effective_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
          { title: '失效时间', dataIndex: 'invalid_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' }
        ]} />
      </>
    )
  });
  if (capabilities.upstreamTasks) tabs.push({
    key: 'tasks', label: '上游任务', children: (
      <>
        <Space wrap style={{ marginBottom: 12 }}>
          <Select value={statusScope} onChange={setStatusScope} options={[{ value: 'running', label: '运行中' }, { value: 'all', label: '全部' }]} style={{ width: 110 }} />
          <Select allowClear value={productType || undefined} onChange={(v) => setProductType(v || '')} placeholder="功能类型" style={{ width: 120 }} options={(capabilities.productTypes || []).map((v) => ({ value: v, label: v }))} />
          <Select value={searchType || undefined} onChange={setSearchType} disabled={!taskSearchOptions.length}
            style={{ width: 155 }} options={taskSearchOptions} />
          <Input allowClear value={searchValue} onChange={(e) => setSearchValue(e.target.value)}
            disabled={!taskSearchOptions.length} placeholder="精确 ID，多个用逗号分隔" style={{ width: 260 }} />
          <Button type="primary" loading={loading} onClick={() => { setCursorStack([]); void loadTasks('', true); }}>查询</Button>
        </Space>
        <Table rowKey="id" loading={loading} dataSource={tasks} columns={taskColumns} pagination={false} size="small" scroll={{ x: 980 }} />
        <Space style={{ marginTop: 12 }}>
          <Button disabled={!cursorStack.length || loading} onClick={() => {
            const stack = cursorStack.slice(0, -1); setCursorStack(stack); void loadTasks(stack.at(-1) || '');
          }}>上一页</Button>
          <Button disabled={!hasMore || !nextCursor || loading} onClick={() => {
            setCursorStack((old) => [...old, nextCursor]); void loadTasks(nextCursor);
          }}>下一页</Button>
          <span style={{ color: '#64748b' }}>本页 {tasks.length} 条</span>
        </Space>
      </>
    )
  });

  return (
    <Modal open={open} title={`${provider?.providerName || ''} · 上游运营`} onCancel={onClose} footer={null} width={1100} destroyOnClose>
      <Descriptions size="small" column={2} style={{ marginBottom: 8 }} items={[
        { key: 'code', label: '服务商编码', children: provider?.providerCode || '-' },
        { key: 'base', label: '基础网关', children: provider?.baseUrl || '-' }
      ]} />
      <Tabs activeKey={tab} onChange={(key) => setTab(key as 'balance' | 'tasks')} items={tabs} />
    </Modal>
  );
}
