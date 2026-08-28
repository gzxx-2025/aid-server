import React, { useEffect, useMemo, useState } from 'react';
import { Button, Input, Switch, Tooltip, message } from 'antd';
import { ApiOutlined, PlusOutlined, EditOutlined, DeleteOutlined, SearchOutlined, ExperimentOutlined, DollarCircleOutlined, UnorderedListOutlined } from '@ant-design/icons';
import type { Provider, ProviderOperationCapabilities } from './types';
import { getProviderOperationCapabilities } from '@/api/aid/aimanage';
import ProviderOperationsModal from './ProviderOperationsModal';
import {
  createProviderCapabilityScope,
  ownsProviderCapabilities,
  providerCapabilityScopeKey,
  type ProviderCapabilityScope
} from './providerOperations';
import { runConfigTest, type ConfigTestResult } from '@/api/system/configTest';
import TestResultModal from '@/components/TestResultModal';

interface Props {
  list: Provider[];
  loading: boolean;
  active: Provider | null;
  modelCounts: Record<number, number>;
  onSelect: (p: Provider) => void;
  onAdd: () => void;
  onEdit: () => void;
  onDelete: () => void;
  /** 行内开关：直接启用/停用服务商，无需进入编辑弹窗 */
  onToggleStatus: (p: Provider, enabled: boolean) => Promise<void>;
}

export default function ProviderPanel(props: Props) {
  const { list, loading, active, modelCounts, onSelect, onAdd, onEdit, onDelete, onToggleStatus } = props;
  const [kw, setKw] = useState('');
  const [testing, setTesting] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [testResult, setTestResult] = useState<ConfigTestResult | null>(null);
  // 正在切换启停的服务商 id（用于对应行 Switch 的 loading 态）
  const [togglingId, setTogglingId] = useState<number | null>(null);
  const [operations, setOperations] = useState<ProviderOperationCapabilities>({});
  const [operationsScope, setOperationsScope] = useState<ProviderCapabilityScope | null>(null);
  const [operationsOpen, setOperationsOpen] = useState(false);
  const [operationsTab, setOperationsTab] = useState<'balance' | 'tasks'>('balance');
  const activeCapabilityScope = createProviderCapabilityScope(active);
  const activeCapabilityScopeKey = providerCapabilityScopeKey(activeCapabilityScope);

  useEffect(() => {
    let alive = true;
    setOperations({});
    setOperationsScope(null);
    setOperationsOpen(false);
    if (!active || !activeCapabilityScope) return;
    const requestedScope = activeCapabilityScope;
    getProviderOperationCapabilities(requestedScope.providerId)
      .then((res: any) => {
        if (alive) {
          setOperations(res.data || {});
          setOperationsScope(requestedScope);
        }
      })
      .catch(() => {
        if (alive) {
          setOperations({});
          setOperationsScope(requestedScope);
        }
      });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeCapabilityScopeKey]);

  // id 或规范化 providerCode 改变后的首帧即隐藏旧能力，不等待 effect 发出新请求。
  const activeOperations = ownsProviderCapabilities(operationsScope, active) ? operations : {};

  const openOperations = (tab: 'balance' | 'tasks') => {
    setOperationsTab(tab);
    setOperationsOpen(true);
  };

  const handleTest = async () => {
    if (!active || testing) return;
    setTesting(true);
    try {
      const res = await runConfigTest('ai-provider', { providerId: active.id });
      setTestResult(res.data);
      setTestOpen(true);
    } catch (e: any) {
      message.error(e?.message || '测试请求失败');
    } finally {
      setTesting(false);
    }
  };

  const handleToggle = async (p: Provider, enabled: boolean) => {
    if (togglingId != null) return;
    setTogglingId(p.id);
    try {
      await onToggleStatus(p, enabled);
    } catch {
      // 失败提示由请求拦截器统一弹出，这里吞掉避免未捕获异常；开关状态保持原值
    } finally {
      setTogglingId(null);
    }
  };

  const filtered = useMemo(() => {
    if (!kw) return list;
    const lower = kw.toLowerCase();
    return list.filter(
      (p) =>
        (p.providerName || '').toLowerCase().includes(lower) ||
        (p.providerCode || '').toLowerCase().includes(lower)
    );
  }, [list, kw]);

  return (
    <div className="aimanage-sidebar">
      <div className="aimanage-sidebar__header">
        <ApiOutlined /> AI大模型服务商
        <span className="provider-total">{list.length}</span>
      </div>
      <div className="aimanage-sidebar__search">
        <Input size="small" prefix={<SearchOutlined />} placeholder="搜索服务商..." value={kw} onChange={(e) => setKw(e.target.value)} allowClear />
      </div>
      <div className="aimanage-sidebar__list">
        {filtered.map((p) => {
          const isActive = active?.id === p.id;
          const enabled = p.status === '0';
          return (
            <div
              key={p.id}
              className={`aimanage-sidebar__item ${isActive ? 'active' : ''} ${enabled ? '' : 'stopped'}`}
              onClick={() => onSelect(p)}
            >
              <div className="provider-row">
                {/* 服务商 LOGO：有则展示图标，无则用首字母占位，保持列表对齐 */}
                {p.logoUrl ? (
                  <img
                    src={p.logoUrl}
                    alt={p.providerName}
                    className="provider-logo"
                    onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
                  />
                ) : (
                  <span className="provider-logo provider-logo--placeholder">
                    {(p.providerName || p.providerCode || '?').slice(0, 1).toUpperCase()}
                  </span>
                )}
                <span className="provider-name">{p.providerName}</span>
                {/* 启停开关：阻止冒泡，避免切换时误选中该行 */}
                <span onClick={(e) => e.stopPropagation()} style={{ flexShrink: 0 }}>
                  <Tooltip title={enabled ? '点击停用' : '点击启用'}>
                    <Switch
                      size="small"
                      checked={enabled}
                      loading={togglingId === p.id}
                      onChange={(checked) => handleToggle(p, checked)}
                    />
                  </Tooltip>
                </span>
              </div>
              <div className="provider-sub">
                <span className="provider-code">{p.providerCode}</span>
                <span className="model-count">{modelCounts[p.id] || 0} 个模型</span>
              </div>
              {/* 选中的服务商直接内联操作，不用再去底部找按钮 */}
              {isActive && (
                <div className="provider-actions" onClick={(e) => e.stopPropagation()}>
                  <Button size="small" icon={<EditOutlined />} onClick={onEdit}>编辑</Button>
                  <Button size="small" icon={<ExperimentOutlined />} loading={testing} onClick={handleTest}>测试</Button>
                  {activeOperations.balance && <Button size="small" icon={<DollarCircleOutlined />} onClick={() => openOperations('balance')}>余额</Button>}
                  {activeOperations.upstreamTasks && <Button size="small" icon={<UnorderedListOutlined />} onClick={() => openOperations('tasks')}>任务</Button>}
                  <Button size="small" icon={<DeleteOutlined />} onClick={onDelete}>删除</Button>
                </div>
              )}
            </div>
          );
        })}
        {filtered.length === 0 && <div style={{ padding: 20, textAlign: 'center', color: '#94a3b8' }}>暂无服务商</div>}
      </div>
      <div className="aimanage-sidebar__footer">
        <Button type="primary" icon={<PlusOutlined />} block onClick={onAdd}>新增服务商</Button>
      </div>
      <TestResultModal open={testOpen} result={testResult} onClose={() => setTestOpen(false)} />
      <ProviderOperationsModal open={operationsOpen} provider={active} capabilities={activeOperations} initialTab={operationsTab} onClose={() => setOperationsOpen(false)} />
    </div>
  );
}
