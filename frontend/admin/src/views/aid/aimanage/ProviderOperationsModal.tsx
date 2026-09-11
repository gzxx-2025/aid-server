import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, DatePicker, Descriptions, Input, Modal, Select, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { getProviderBalance, listProviderUpstreamTasks } from '@/api/aid/aimanage';
import type { Provider, ProviderOperationCapabilities } from './types';
import ProviderBalanceSummary, { balanceNumber, type ProviderBalanceData } from './ProviderBalanceSummary';
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
  submitted: 'default', processing: 'processing', succeeded: 'success', failed: 'error',
  queued: 'default', running: 'processing', cancelled: 'default'
};
const statusLabel: Record<string, string> = {
  submitted: '已提交', queued: '排队中', processing: '处理中', running: '运行中', succeeded: '成功', failed: '失败', cancelled: '已取消'
};

export default function ProviderOperationsModal({ open, provider, capabilities, initialTab, onClose }: Props) {
  const [tab, setTab] = useState<'balance' | 'tasks'>(initialTab);
  const [balanceLoading, setBalanceLoading] = useState(false);
  const [taskLoading, setTaskLoading] = useState(false);
  const [balanceError, setBalanceError] = useState('');
  const [taskError, setTaskError] = useState('');
  const [balance, setBalance] = useState<ProviderBalanceData | null>(null);
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(30, 'day'), dayjs()]);
  const [packName, setPackName] = useState('');
  const [tasks, setTasks] = useState<any[]>([]);
  const [statusScope, setStatusScope] = useState<'running' | 'all'>('all');
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
  const balanceGateRef = useRef(new ProviderOperationRequestGate());
  const tasksLoadedRef = useRef(false);
  const resourcePackages = capabilities.balanceKind === 'resourcePackages' || provider?.providerCode?.toLowerCase() === 'kling';
  const taskQueryRef = useRef<ProviderTaskQuerySnapshot | null>(null);
  const pendingTaskQueryRef = useRef<ProviderTaskQuerySnapshot | null>(null);
  const providerId = provider?.id ?? null;
  const requestScopeRef = useRef<ProviderOperationRequestScope>({ open, providerId });
  if (requestScopeRef.current.open !== open || requestScopeRef.current.providerId !== providerId) {
    requestScopeRef.current = { open, providerId };
    requestGateRef.current.invalidate();
    balanceGateRef.current.invalidate();
    pendingTaskQueryRef.current = null;
  }

  useEffect(() => () => { requestGateRef.current.invalidate(); balanceGateRef.current.invalidate(); }, []);

  useEffect(() => {
    setSearchType((current) => resolveProviderTaskSearchType(current, taskSearchOptions));
  }, [providerId, taskSearchOptions]);

  useEffect(() => {
    if (!open) return;
    setTab(initialTab);
    setBalance(null);
    setBalanceError('');
    setTaskError('');
    setBalanceLoading(false);
    setTaskLoading(false);
    setTasks([]);
    setCursorStack([]);
    setNextCursor('');
    setHasMore(false);
    taskQueryRef.current = null;
    tasksLoadedRef.current = false;
    setStatusScope('all');
    setProductType('video');
    setSearchValue('');
    if (initialTab === 'balance') void loadBalance();
    else void loadTasks('', true, [], false, true);
    // 弹窗每次打开以最新供应商为准。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, provider?.id, initialTab]);

  const loadBalance = async (force = false) => {
    if (!provider) return;
    const token = balanceGateRef.current.begin(provider.id);
    setBalanceLoading(true);
    setBalanceError('');
    try {
      const res: any = await getProviderBalance(provider.id, resourcePackages ? {
        startTime: range[0].valueOf(), endTime: range[1].valueOf(),
        resourcePackName: packName || undefined
      } : {}, force);
      if (!balanceGateRef.current.isCurrent(token, requestScopeRef.current)) return;
      setBalance(res.data || {});
    } catch (e: any) {
      if (balanceGateRef.current.isCurrent(token, requestScopeRef.current)) {
        setBalanceError(e?.message || '余额查询失败，请检查供应商凭证后重试');
      }
    } finally {
      if (balanceGateRef.current.isCurrent(token, requestScopeRef.current)) setBalanceLoading(false);
    }
  };

  const loadTasks = async (cursor: string, resetQuery = false, targetStack = cursorStack, force = false, initial = false) => {
    if (!provider) return;
    let snapshot = taskQueryRef.current;
    if (resetQuery || !taskQueryRef.current) {
      const supportedSearchType = resolveProviderTaskSearchType(searchType, taskSearchOptions);
      const exactSearch = !initial && supportedSearchType ? searchValue.trim() : '';
      const endTime = Math.floor(Date.now() / 1000) * 1000;
      snapshot = {
        startTime: endTime - (capabilities.recentDays || 30) * 86400000,
        endTime,
        limit: 100,
        status: !initial && statusScope === 'running'
          ? (capabilities.taskStatuses || ['submitted', 'processing']).filter((value) => ['submitted', 'processing', 'queued', 'running'].includes(value)).join(',') : undefined,
        productType: initial ? 'video' : productType || undefined,
        searchType: supportedSearchType || undefined,
        searchValue: exactSearch || undefined
      };
      const pending = pendingTaskQueryRef.current;
      if (pending && JSON.stringify({ ...pending, startTime: 0, endTime: 0 })
          === JSON.stringify({ ...snapshot, startTime: 0, endTime: 0 })) snapshot = pending;
    }
    if (!snapshot) return;
    pendingTaskQueryRef.current = snapshot;
    const token = requestGateRef.current.begin(provider.id);
    setTaskLoading(true);
    setTaskError('');
    try {
      const exactSearch = snapshot.searchValue || '';
      const payload = buildProviderTaskPayload({
        cursor,
        exactSearch,
        searchType: snapshot.searchType || '',
        snapshot
      });
      const res: any = await listProviderUpstreamTasks(provider.id, payload, force);
      if (!requestGateRef.current.isCurrent(token, requestScopeRef.current)) return;
      const data = res.data || {};
      taskQueryRef.current = snapshot;
      setTasks(Array.isArray(data.result) ? data.result : []);
      tasksLoadedRef.current = true;
      setCursorStack(targetStack);
      const pageHasMore = Boolean(data.hasMore);
      setHasMore(pageHasMore);
      setNextCursor(pageHasMore && data.nextCursor ? data.nextCursor : '');
    } catch (e: any) {
      if (requestGateRef.current.isCurrent(token, requestScopeRef.current)) {
        setTaskError(e?.message || '上游任务查询失败，请重试');
      }
    } finally {
      if (requestGateRef.current.isCurrent(token, requestScopeRef.current)) {
        pendingTaskQueryRef.current = null;
        setTaskLoading(false);
      }
    }
  };

  const balanceRows = useMemo(() => {
    const rows = balance?.resource_pack_subscribe_infos;
    return Array.isArray(rows) ? rows : [];
  }, [balance]);

  const taskColumns = [
    { title: '任务 ID', dataIndex: 'id', width: 210, ellipsis: true },
    { title: '外部 ID', dataIndex: 'external_id', width: 160, ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <Tag color={statusColor[v] || 'default'}>{statusLabel[v] || v || '-'}</Tag> },
    { title: '创建时间', dataIndex: 'create_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '更新时间', dataIndex: 'update_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '消息', dataIndex: 'message', ellipsis: true }
  ];

  const tabs: any[] = [];
  if (capabilities.balance) tabs.push({
    key: 'balance', label: resourcePackages ? '资源包余量' : '账户余额', children: (
      <>
        <Alert type="info" showIcon message={capabilities.balanceDelayNotice || balance?.delayNotice || '上游余额可能存在统计延迟'} style={{ marginBottom: 12 }} />
        {balanceError && <Alert role="alert" type="error" showIcon message={balanceError} description={balance ? '以下保留上次查询结果，并非本次最新余额。' : '未取得有效余额，不会将查询失败视为余额为零。'} style={{ marginBottom: 12 }} />}
        <Space wrap style={{ marginBottom: 12 }}>
          {resourcePackages && <>
          <DatePicker.RangePicker value={range} onChange={(v) => v?.[0] && v?.[1] && setRange([v[0], v[1]])} />
          <Input allowClear placeholder="资源包名称（精确）" value={packName} onChange={(e) => setPackName(e.target.value)} style={{ width: 220 }} />
          </>}
          <Button type="primary" loading={balanceLoading} onClick={() => void loadBalance(true)}>刷新余额</Button>
          {balance?.queriedAt && <Typography.Text type="secondary">查询时间：{dayjs(balance.queriedAt).format('YYYY-MM-DD HH:mm:ss')}</Typography.Text>}
        </Space>
        {resourcePackages ? <Table rowKey={(r) => r.resource_pack_id} loading={balanceLoading} dataSource={balanceRows} pagination={false} size="small" scroll={{ x: 900 }}
          locale={{ emptyText: balanceError ? '未取得有效资源包数据' : '当前时间范围内未返回资源包，可扩大时间范围后重试；不代表账户余额为零。' }} columns={[
          { title: '资源包', dataIndex: 'resource_pack_name', width: 220 },
          { title: '类型', dataIndex: 'resource_pack_type', width: 150, render: (v: string) => ({ decreasing_total: '总量递减', constant_period: '周期恒定' }[v] || v) },
          { title: '总量', dataIndex: 'total_quantity', width: 100, render: balanceNumber },
          { title: '余量', dataIndex: 'remaining_quantity', width: 100, render: balanceNumber },
          { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => ({ online: '生效中', toBeOnline: '待生效', expired: '已到期', runOut: '已用完' }[v] || v) },
          { title: '生效时间', dataIndex: 'effective_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
          { title: '失效时间', dataIndex: 'invalid_time', width: 170, render: (v: number) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' }
        ]} /> : <Spin spinning={balanceLoading}><ProviderBalanceSummary data={balance} /></Spin>}
      </>
    )
  });
  if (capabilities.upstreamTasks) tabs.push({
    key: 'tasks', label: '上游任务', children: (
      <>
        <Alert type="info" showIcon message={capabilities.recentDays
          ? `官方仅提供最近 ${capabilities.recentDays} 天的视频任务，不包含更早的历史记录。`
          : '查询最近 30 天的上游任务；默认显示全部状态，也可使用任务 ID 精确查询。'} style={{ marginBottom: 12 }} />
        {taskError && <Alert role="alert" type="error" showIcon message={taskError} description="本次查询失败，以下数据如有显示，仍为上次成功查询的页面。" style={{ marginBottom: 12 }} />}
        <Space wrap style={{ marginBottom: 12 }}>
          <Select value={statusScope} onChange={setStatusScope} options={[{ value: 'running', label: '运行中' }, { value: 'all', label: '全部' }]} style={{ width: 110 }} />
          <Select allowClear value={productType || undefined} onChange={(v) => setProductType(v || '')} placeholder="功能类型" style={{ width: 120 }} options={(capabilities.productTypes || []).map((v) => ({ value: v, label: v }))} />
          <Select value={searchType || undefined} onChange={setSearchType} disabled={!taskSearchOptions.length}
            style={{ width: 155 }} options={taskSearchOptions} />
          <Input allowClear value={searchValue} onChange={(e) => setSearchValue(e.target.value)}
            disabled={!taskSearchOptions.length} placeholder="精确 ID，多个用逗号分隔" style={{ width: 260 }} />
          <Button type="primary" loading={taskLoading} onClick={() => void loadTasks('', true, [], true)}>查询</Button>
        </Space>
        <Table rowKey="id" loading={taskLoading} dataSource={tasks} columns={taskColumns} pagination={false} size="small" scroll={{ x: 980 }}
          locale={{ emptyText: taskError ? '任务查询失败，请重试' : '当前查询范围内没有符合条件的任务，可切换全部状态或用任务 ID 查询。' }} />
        <Space style={{ marginTop: 12 }}>
          <Button disabled={!cursorStack.length || taskLoading} onClick={() => {
            const stack = cursorStack.slice(0, -1); void loadTasks(stack.at(-1) || '', false, stack);
          }}>上一页</Button>
          <Button disabled={!hasMore || !nextCursor || taskLoading} onClick={() => {
            void loadTasks(nextCursor, false, [...cursorStack, nextCursor]);
          }}>下一页</Button>
          <Typography.Text type="secondary">第 {cursorStack.length + 1} 页 · 本页 {tasks.length} 条{hasMore && !tasks.length ? '，后续页可能还有符合筛选条件的任务' : ''}</Typography.Text>
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
      <Tabs activeKey={tab} onChange={(key) => {
        setTab(key as 'balance' | 'tasks');
        if (key === 'balance' && !balance && !balanceLoading) void loadBalance();
        if (key === 'tasks' && !tasksLoadedRef.current && !taskLoading) void loadTasks('', true, []);
      }} items={tabs} />
    </Modal>
  );
}
