import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Modal, message } from 'antd';
import {
  listProvider, getProvider, addProvider, updateProvider, updateProviderStatus, delProvider,
  listModel, getModel, addModel, updateModel, getModelPoolBindings, bindModelsToPools, unbindModelsFromPools,
  type ModelPoolBindingSnapshot
} from '@/api/aid/aimanage';
import {
  getModelRetirementImpact,
  retireModel,
  type OrchestrationImpact
} from '@/api/aid/orchestration';
import { cleanExpiredVoices } from '@/api/aid/voicelibrary';
import ProviderPanel from './ProviderPanel';
import ModelTable from './ModelTable';
import TokenDanceRecommendedCard from './TokenDanceRecommendedCard';
import { isTokenDanceProvider } from './recommendedProvider';
import TokenDanceAccountModal from './TokenDanceAccountModal';
import TokenDanceCatalogModal from './TokenDanceCatalogModal';
import ProviderDialog from './ProviderDialog';
import ModelDialog from './ModelDialog';
import SyncVoiceModal from './SyncVoiceModal';
import RealModelOverviewDrawer from './RealModelOverviewDrawer';
import ModelPoolBindingModal, { type ModelPoolBindingMode } from './ModelPoolBindingModal';
import RetirementModal from '@/views/aid/orchestration/RetirementModal';
import type { Model, Provider } from './types';
import './style.less';

