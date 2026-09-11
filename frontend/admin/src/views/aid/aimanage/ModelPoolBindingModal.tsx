import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Empty, Modal, Space, Table, Tag, Typography } from 'antd';
import type { ModelPoolBindingPool, ModelPoolBindingSnapshot } from '@/api/aid/aimanage';
import { GENERATE_MODE_OPTIONS, MODEL_TYPE_OPTIONS, getLabelByValue } from '@/utils/enums';
import type { Model } from './types';

export type ModelPoolBindingMode = 'bind' | 'unbind';

interface Props {
  open: boolean;
  mode: ModelPoolBindingMode;
  models: Model[];
  snapshot: ModelPoolBindingSnapshot;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (poolIds: number[]) => void;
}

interface PoolRow extends ModelPoolBindingPool {
  selectedBindingCount: number;
  unavailableReason?: string;
}

/** 批量维护模型与现有模型池关系，不修改模型本身配置。 */
export default function ModelPoolBindingModal({ open, mode, models, snapshot, submitting, onCancel, onSubmit }: Props) {
  const [selectedPoolIds, setSelectedPoolIds] = useState<React.Key[]>([]);
  const modelIds = useMemo(() => models.map((model) => model.id).filter((id): id is number => id != null), [models]);
  const modelIdsKey = useMemo(() => modelIds.join(','), [modelIds]);
  const modelIdSet = useMemo(() => new Set(modelIds), [modelIds]);
  const selectedTypes = useMemo(() => new Set(models.map((model) => model.modelType)), [models]);
  const disabledModelCount = useMemo(() => models.filter((model) => model.status !== '0').length, [models]);

  useEffect(() => {
    if (open) setSelectedPoolIds([]);
  }, [open, mode, modelIdsKey]);

  const rows = useMemo<PoolRow[]>(() => snapshot.pools
    .map((pool) => {
      const selectedBindingCount = pool.modelIds.filter((id) => modelIdSet.has(id)).length;
      let unavailableReason: string | undefined;
      if (!pool.configurationValid) unavailableReason = '模型池配置异常，请先在模型池页面修复';
      else if (mode === 'bind' && (selectedTypes.size !== 1 || !selectedTypes.has(pool.modelType))) unavailableReason = '模型大类不一致';
      else if (mode === 'unbind' && selectedBindingCount === 0) unavailableReason = '所选模型均未绑定';
      else if (mode === 'unbind' && pool.status === '0' && pool.modelIds.every((id) => modelIdSet.has(id))) unavailableReason = '启用模型池不能移除全部模型';
      return { ...pool, selectedBindingCount, unavailableReason };
    })
    .filter((pool) => mode === 'bind' || pool.selectedBindingCount > 0),
  [snapshot.pools, modelIdSet, mode, selectedTypes]);

  const columns = [
    {
      title: '模型池', key: 'pool', render: (_: unknown, row: PoolRow) => (
        <div>
          <Space size={6} wrap>
            <Typography.Text strong>{row.funcName}</Typography.Text>
            <Tag>{row.funcCode}</Tag>
            {row.status === '1' && <Tag color="default">已停用</Tag>}
          </Space>
          <div style={{ marginTop: 4, color: '#94a3b8', fontSize: 12 }}>
            {getLabelByValue(MODEL_TYPE_OPTIONS, row.modelType)}
            {row.generateMode ? ` · ${getLabelByValue(GENERATE_MODE_OPTIONS, row.generateMode)}` : ''}
          </div>
        </div>
      )
    },
    {
      title: '当前关系', key: 'binding', width: 140, render: (_: unknown, row: PoolRow) => {
        if (row.selectedBindingCount === modelIds.length && modelIds.length > 0) return <Tag color="success">全部已绑定</Tag>;
        if (row.selectedBindingCount > 0) return <Tag color="gold">已绑定 {row.selectedBindingCount}/{modelIds.length}</Tag>;
        return <Tag>尚未绑定</Tag>;
      }
    },
    {
      title: '池内模型', dataIndex: 'modelIds', width: 90,
      render: (ids: number[]) => `${ids.length} 个`
    },
    {
      title: '可操作性', key: 'availability', width: 220,
      render: (_: unknown, row: PoolRow) => row.unavailableReason
        ? <Typography.Text type="danger">{row.unavailableReason}</Typography.Text>
        : <Typography.Text type="success">可以操作</Typography.Text>
    }
  ];

  return (
    <Modal
      open={open}
      title={mode === 'bind' ? `绑定模型池（${models.length} 个模型）` : `移出模型池（${models.length} 个模型）`}
      okText={mode === 'bind' ? '确认绑定' : '确认移出'}
      cancelText="取消"
      width={860}
      confirmLoading={submitting}
      okButtonProps={{ disabled: selectedPoolIds.length === 0 }}
      maskClosable={false}
      destroyOnClose
      onCancel={onCancel}
      onOk={() => onSubmit(selectedPoolIds.map(Number))}
    >
      <Alert
        type={mode === 'bind' ? 'info' : 'warning'}
        showIcon
        style={{ marginBottom: 14 }}
        message={mode === 'bind'
          ? '只建立模型池调度关系，不修改模型能力、SKU、价格、倍率或启停状态。'
          : '移除关系不会删除模型；存在活动引用或会清空启用模型池时，服务端会拒绝操作。'}
      />
      {mode === 'bind' && disabledModelCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 14 }}
          message={`所选模型中有 ${disabledModelCount} 个已停用；可以预先绑定，但恢复启用前不会参与调度。`}
        />
      )}
      <Table<PoolRow>
        rowKey="id"
        size="small"
        pagination={false}
        scroll={{ y: 420 }}
        dataSource={rows}
        columns={columns}
        locale={{ emptyText: <Empty description={mode === 'bind' ? '没有可用的同类模型池' : '所选模型尚未绑定模型池'} /> }}
        rowSelection={{
          selectedRowKeys: selectedPoolIds,
          onChange: setSelectedPoolIds,
          getCheckboxProps: (row) => ({ disabled: Boolean(row.unavailableReason), title: row.unavailableReason })
        }}
      />
    </Modal>
  );
}
