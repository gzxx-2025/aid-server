import React, { useCallback, useRef, useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Select,
  Space, Switch, Table, Tag, Typography, message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined, EditOutlined, EyeOutlined, HistoryOutlined, ReloadOutlined,
  SearchOutlined, UndoOutlined
} from '@ant-design/icons';

import {
  editSkillIdentity, getSkillRun, listSkillRuns, listSkills, removeSkill, restoreSkill,
  updateSkillStatus, type AdminSkillSummary, type SkillRunItem,
  type SkillRunSummary
} from '@/api/aid/skill';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import SkillPackageManager from './SkillPackageManager';

interface IdentityFormValues {
  name: string;
  description?: string;
  capabilityDescription?: string;
  iconUrl?: string;
  status: '0' | '1';
}

function statusTag(status: string, delFlag?: string) {
  if (delFlag === '1') return <Tag color="error">已删除</Tag>;
  return status === '0' ? <Tag color="success">已启用</Tag> : <Tag>已停用</Tag>;
}

/** Skill 稳定身份、不可变版本包和运行审计入口。 */
export default function CommercialSkillPage() {
  const { hasPermi } = useAuth();
  const canList = hasPermi('aid:skill:list');
  const canQuery = hasPermi('aid:skill:query');
  const canEdit = hasPermi('aid:skill:edit');
  const [identityForm] = Form.useForm<IdentityFormValues>();
  const [queryForm] = Form.useForm();
  const [records, setRecords] = useState<AdminSkillSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [query, setQuery] = useState<{ keyword?: string; status?: '0' | '1' }>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [identityOpen, setIdentityOpen] = useState(false);
  const [identitySaving, setIdentitySaving] = useState(false);
  const [editing, setEditing] = useState<AdminSkillSummary>();
  const [packageSkill, setPackageSkill] = useState<AdminSkillSummary>();
  const [actionKeys, setActionKeys] = useState<string[]>([]);
  const [runOpen, setRunOpen] = useState(false);
  const [runs, setRuns] = useState<SkillRunSummary[]>([]);
  const [runTotal, setRunTotal] = useState(0);
  const [runLoading, setRunLoading] = useState(false);
  const [runDetailLoadingId, setRunDetailLoadingId] = useState<number>();
  const [runDetail, setRunDetail] = useState<SkillRunItem>();
  const [runSkillId, setRunSkillId] = useState<number>();
  const [runPageNum, setRunPageNum] = useState(1);
  const [runPageSize, setRunPageSize] = useState(20);
  const listSequence = useRef(0);
  const runListSequence = useRef(0);
  const requestedRunDetailId = useRef<number>();
  const pendingActions = useRef(new Set<string>());

  const load = useCallback(async (force = false) => {
    if (!canList) return;
    const sequence = ++listSequence.current;
    setLoading(true);
    setError('');
    try {
      const response = await listSkills({ pageNum, pageSize, ...query }, { force });
      if (sequence !== listSequence.current) return;
      setRecords(response.data || []);
      setTotal(Number(response.total || 0));
    } catch (cause: any) {
      if (sequence === listSequence.current) setError(cause?.message || 'Skill 列表加载失败');
    } finally {
      if (sequence === listSequence.current) setLoading(false);
    }
  }, [canList, pageNum, pageSize, query]);

  React.useEffect(() => { load(); }, [load]);

  const runAction = async (key: string, operation: () => Promise<void>) => {
    if (pendingActions.current.has(key)) return;
    pendingActions.current.add(key);
    setActionKeys((current) => [...current, key]);
    try {
      await operation();
    } finally {
      pendingActions.current.delete(key);
      setActionKeys((current) => current.filter((item) => item !== key));
    }
  };

  const openIdentity = (record: AdminSkillSummary) => {
    setEditing(record);
    identityForm.setFieldsValue({
      name: record.name,
      description: record.description,
      capabilityDescription: record.capabilityDescription,
      iconUrl: record.iconUrl,
      status: record.status
    });
    setIdentityOpen(true);
  };

  const saveIdentity = async () => {
    if (!editing) return;
    setIdentitySaving(true);
    try {
      const values = await identityForm.validateFields();
      await editSkillIdentity({ id: editing.id, ...values });
      message.success('稳定身份已保存');
      setIdentityOpen(false);
      await load(true);
    } catch (cause: any) {
      message.error(cause?.message || '身份保存失败');
    } finally {
      setIdentitySaving(false);
    }
  };

  const changeStatus = (row: AdminSkillSummary, status: '0' | '1') => runAction(
    `status:${row.id}:${status}`, async () => {
      try {
        await updateSkillStatus(row.id, status);
        message.success(status === '0' ? 'Skill 已启用' : 'Skill 已停用');
        await load(true);
      } catch (cause: any) {
        message.error(cause?.message || '状态更新失败');
      }
    }
  );

  const deleteSkill = (row: AdminSkillSummary) => runAction(`remove:${row.id}`, async () => {
    try {
      await removeSkill(row.id);
      message.success('已软删除');
      await load(true);
    } catch (cause: any) {
      message.error(cause?.message || '删除失败');
    }
  });

  const recoverSkill = (row: AdminSkillSummary) => runAction(`restore:${row.id}`, async () => {
    try {
      await restoreSkill(row.id);
      message.success('已恢复并停用');
      await load(true);
    } catch (cause: any) {
      message.error(cause?.message || '恢复失败');
    }
  });

  const loadRuns = async (skillId?: number, page = 1, size = 20, force = false) => {
    const sequence = ++runListSequence.current;
    setRunLoading(true);
    try {
      const response = await listSkillRuns({ pageNum: page, pageSize: size, skillId }, { force });
      if (sequence !== runListSequence.current) return;
      setRuns(response.data || []);
      setRunTotal(Number(response.total || 0));
    } catch (cause: any) {
      if (sequence === runListSequence.current) message.error(cause?.message || '运行记录加载失败');
    } finally {
      if (sequence === runListSequence.current) setRunLoading(false);
    }
  };

  const openRuns = async (skillId?: number) => {
    setRunOpen(true);
    setRunSkillId(skillId);
    setRunPageNum(1);
    setRunPageSize(20);
    await loadRuns(skillId, 1, 20);
  };

  const showRunDetail = async (id: number) => {
    if (runDetailLoadingId === id) return;
    requestedRunDetailId.current = id;
    setRunDetailLoadingId(id);
    try {
      const response = await getSkillRun(id);
      if (requestedRunDetailId.current !== id) return;
      setRunDetail(response.data);
    } catch (cause: any) {
      if (requestedRunDetailId.current === id) message.error(cause?.message || '运行详情加载失败');
    } finally {
      if (requestedRunDetailId.current === id) setRunDetailLoadingId(undefined);
    }
  };

  const columns: ColumnsType<AdminSkillSummary> = [
    { title: '稳定身份', dataIndex: 'name', render: (_, row) => <Space direction="vertical" size={0}>
      <Typography.Text strong>{row.name}</Typography.Text>
      <Typography.Text type="secondary" copyable>{row.skillCode}</Typography.Text>
    </Space> },
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '--' },
    { title: '能力介绍', dataIndex: 'capabilityDescription', ellipsis: true,
      render: (value) => value || '--' },
    { title: '当前版本 ID', dataIndex: 'currentVersionId', width: 120, render: (value) => value || '--' },
    { title: '当前模型', dataIndex: 'modelCode', width: 180, render: (value) => value || '--' },
    { title: '状态', dataIndex: 'status', width: 90, render: (_, row) => statusTag(row.status, row.delFlag) },
    { title: '更新时间', dataIndex: 'updateTime', width: 170, render: (value) => value || '--' },
    { title: '操作', key: 'action', width: 360, fixed: 'right', render: (_, row) => <Space size={4}>
      {hasPermi('aid:skill:run:list') && <Button type="link" icon={<EyeOutlined />}
        onClick={() => openRuns(row.id)}>运行</Button>}
      {canQuery && row.delFlag === '0' && <Button type="link" icon={<HistoryOutlined />}
        onClick={() => setPackageSkill(row)}>{row.currentVersionId ? '版本' : '版本化'}</Button>}
      {canQuery && <Button type="link" icon={canEdit ? <EditOutlined /> : <EyeOutlined />}
        onClick={() => openIdentity(row)}>身份</Button>}
      {row.delFlag === '0' && canEdit && <Switch checked={row.status === '0'}
        loading={actionKeys.some((key) => key.startsWith(`status:${row.id}:`))}
        checkedChildren="启" unCheckedChildren="停"
        onChange={(checked) => changeStatus(row, checked ? '0' : '1')} />}
      {row.delFlag === '0' && hasPermi('aid:skill:remove') && <Popconfirm
        title="删除后不能发起新运行，历史仍可审计。确认删除？" onConfirm={() => deleteSkill(row)}>
        <Button danger type="link" loading={actionKeys.includes(`remove:${row.id}`)}
          icon={<DeleteOutlined />}>删除</Button>
      </Popconfirm>}
      {row.delFlag === '1' && hasPermi('aid:skill:restore') && <Popconfirm
        title="恢复后保持停用，确认恢复？" onConfirm={() => recoverSkill(row)}>
        <Button type="link" loading={actionKeys.includes(`restore:${row.id}`)}
          icon={<UndoOutlined />}>恢复</Button>
      </Popconfirm>}
    </Space> }
  ];

  return <div>
    <PageHeader title="Skill 管理" />
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        {hasPermi('aid:skill:run:list') && <Button icon={<EyeOutlined />}
          onClick={() => openRuns(undefined)}>全部运行审计</Button>}
        <Form form={queryForm} layout="inline" onFinish={(values) => {
          setQuery({ keyword: values.keyword?.trim(), status: values.status });
          setPageNum(1);
        }}>
          <Form.Item name="keyword"><Input allowClear prefix={<SearchOutlined />}
            placeholder="名称或编码" /></Form.Item>
          <Form.Item name="status"><Select allowClear placeholder="状态" style={{ width: 120 }} options={[
            { label: '已启用', value: '0' }, { label: '已停用', value: '1' }
          ]} /></Form.Item>
          <Button type="primary" htmlType="submit">查询</Button>
        </Form>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(true)}>刷新</Button>
      </Space>
      {error && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={error} />}
      <Table rowKey="id" loading={loading} columns={columns} dataSource={records} scroll={{ x: 1250 }}
        pagination={{ current: pageNum, pageSize, total, showSizeChanger: true,
          onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card>

    <Drawer width={720} title={editing ? `${editing.name} · 稳定身份` : 'Skill 稳定身份'}
      open={identityOpen} onClose={() => setIdentityOpen(false)} destroyOnClose
      extra={editing?.delFlag === '0' && canEdit ? <Button type="primary" loading={identitySaving}
        onClick={saveIdentity}>保存身份</Button> : null}>
      <Alert type="info" showIcon message="身份配置与可执行版本相互独立"
        description="这里维护名称、说明、图标和总开关；Prompt、Schema、资源及路由请在版本包草稿中修改。" />
      {editing && <Descriptions bordered size="small" column={2} style={{ marginTop: 16, marginBottom: 16 }} items={[
        { key: 'code', label: '稳定编码', children: <Typography.Text copyable>{editing.skillCode}</Typography.Text> },
        { key: 'owner', label: '所有者', children: editing.ownerType },
        { key: 'scope', label: '调用范围', children: editing.invocationScope },
        { key: 'visibility', label: '可见范围', children: editing.visibility },
        { key: 'version', label: '当前版本 ID', children: editing.currentVersionId || '--' }
      ]} />}
      <Form form={identityForm} layout="vertical" disabled={editing?.delFlag === '1' || !canEdit}>
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input maxLength={100} /></Form.Item>
        <Form.Item name="description" label="说明"><Input.TextArea rows={4} maxLength={1000} showCount /></Form.Item>
        <Form.Item name="capabilityDescription" label="能力介绍"
          tooltip="直接返回给 C 端 Skill 目录，不使用硬编码回退">
          <Input.TextArea rows={5} maxLength={2000} showCount />
        </Form.Item>
        <Form.Item name="iconUrl" label="图标 URL"><Input maxLength={500} /></Form.Item>
        <Form.Item name="status" label="总开关" rules={[{ required: true }]}><Select options={[
          { label: '启用', value: '0' }, { label: '停用', value: '1' }
        ]} /></Form.Item>
      </Form>
    </Drawer>

    <SkillPackageManager open={!!packageSkill} skill={packageSkill} canEdit={canEdit}
      onClose={() => setPackageSkill(undefined)} onChanged={() => load(true)} />

    <Drawer width={1120} title={`运行记录（${runTotal}）`} open={runOpen}
      onClose={() => { setRunOpen(false); setRunDetail(undefined); }}
      extra={<Button icon={<ReloadOutlined />} loading={runLoading}
        onClick={() => loadRuns(runSkillId, runPageNum, runPageSize, true)}>刷新</Button>}>
      <Table rowKey="id" loading={runLoading} dataSource={runs} scroll={{ x: 1240 }}
        pagination={{ current: runPageNum, pageSize: runPageSize, total: runTotal, showSizeChanger: true,
          onChange: (page, size) => { setRunPageNum(page); setRunPageSize(size); loadRuns(runSkillId, page, size); } }}
        columns={[
          { title: 'Run ID', dataIndex: 'id', width: 90 }, { title: '用户 ID', dataIndex: 'userId', width: 90 },
          { title: '版本 ID', dataIndex: 'skillVersionId', width: 90 },
          { title: '项目/剧集', width: 120, render: (_: unknown, row: SkillRunSummary) =>
            `${row.projectId}/${row.episodeId ?? 0}` },
          { title: '动作', dataIndex: 'actionMode', width: 100, render: (value?: string) => value || '--' },
          { title: '阶段', dataIndex: 'stage', width: 110, render: (value?: string) => value || '--' },
          { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <Tag>{value}</Tag> },
          { title: '模型', dataIndex: 'modelCode', width: 170 },
          { title: '耗时', dataIndex: 'durationMillis', width: 110,
            render: (value?: number) => value == null ? '--' : `${value} ms` },
          { title: '开始', dataIndex: 'startedAt', width: 170 },
          { title: '操作', width: 90, fixed: 'right', render: (_: unknown, row: SkillRunSummary) =>
            hasPermi('aid:skill:run:query') ? <Button type="link"
              loading={runDetailLoadingId === row.id} onClick={() => showRunDetail(row.id)}>详情</Button> : null }
        ]} />
      <Modal width={780} title="Run 详情" open={!!runDetail} footer={null}
        onCancel={() => setRunDetail(undefined)}>
        {runDetail && <Descriptions column={1} bordered size="small" items={[
          { key: 'id', label: 'Run ID', children: runDetail.id },
          { key: 'status', label: '状态', children: runDetail.status },
          { key: 'scope', label: '项目 / 剧集', children:
            `${runDetail.projectId} / ${runDetail.episodeId ?? 0}` },
          { key: 'version', label: 'Skill 版本 ID', children: runDetail.skillVersionId },
          { key: 'mode', label: '动作 / 质量 / 阶段', children:
            `${runDetail.actionMode || '--'} / ${runDetail.qualityMode || '--'} / ${runDetail.stage || '--'}` },
          { key: 'hash', label: '执行配置摘要', children: <Typography.Text copyable>
            {runDetail.skillConfigHash || '--'}</Typography.Text> },
          { key: 'tasks', label: '步骤与计费任务', children: <Table size="small" pagination={false}
            rowKey="stepId" dataSource={runDetail.tasks || []} scroll={{ x: 760 }} columns={[
              { title: '步骤', dataIndex: 'stepKey', width: 120 },
              { title: '动作', dataIndex: 'actionMode', width: 100 },
              { title: '编排状态', dataIndex: 'orchestrationStatus', width: 130 },
              { title: '媒体任务', dataIndex: 'mediaTaskId', width: 100,
                render: (value?: number) => value || '--' },
              { title: '任务状态', dataIndex: 'mediaStatus', width: 110,
                render: (value?: string) => value || '--' },
              { title: '计费状态', dataIndex: 'billingStatus', width: 110,
                render: (value?: string) => value || '--' },
              { title: '实际费用', dataIndex: 'actualCost', width: 100,
                render: (value?: string) => value ?? '--' }
            ]} /> },
          { key: 'error', label: '错误', children: runDetail.errorMessage || '--' },
          { key: 'input', label: '输入 JSON', children: <Typography.Paragraph copyable
            style={{ whiteSpace: 'pre-wrap', maxHeight: 240, overflow: 'auto' }}>
            {runDetail.inputJson || '--'}</Typography.Paragraph> },
          { key: 'output', label: '输出 JSON', children: <Typography.Paragraph copyable
            style={{ whiteSpace: 'pre-wrap', maxHeight: 360, overflow: 'auto' }}>
            {runDetail.outputJson || '--'}</Typography.Paragraph> }
        ]} />}
      </Modal>
    </Drawer>
  </div>;
}
