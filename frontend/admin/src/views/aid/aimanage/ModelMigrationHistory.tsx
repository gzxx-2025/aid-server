import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Popconfirm, Table, Tag, message } from 'antd';
import { request } from '@/utils/request';
import { sharedReadRequest, invalidateSharedReads } from '@/utils/sharedReadRequest';

interface Entry { id: number; status: string; affectedModels: number; createTime: string; createBy: string }
const load = () => sharedReadRequest('/aid/aidmodel/migrations', undefined, true);

export default function ModelMigrationHistory({ open, onClose, onComplete }: { open: boolean; onClose: () => void; onComplete: () => void }) {
  const [rows, setRows] = useState<Entry[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const flight = useRef(false);
  useEffect(() => {
    let active = true;
    if (!open) return;
    setBusy(true); setError('');
    load().then((result) => { if (active) setRows(result.data || []); }).catch(() => { if (active) setError('迁移记录读取失败'); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [open]);
  const rollback = async (id: number) => {
    if (flight.current) return;
    flight.current = true; setBusy(true); setError('');
    try {
      await request({ url: `/aid/aidmodel/migrations/${id}/rollback`, method: 'post' });
      invalidateSharedReads('/aid/');
      const result = await load(); setRows(result.data || []); onComplete(); message.success('已恢复迁移前的模型配置');
    } catch (error: any) { setError(error?.message || '恢复未完成，请核对迁移后的配置变化'); }
    finally { flight.current = false; setBusy(false); }
  };
  return <Modal open={open} title="模型迁移记录" width={850} footer={null} onCancel={() => { if (!flight.current) onClose(); }}>
    <Alert type="info" showIcon message="恢复只涉及本次迁移的模型与业务配置，保留全部任务和账单。迁移后配置已被编辑时会拒绝直接恢复。" style={{ marginBottom: 12 }} />
    {error && <Alert type="error" message={error} />}
    <Table size="small" rowKey="id" dataSource={rows} loading={busy} columns={[
      { title: '编号', dataIndex: 'id' }, { title: '迁移时间', dataIndex: 'createTime' }, { title: '操作人', dataIndex: 'createBy' },
      { title: '模型记录数', dataIndex: 'affectedModels' }, { title: '状态', dataIndex: 'status', render: (status: string) => <Tag color={status === 'APPLIED' ? 'blue' : undefined}>{status === 'APPLIED' ? '已应用' : '已恢复'}</Tag> },
      { title: '操作', render: (_: unknown, row: Entry) => row.status === 'APPLIED' && <Popconfirm title="恢复此批迁移前的配置？" description="系统将先核对配置是否仍与迁移完成时一致。" onConfirm={() => rollback(row.id)}><Button size="small" disabled={busy}>恢复配置</Button></Popconfirm> }
    ]} />
  </Modal>;
}
