/**
 * v2.39.x：MiniMax 音色同步弹窗
 *
 * 从 aimanage/index.tsx 抽离的独立组件，核心目的：
 *   1) 组件定义在模块作用域，不会随父组件 re-render 被卸载 —— 避免穿梭面板里搜索框/滚动位置每次点击"添加/移除"都被重置
 *      （旧实现把 SyncTransferPanel 嵌在 AimanagePage 函数体内，每次父组件 re-render 就是一个全新的组件类型）
 *   2) 内部自己管 audioModelId 下拉 —— 让运营在多 audio 模型（speech-2.8-hd / speech-2.6-hd / speech-02-hd）时能显式选择目标模型
 *   3) 拉远程 + 应用同步都用同一个 audioModelId，避免"拉 A 模型、同步到 B 模型"的数据错乱
 *   4) 打开/关闭时重置内部状态，不驻留上次数据
 *
 * 对接后端：
 *   POST /aid/voice-library/sync/fetch-remote/{modelId}  → RemoteVoiceFetchResultVO
 *   POST /aid/voice-library/sync/apply                    → VoiceSyncResultVO (inserted/softDeleted)
 * 注意：后端 applySelectedSync 的 removedVoiceCodes 分支是 voiceLibraryService.removeById(...) 物理删除，
 * 因此前端成功提示使用"删除"而不是"移除/软删"，以免误导运营以为可恢复。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Modal, Select, Space, message } from 'antd';
import { applySyncSelected, fetchRemoteVoices } from '@/api/aid/voicelibrary';
import type { Model } from './types';

interface Props {
  open: boolean;
  /** 当前服务商下可同步的 audio 模型列表（已由父级筛 modelType='audio' & status!=1） */
  audioModels: Model[];
  onClose: () => void;
  /** 同步成功后的回调，父组件可据此刷新列表 */
  onSuccess?: () => void;
}

interface RemoteVoice {
  voiceCode: string;
  voiceName?: string;
  description?: string;
  exists?: boolean;
}

