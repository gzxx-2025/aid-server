import React, { useEffect, useMemo, useState } from 'react';
import { Modal, message } from 'antd';
import {
  listProvider, getProvider, addProvider, updateProvider, updateProviderStatus, delProvider,
  listModel, getModel, addModel, updateModel
} from '@/api/aid/aimanage';
import {
  getModelRetirementImpact,
  retireModel,
  type OrchestrationImpact
} from '@/api/aid/orchestration';
import { cleanExpiredVoices } from '@/api/aid/voicelibrary';
import ProviderPanel from './ProviderPanel';
import ModelTable from './ModelTable';
import OfficialGatewayCard from './OfficialGatewayCard';
import ProviderDialog from './ProviderDialog';
import ModelDialog from './ModelDialog';
import SyncVoiceModal from './SyncVoiceModal';
import RealModelOverviewDrawer from './RealModelOverviewDrawer';
import RetirementModal from '@/views/aid/orchestration/RetirementModal';
import type { Model, Provider } from './types';
import './style.less';

/**
 * AI 管理主页：服务商 + 模型列表 + MiniMax 音色同步入口
 *
 * v2.39.x 要点：
 * - SyncVoiceModal 作为独立模块级组件导入，父组件 re-render 不会把它重挂
 *   （旧版本把穿梭面板定义在函数体内，每次勾选音色都导致面板里搜索框/滚动位置被重置）
 * - 同步音色 / 清除过期 按钮只在 providerCode==='minimax' 时暴露，和后端 isMinimax 判定一致
 */