const EMPTY_POOL_SNAPSHOT: ModelPoolBindingSnapshot = { pools: [], models: [] };

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
  const [providerLoading, setProviderLoading] = useState(true);
  const [activeProvider, setActiveProvider] = useState<Provider | null>(null);
  const [allModels, setAllModels] = useState<Model[]>([]);
  const [poolSnapshot, setPoolSnapshot] = useState<ModelPoolBindingSnapshot>(EMPTY_POOL_SNAPSHOT);
  const [poolSnapshotReady, setPoolSnapshotReady] = useState(false);
  const [selectedModelIds, setSelectedModelIds] = useState<number[]>([]);
  const [poolBindingMode, setPoolBindingMode] = useState<ModelPoolBindingMode | null>(null);
  const [poolBindingSubmitting, setPoolBindingSubmitting] = useState(false);
  const poolBindingSubmitRef = useRef<Promise<void> | null>(null);
  const [tokenDanceAction, setTokenDanceAction] = useState<'account' | 'catalog' | null>(null);
  const tokenDanceProvider = providerList.find(isTokenDanceProvider) || null;
  const providerLoadRef = useRef<Promise<void> | null>(null);
  const [modelQuery, setModelQuery] = useState<any>({ modelType: null, generateMode: null, inputRequirement: null, poolId: null, keyword: '' });
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

  const loadProviderSnapshot = useCallback(async () => {
    setProviderLoading(true);
    try {
      const [res, res2, poolRes]: any[] = await Promise.all([
        listProvider({ pageNum: 1, pageSize: 999 }),
        listModel({ pageNum: 1, pageSize: 9999 }),
        getModelPoolBindings().catch(() => null)
      ]);
      const list: Provider[] = [...(res.rows || [])].sort((a, b) => Number(isTokenDanceProvider(b)) - Number(isTokenDanceProvider(a)));
      setProviderList(list);
      setAllModels(res2.rows || []);
      if (poolRes?.data) {
        setPoolSnapshot(poolRes.data);
        setPoolSnapshotReady(true);
      } else {
        setPoolSnapshotReady(false);
      }
      setActiveProvider((current) => list.find((provider) => provider.id === current?.id) || list[0] || null);
    } finally { setProviderLoading(false); }
  }, []);

  const loadProviders = useCallback((): Promise<void> => {
    if (providerLoadRef.current) return providerLoadRef.current;
    const pending = loadProviderSnapshot();
    providerLoadRef.current = pending;
    void pending.finally(() => {
      if (providerLoadRef.current === pending) providerLoadRef.current = null;
    }).catch(() => undefined);
    return pending;
  }, [loadProviderSnapshot]);

  // 写操作完成后的刷新必须晚于旧快照请求；并发刷新仍合并到同一个新请求。
  const refreshProviders = async () => {
    const pending = providerLoadRef.current;
    if (pending) await pending.catch(() => undefined);
    await loadProviders();
  };

  const modelList = useMemo(() => {
      let rows = allModels.filter((model) => model.providerId === activeProvider?.id);
      if (modelQuery.modelType) rows = rows.filter((m) => m.modelType === modelQuery.modelType);
      if (modelQuery.generateMode) rows = rows.filter((m) => m.generateMode === modelQuery.generateMode);
      // 输入要求（后端推导标签）：区分纯文本/图片可选/图片必传/视频必传
      if (modelQuery.inputRequirement) rows = rows.filter((m) => m.inputRequirement === modelQuery.inputRequirement);
      if (modelQuery.poolId != null) {
        const membership = new Map(poolSnapshot.models.map((model) => [model.id, model.poolIds]));
        rows = rows.filter((model) => {
          const poolIds = membership.get(model.id!) || [];
          return modelQuery.poolId === 'unbound' ? poolIds.length === 0 : poolIds.includes(Number(modelQuery.poolId));
        });
      }
      if (modelQuery.keyword) {
        const kw = modelQuery.keyword.toLowerCase();
        rows = rows.filter((m) => (m.modelCode || '').toLowerCase().includes(kw) || (m.realModelCode || '').toLowerCase().includes(kw) || (m.modelName || '').toLowerCase().includes(kw));
      }
      return rows;
  }, [allModels, activeProvider?.id, modelQuery, poolSnapshot.models]);

  const selectedModels = useMemo(
    () => allModels.filter((model) => model.id != null && selectedModelIds.includes(model.id)),
    [allModels, selectedModelIds]
  );

  useEffect(() => { void loadProviders().catch(() => undefined); }, [loadProviders]);

  useEffect(() => {
    const existingIds = new Set(allModels.map((model) => model.id).filter((id): id is number => id != null));
    setSelectedModelIds((ids) => ids.filter((id) => existingIds.has(id)));
  }, [allModels]);

  useEffect(() => {
    setSelectedModelIds([]);
  }, [activeProvider?.id]);

  useEffect(() => {
    const visibleIds = new Set(modelList.map((model) => model.id).filter((id): id is number => id != null));
    setSelectedModelIds((ids) => {
      const next = ids.filter((id) => visibleIds.has(id));
      return next.length === ids.length ? ids : next;
    });
  }, [modelList]);

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
        await refreshProviders();
      }
    });
  };
  const handleProviderSubmit = async (values: any) => {
    if (values.id) { await updateProvider(values); message.success('修改成功'); }
    else { await addProvider(values); message.success('新增成功'); }
    setProviderDlg({ open: false, title: '' });
    await refreshProviders();
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
    let current = row;
    if (current.configVersion == null) {
      const currentResponse: any = await getModel(row.id);
      current = currentResponse.data;
    }
    await updateModel({
      id: row.id,
      modelCode: current.modelCode,
      status,
      configVersion: current.configVersion
    });
    const refreshedResponse: any = await getModel(row.id);
    const refreshed: Model = refreshedResponse.data;
    message.success(enabled ? `已启用【${row.modelName}】` : `已停用【${row.modelName}】`);
    // 权威回读包含服务端递增后的 configVersion，后续更新不得复用旧版本。
    setAllModels((prev) => prev.map((item) => (item.id === row.id ? { ...item, ...refreshed } : item)));
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

  const handleOpenPoolBinding = (mode: ModelPoolBindingMode) => {
    if (selectedModels.length === 0) {
      message.warning('请先选择模型');
      return;
    }
    if (mode === 'bind' && new Set(selectedModels.map((model) => model.modelType)).size > 1) {
      message.warning('请先选择同类模型');
      return;
    }
    setPoolBindingMode(mode);
  };

  const handlePoolBindingSubmit = (poolIds: number[]) => {
    if (!poolBindingMode || poolBindingSubmitRef.current) return poolBindingSubmitRef.current || Promise.resolve();
    const operation = poolBindingMode;
    const task = (async () => {
      setPoolBindingSubmitting(true);
      try {
        const pendingLoad = providerLoadRef.current;
        if (pendingLoad) await pendingLoad;
        const response: any = operation === 'bind'
          ? await bindModelsToPools(selectedModelIds, poolIds)
          : await unbindModelsFromPools(selectedModelIds, poolIds);
        const result = response.data;
        if (result?.snapshot) setPoolSnapshot(result.snapshot);
        message.success(result?.changedRelationCount > 0
          ? `已${operation === 'bind' ? '绑定' : '移出'} ${result.changedRelationCount} 个关系`
          : '当前关系无需变更');
        setPoolBindingMode(null);
        setSelectedModelIds([]);
      } finally {
        setPoolBindingSubmitting(false);
      }
    })();
    poolBindingSubmitRef.current = task;
    void task.finally(() => {
      if (poolBindingSubmitRef.current === task) poolBindingSubmitRef.current = null;
    }).catch(() => undefined);
    return task;
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
      await refreshProviders();
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
      <TokenDanceRecommendedCard
        provider={tokenDanceProvider}
        loading={providerLoading}
        modelCount={tokenDanceProvider ? modelCounts[tokenDanceProvider.id] || 0 : 0}
        enabledModelCount={allModels.filter((model) => model.providerId === tokenDanceProvider?.id && model.status === '0').length}
        onOpen={setTokenDanceAction}
        onSelect={() => {
          if (!tokenDanceProvider) return;
          setActiveProvider(tokenDanceProvider);
          setModelQuery({ modelType: null, generateMode: null, inputRequirement: null, poolId: null, keyword: '' });
          setSelectedModelIds([]);
        }}
      />
      <TokenDanceAccountModal open={tokenDanceAction === 'account'} provider={tokenDanceProvider} onClose={() => setTokenDanceAction(null)} />
      <TokenDanceCatalogModal open={tokenDanceAction === 'catalog'} provider={tokenDanceProvider} onClose={() => setTokenDanceAction(null)} onImported={refreshProviders} />
      <div className="aimanage">
      <ProviderPanel
        list={providerList}
        loading={providerLoading}
        active={activeProvider}
        modelCounts={modelCounts}
        onSelect={(p) => { setActiveProvider(p); setModelQuery({ modelType: null, generateMode: null, inputRequirement: null, poolId: null, keyword: '' }); setSelectedModelIds([]); }}
        onAdd={handleAddProvider}
        onEdit={handleEditProvider}
        onDelete={handleDeleteProvider}
        onToggleStatus={handleToggleProviderStatus}
        onTokenDanceAction={setTokenDanceAction}
      />
      <ModelTable
        provider={activeProvider}
        list={modelList}
        loading={providerLoading}
        query={modelQuery}
        onQueryChange={setModelQuery}
        onAdd={handleAddModel}
        onEdit={handleEditModel}
        onDelete={handleDeleteModel}
        onToggleStatus={handleToggleModelStatus}
        onSyncVoice={handleOpenSync}
        onCleanExpired={handleCleanExpired}
        onOpenOverview={() => setOverviewOpen(true)}
        poolSnapshot={poolSnapshot}
        poolSnapshotReady={poolSnapshotReady}
        selectedModelIds={selectedModelIds}
        onSelectionChange={setSelectedModelIds}
        onOpenPoolBinding={handleOpenPoolBinding}
      />
      <ModelPoolBindingModal
        open={poolBindingMode != null}
        mode={poolBindingMode || 'bind'}
        models={selectedModels}
        snapshot={poolSnapshot}
        submitting={poolBindingSubmitting}
        onCancel={() => setPoolBindingMode(null)}
        onSubmit={(poolIds) => { void handlePoolBindingSubmit(poolIds); }}
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
          await refreshProviders();
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
        onStatusChanged={() => { void refreshProviders().catch(() => undefined); }}
      />
      </div>
    </>
  );
}