export default function SyncVoiceModal({ open, audioModels, onClose, onSuccess }: Props) {
  const [modelId, setModelId] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [applyLoading, setApplyLoading] = useState(false);
  const [remoteVoices, setRemoteVoices] = useState<RemoteVoice[]>([]);
  const [selectedCodes, setSelectedCodes] = useState<Set<string>>(new Set());
  // 记录第一次 fetch 时后端标记的 exists=true 集合，用来计算 removedVoiceCodes
  const [originalLocalCodes, setOriginalLocalCodes] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState('');
  const [hasFetched, setHasFetched] = useState(false);

  // 打开弹窗时重置内部状态；默认选中第一个可用 audio 模型，但不自动触发 fetch
  // —— 让运营明确知道"正在同步哪个模型"，必须主动点"拉取远程列表"
  useEffect(() => {
    if (!open) return;
    setRemoteVoices([]);
    setSelectedCodes(new Set());
    setOriginalLocalCodes(new Set());
    setSearch('');
    setHasFetched(false);
    setModelId(audioModels.length > 0 ? audioModels[0].id : undefined);
  }, [open, audioModels]);

  // 切换模型时清空上一次的数据，强制运营重新拉取
  // —— 防止"拉取 speech-2.8 的列表、再切到 speech-2.6 应用"导致错库
  useEffect(() => {
    setRemoteVoices([]);
    setSelectedCodes(new Set());
    setOriginalLocalCodes(new Set());
    setHasFetched(false);
  }, [modelId]);

  const handleFetch = async () => {
    if (!modelId) {
      message.warning('请选择要同步的 audio 模型');
      return;
    }
    setLoading(true);
    try {
      const res: any = await fetchRemoteVoices(modelId);
      const data = res?.data;
      const voices: RemoteVoice[] = data?.voices || [];
      setRemoteVoices(voices);
      const existCodes = new Set<string>(voices.filter((v) => v.exists).map((v) => v.voiceCode));
      setSelectedCodes(existCodes);
      setOriginalLocalCodes(existCodes);
      setHasFetched(true);
    } catch (e: any) {
      // request.ts 拦截层已经 message.error 过了，这里只是兜底
      if (e?.message) console.error('fetchRemoteVoices failed:', e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleApply = async () => {
    if (!modelId) {
      message.warning('请选择要同步的 audio 模型');
      return;
    }
    if (!hasFetched) {
      message.warning('请先拉取远程音色列表');
      return;
    }
    const selectedArr = Array.from(selectedCodes);
    const removedArr = Array.from(originalLocalCodes).filter((c) => !selectedCodes.has(c));
    if (selectedArr.length === 0 && removedArr.length === 0) {
      message.info('没有变更需要同步');
      return;
    }
    setApplyLoading(true);
    try {
      const res: any = await applySyncSelected({
        modelId,
        selectedVoiceCodes: selectedArr,
        removedVoiceCodes: removedArr,
      });
      const d = res?.data;
      // 后端 applySelectedSync 对 removedVoiceCodes 是物理删除，这里文案用"删除"更贴近真实行为
      const inserted = d?.inserted || 0;
      const deleted = d?.softDeleted || 0;
      message.success(
        `同步完成：入库 ${inserted} / 删除 ${deleted}${d?.modelCode ? ` · ${d.modelCode}` : ''}`
      );
      onSuccess?.();
      onClose();
    } catch (e: any) {
      if (e?.message) console.error('applySyncSelected failed:', e.message);
    } finally {
      setApplyLoading(false);
    }
  };

  const available = useMemo(() => {
    const kw = search.trim().toLowerCase();
    return remoteVoices.filter((v) => {
      if (selectedCodes.has(v.voiceCode)) return false;
      if (!kw) return true;
      return (
        (v.voiceName || '').toLowerCase().includes(kw) ||
        (v.voiceCode || '').toLowerCase().includes(kw)
      );
    });
  }, [remoteVoices, selectedCodes, search]);

  const selected = useMemo(
    () => remoteVoices.filter((v) => selectedCodes.has(v.voiceCode)),
    [remoteVoices, selectedCodes]
  );

  const addItem = (code: string) => {
    const next = new Set(selectedCodes);
    next.add(code);
    setSelectedCodes(next);
  };
  const removeItem = (code: string) => {
    const next = new Set(selectedCodes);
    next.delete(code);
    setSelectedCodes(next);
  };
  const addAll = () => {
    const next = new Set(selectedCodes);
    available.forEach((v) => next.add(v.voiceCode));
    setSelectedCodes(next);
  };
  const removeAll = () => {
    setSelectedCodes(new Set());
  };

  // 变更预览（给运营一个"保存前能看到影响范围"的数字）
  const diffNew = Array.from(selectedCodes).filter((c) => !originalLocalCodes.has(c)).length;
  const diffDel = Array.from(originalLocalCodes).filter((c) => !selectedCodes.has(c)).length;

  return (
    <Modal
      title="同步 MiniMax 音色"
      open={open}
      width={900}
      onCancel={onClose}
      maskClosable={false}
      footer={[
        <Button key="cancel" onClick={onClose}>
          取消
        </Button>,
        <Button key="fetch" onClick={handleFetch} loading={loading} disabled={!modelId}>
          {hasFetched ? '重新拉取' : '拉取远程列表'}
        </Button>,
        <Button
          key="apply"
          type="primary"
          onClick={handleApply}
          loading={applyLoading}
          disabled={!hasFetched}
        >
          确认同步
        </Button>,
      ]}
      styles={{ body: { paddingTop: 12 } }}
    >
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Alert
          type="info"
          showIcon
          message={
            <span>
              将调用 MiniMax <code>/v1/get_voice</code> 拉取该模型下的全量音色；
              右侧"已选中"中的音色会被写入音色库，移出右侧的音色会从音色库<strong>物理删除</strong>（不可恢复）。
            </span>
          }
        />

        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span style={{ fontSize: 13, color: '#4b5563' }}>同步到模型：</span>
          <Select
            style={{ width: 360 }}
            value={modelId}
            onChange={setModelId}
            placeholder="请选择 audio 模型"
            options={audioModels.map((m) => ({
              label: `${m.modelName} (${m.modelCode})`,
              value: m.id,
            }))}
          />
          {hasFetched && (
            <span style={{ fontSize: 12, color: '#94a3b8' }}>
              远程 {remoteVoices.length} / 本地已入库 {originalLocalCodes.size}
            </span>
          )}
        </div>

        {hasFetched && (
          <Alert
            type={diffNew || diffDel ? 'warning' : 'success'}
            showIcon
            message={
              diffNew || diffDel
                ? `本次将：新增入库 ${diffNew} 条，删除 ${diffDel} 条`
                : '当前选中与本地一致，点"确认同步"不会产生变更'
            }
          />
        )}

        <div style={{ display: 'flex', gap: 16 }}>
          {/* 左：可选区 */}
          <div
            style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              border: '1px solid #f0f0f0',
              borderRadius: 8,
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                padding: '10px 12px',
                background: '#fafafa',
                borderBottom: '1px solid #f0f0f0',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <span style={{ fontWeight: 600, fontSize: 13 }}>可选音色（{available.length}）</span>
              <Button type="link" size="small" onClick={addAll} disabled={!available.length}>
                全部添加 →
              </Button>
            </div>
            <div style={{ padding: '8px 12px 0' }}>
              <Input
                size="small"
                placeholder="搜索音色名称/编码..."
                allowClear
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: 8, maxHeight: 360, minHeight: 200 }}>
              {loading && <p style={{ color: '#999', textAlign: 'center' }}>加载中...</p>}
              {!loading && !hasFetched && (
                <p style={{ color: '#999', textAlign: 'center' }}>
                  点下方"拉取远程列表"加载音色
                </p>
              )}
              {!loading && hasFetched && available.length === 0 && (
                <p style={{ color: '#999', textAlign: 'center' }}>无可选音色</p>
              )}
              {available.map((v) => (
                <div
                  key={v.voiceCode}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '6px 8px',
                    borderRadius: 4,
                    marginBottom: 4,
                    background: '#fafafa',
                    fontSize: 13,
                  }}
                >
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {v.voiceName || v.voiceCode}
                    </div>
                    <code style={{ color: '#8c8c8c', fontSize: 11 }}>{v.voiceCode}</code>
                  </div>
                  <Button type="link" size="small" onClick={() => addItem(v.voiceCode)}>
                    添加 →
                  </Button>
                </div>
              ))}
            </div>
          </div>

          {/* 右：已选区 */}
          <div
            style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              border: '1px solid #f0f0f0',
              borderRadius: 8,
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                padding: '10px 12px',
                background: '#fafafa',
                borderBottom: '1px solid #f0f0f0',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <span style={{ fontWeight: 600, fontSize: 13 }}>已选中（{selected.length}）</span>
              <Button type="link" size="small" danger onClick={removeAll} disabled={!selected.length}>
                ← 全部移除
              </Button>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: 8, maxHeight: 400, minHeight: 240 }}>
              {selected.length === 0 && (
                <p style={{ color: '#999', textAlign: 'center' }}>暂无选中</p>
              )}
              {selected.map((v) => {
                const isOriginal = originalLocalCodes.has(v.voiceCode);
                return (
                  <div
                    key={v.voiceCode}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      padding: '6px 8px',
                      borderRadius: 4,
                      marginBottom: 4,
                      background: isOriginal ? '#f6ffed' : '#fffbe6',
                      fontSize: 13,
                    }}
                  >
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div
                        style={{
                          fontWeight: 500,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {v.voiceName || v.voiceCode}
                        {!isOriginal && (
                          <span
                            style={{
                              marginLeft: 6,
                              fontSize: 11,
                              color: '#faad14',
                              background: '#fff7e6',
                              padding: '1px 4px',
                              borderRadius: 3,
                            }}
                          >
                            新增
                          </span>
                        )}
                      </div>
                      <code style={{ color: '#8c8c8c', fontSize: 11 }}>{v.voiceCode}</code>
                    </div>
                    <Button
                      type="link"
                      size="small"
                      danger
                      onClick={() => removeItem(v.voiceCode)}
                    >
                      ← 移除
                    </Button>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </Space>
    </Modal>
  );
}