export default function AimanagePage() {
  const [providerList, setProviderList] = useState<Provider[]>([]);
  const [providerLoading, setProviderLoading] = useState(false);
  const [activeProvider, setActiveProvider] = useState<Provider | null>(null);
  const [allModels, setAllModels] = useState<Model[]>([]);
  const [modelList, setModelList] = useState<Model[]>([]);
  const [modelLoading, setModelLoading] = useState(false);
  const [modelQuery, setModelQuery] = useState<any>({ modelType: null, generateMode: null, inputRequirement: null, keyword: '' });
  const [providerDlg, setProviderDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [modelDlg, setModelDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [retireState, setRetireState] = useState<{
    open: boolean;
    loading: boolean;
    submitting: boolean;
    row?: Model;
    impact?: OrchestrationImpact;
  }>({ open: false, loading: false, submitting: false });

  const modelCounts = useMemo(() => {
    const map: Record<number, number> = {};
    allModels.forEach((m) => { map[m.providerId!] = (map[m.providerId!] || 0) + 1; });
    return map;
  }, [allModels]);

  const loadProviders = async () => {
    setProviderLoading(true);
    try {
      const res: any = await listProvider({ pageNum: 1, pageSize: 999 });
      const list = res.rows || [];
      setProviderList(list);
      const res2: any = await listModel({ pageNum: 1, pageSize: 9999 });
      setAllModels(res2.rows || []);
      if (list.length > 0 && !activeProvider) setActiveProvider(list[0]);
    } finally { setProviderLoading(false); }
  };

  const loadModels = async () => {
    if (!activeProvider) return;
    setModelLoading(true);
    try {
      const res: any = await listModel({ pageNum: 1, pageSize: 999, providerId: activeProvider.id });
      let rows: Model[] = res.rows || [];
      if (modelQuery.modelType) rows = rows.filter((m) => m.modelType === modelQuery.modelType);
      if (modelQuery.generateMode) rows = rows.filter((m) => m.generateMode === modelQuery.generateMode);
      // 输入要求（后端推导标签）：区分纯文本/图片可选/图片必传/视频必传
      if (modelQuery.inputRequirement) rows = rows.filter((m) => m.inputRequirement === modelQuery.inputRequirement);
      if (modelQuery.keyword) {
        const kw = modelQuery.keyword.toLowerCase();
        rows = rows.filter((m) => (m.modelCode || '').toLowerCase().includes(kw) || (m.realModelCode || '').toLowerCase().includes(kw) || (m.modelName || '').toLowerCase().includes(kw));
      }
      setModelList(rows);
    } finally { setModelLoading(false); }
  };

  useEffect(() => { loadProviders(); }, []);
  useEffect(() => { loadModels(); }, [activeProvider, modelQuery]);

  const handleAddProvider = () => setProviderDlg({ open: true, title: '新增服务商', data: { status: '0' } });
  const handleEditProvider = async () => {
    if (!activeProvider) return;
    const res: any = await getProvider(activeProvider.id);
    setProviderDlg({ open: true, title: '编辑服务商', data: res.data });
  };
  const handleDeleteProvider = () => {
    if (!activeProvider) return;
    Modal.confirm({
      title: '确认删除',
      content: `是否确认删除服务商【${activeProvider.providerName}】？`,
      okType: 'danger',
      onOk: async () => {
        await delProvider(activeProvider.id);
        message.success('删除成功');
        setActiveProvider(null);
        loadProviders();
      }
    });
  };
  const handleProviderSubmit = async (values: any) => {
    if (values.id) { await updateProvider(values); message.success('修改成功'); }
    else { await addProvider(values); message.success('新增成功'); }
    setProviderDlg({ open: false, title: '' });
    loadProviders();
  };

  /** 行内开关直接启停服务商：只提交 id + status，其余字段不动 */
  const handleToggleProviderStatus = async (p: Provider, enabled: boolean) => {
    const status = enabled ? '0' : '1';
    await updateProviderStatus({ id: p.id, status });
    message.success(enabled ? `已启用【${p.providerName}】` : `已停用【${p.providerName}】`);
    // 本地同步列表与选中项状态，避免整页刷新造成闪烁
    setProviderList((prev) => prev.map((it) => (it.id === p.id ? { ...it, status } : it)));
    if (activeProvider?.id === p.id) setActiveProvider({ ...activeProvider, status });
  };

  const handleEditModel = async (row: Model) => {
    const res: any = await getModel(row.id);
    setModelDlg({ open: true, title: '编辑模型', data: res.data });
  };
  const handleAddModel = () => {
    setModelDlg({ open: true, title: '新增模型', data: { providerId: activeProvider?.id, status: '0', billingMode: 'FIXED', priority: 1, billingMultiplier: 1, isFree: false } });
  };
  /** 行内开关直接启停模型：modelCode 用于通过后端更新校验 */
  const handleToggleModelStatus = async (row: Model, enabled: boolean) => {
    if (row.id == null) return;
    const status = enabled ? '0' : '1';
    await updateModel({ id: row.id, modelCode: row.modelCode, status });
    message.success(enabled ? `已启用【${row.modelName}】` : `已停用【${row.modelName}】`);
    // 同步当前表格与模型汇总，避免刷新页面造成闪烁
    setModelList((prev) => prev.map((item) => (item.id === row.id ? { ...item, status } : item)));
    setAllModels((prev) => prev.map((item) => (item.id === row.id ? { ...item, status } : item)));
  };
  const handleDeleteModel = async (row: Model) => {
    if (row.id == null) return;
    setRetireState({ open: true, loading: true, submitting: false, row });
    try {
      const res: any = await getModelRetirementImpact(row.id);
      setRetireState({ open: true, loading: false, submitting: false, row, impact: res.data });
    } catch {
      setRetireState({ open: false, loading: false, submitting: false });
    }
  };

  const replacementModelOptions = useMemo(() => {
    const target = retireState.row;
    if (!target) return [];
    const enabledProviderIds = new Set(
      providerList.filter((provider) => provider.status === '0').map((provider) => provider.id)
    );
    return allModels
      .filter((model) => model.id !== target.id
        && model.status === '0'
        && model.modelType === target.modelType
        && enabledProviderIds.has(model.providerId!))
      .map((model) => ({
        value: model.modelCode,
        label: `${model.modelName}（${model.modelCode}）`
      }));
  }, [allModels, providerList, retireState.row]);

  const confirmRetireModel = async (replacementCode?: string) => {
    if (retireState.row?.id == null) return;
    setRetireState((state) => ({ ...state, submitting: true }));
    try {
      await retireModel(retireState.row.id, replacementCode);
      message.success(replacementCode ? '模型引用已替换并完成下线' : '模型引用已清理并完成下线');
      setRetireState({ open: false, loading: false, submitting: false });
      await Promise.all([loadModels(), loadProviders()]);
    } finally {
      setRetireState((state) => state.open ? { ...state, submitting: false } : state);
    }
  };

  // ==================== 真实模型总览 ====================
  const [overviewOpen, setOverviewOpen] = useState(false);

  // ==================== v2.39.x：MiniMax 音色同步 ====================
  const [syncModalOpen, setSyncModalOpen] = useState(false);

  // 给弹窗的 audio 模型列表（排除已停用）
  const syncAudioModels = useMemo(
    () => modelList.filter((m) => m.modelType === 'audio' && m.status !== '1'),
    [modelList]
  );

  const handleOpenSync = () => {
    if (syncAudioModels.length === 0) {
      message.warning('请先在当前服务商下新增 audio 类型模型');
      return;
    }
    setSyncModalOpen(true);
  };

  const handleCleanExpired = () => {
    Modal.confirm({
      title: '清除过期音色',
      content: '将把所有下架时间已过期（offline_time ≤ 当前时间）的音色软删除。此操作不可撤销，确认继续？',
      okType: 'danger',
      okText: '确认清除',
      onOk: async () => {
        const res: any = await cleanExpiredVoices();
        const count = res?.data || 0;
        message.success(`已清除 ${count} 条过期音色`);
      }
    });
  };

  return (
    <>
      <OfficialGatewayCard models={allModels} providers={providerList} />
      <div className="aimanage">
      <ProviderPanel
        list={providerList}
        loading={providerLoading}
        active={activeProvider}
        modelCounts={modelCounts}
        onSelect={(p) => { setActiveProvider(p); setModelQuery({ modelType: null, generateMode: null, inputRequirement: null, keyword: '' }); }}
        onAdd={handleAddProvider}
        onEdit={handleEditProvider}
        onDelete={handleDeleteProvider}
        onToggleStatus={handleToggleProviderStatus}
      />
      <ModelTable
        provider={activeProvider}
        list={modelList}
        loading={modelLoading}
        query={modelQuery}
        onQueryChange={setModelQuery}
        onAdd={handleAddModel}
        onEdit={handleEditModel}
        onDelete={handleDeleteModel}
        onToggleStatus={handleToggleModelStatus}
        onSyncVoice={handleOpenSync}
        onCleanExpired={handleCleanExpired}
        onOpenOverview={() => setOverviewOpen(true)}
      />
      <ProviderDialog
        open={providerDlg.open}
        title={providerDlg.title}
        data={providerDlg.data}
        onCancel={() => setProviderDlg({ open: false, title: '' })}
        onOk={handleProviderSubmit}
      />
      <ModelDialog
        open={modelDlg.open}
        title={modelDlg.title}
        provider={activeProvider}
        data={modelDlg.data}
        onCancel={() => setModelDlg({ open: false, title: '' })}
        onOk={async (values) => {
          if (values.id) { await updateModel(values); message.success('修改成功'); }
          else { await addModel(values); message.success('新增成功'); }
          setModelDlg({ open: false, title: '' });
          loadModels();
        }}
      />
      <RetirementModal
        open={retireState.open}
        loading={retireState.loading}
        submitting={retireState.submitting}
        impact={retireState.impact}
        replacementOptions={replacementModelOptions}
        onCancel={() => setRetireState({ open: false, loading: false, submitting: false })}
        onConfirm={confirmRetireModel}
      />
      <SyncVoiceModal
        open={syncModalOpen}
        audioModels={syncAudioModels}
        onClose={() => setSyncModalOpen(false)}
      />
      <RealModelOverviewDrawer
        open={overviewOpen}
        onClose={() => setOverviewOpen(false)}
        onStatusChanged={() => { loadModels(); loadProviders(); }}
      />
      </div>
    </>
  );
}
