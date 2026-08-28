import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Col, Descriptions, Dropdown, Form, Input, Modal, Popconfirm, Radio,
  Row, Select, Space, Switch, Table, Tabs, Tag, Tooltip, message
} from 'antd';
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, EyeOutlined, FileTextOutlined,
  FieldTimeOutlined, LockOutlined, MoreOutlined, PlayCircleOutlined, PlusOutlined,
  QuestionCircleOutlined, ReloadOutlined, SearchOutlined, ThunderboltOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  listJob, getJob, addJob, updateJob, delJob, runJob, changeJobStatus,
  getDbPressure, getFrequencyAdvice
} from '@/api/monitor/job';
import { download } from '@/utils/request';
import { useDict } from '@/hooks/useDict';
import DictTag from '@/components/DictTag';
import Auth from '@/components/Auth';
import { parseTime } from '@/utils/ruoyi';
import CronGeneratorModal from './CronGeneratorModal';

/** 任务类型常量：1=固定任务（系统必备，禁止关闭） 2=可选任务（允许开关） */
const JOB_TYPE_FIXED = '1';
const JOB_TYPE_OPTIONAL = '2';

/** 压力级别展示配置 */
const PRESSURE_LEVEL_META: Record<string, { color: string; text: string }> = {
  LOW: { color: '#16a34a', text: '低' },
  MEDIUM: { color: '#d97706', text: '中' },
  HIGH: { color: '#dc2626', text: '高' }
};

/** 秒数转可读文案 */
function formatInterval(seconds?: number | null) {
  if (seconds === undefined || seconds === null || seconds < 0) return '-';
  if (seconds >= 86400) return `${Math.round(seconds / 86400)} 天`;
  if (seconds >= 3600) return `${Math.round(seconds / 3600)} 小时`;
  if (seconds >= 60) return `${Math.round(seconds / 60)} 分钟`;
  return `${seconds} 秒`;
}

export default function JobPage() {
  const [queryForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [activeTab, setActiveTab] = useState<string>('all');
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [saving, setSaving] = useState(false);
  const [detail, setDetail] = useState<any | null>(null);
  const [cronOpen, setCronOpen] = useState(false);
  const [pressure, setPressure] = useState<any | null>(null);
  const [adviceList, setAdviceList] = useState<any[]>([]);
  const navigate = useNavigate();

  const dicts = useDict('sys_job_group', 'sys_job_status');
  const groupDict = dicts['sys_job_group'] || [];
  const statusDict = dicts['sys_job_status'] || [];

  const loadList = async () => {
    setLoading(true);
    try { const res: any = await listJob(query); setList(res.rows || []); setTotal(res.total || 0); }
    finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);

  /** 加载数据库压力 + 动态推荐频率 */
  const loadAdvice = async () => {
    try {
      const [p, a]: any[] = await Promise.all([getDbPressure(), getFrequencyAdvice()]);
      setPressure(p.data || null);
      setAdviceList(a.data || []);
    } catch { /* 推荐频率加载失败不阻塞列表 */ }
  };
  useEffect(() => { loadAdvice(); }, []);

  /** jobId -> 推荐频率建议 */
  const adviceMap = useMemo(() => {
    const m = new Map<number, any>();
    adviceList.forEach((it: any) => { if (it.jobId !== undefined && it.jobId !== null) m.set(it.jobId, it); });
    return m;
  }, [adviceList]);

  /** 切换任务类型 tab：all=全部 fixed=固定任务 optional=可选任务 */
  const onTabChange = (key: string) => {
    setActiveTab(key);
    setSelectedKeys([]);
    const jobType = key === 'fixed' ? JOB_TYPE_FIXED : key === 'optional' ? JOB_TYPE_OPTIONAL : undefined;
    setQuery({ ...query, jobType, pageNum: 1 });
  };

  const isFixed = (row: any) => row?.jobType === JOB_TYPE_FIXED;

  const openAdd = () => {
    editForm.resetFields();
    editForm.setFieldsValue({ status: '1', misfirePolicy: '1', concurrent: '1' });
    setDlg({ open: true, title: '添加任务' });
  };
  const openEdit = async (row: any) => {
    editForm.resetFields();
    const res: any = await getJob(row.jobId);
    const d = res.data || res;
    editForm.setFieldsValue(d);
    setDlg({ open: true, title: isFixed(d) ? '调整固定任务频率' : '修改任务', data: d });
  };
  const handleSave = async () => {
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      if (dlg.data?.jobId) { await updateJob({ ...dlg.data, ...values }); message.success('修改成功'); }
      else { await addJob(values); message.success('新增成功'); }
      setDlg({ open: false, title: '' });
      loadList();
      loadAdvice();
    } finally { setSaving(false); }
  };
  const handleDelete = (row?: any) => {
    const ids = row?.jobId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({ title: '提示', content: `是否确认删除任务编号为 "${ids}" 的数据项？`, okType: 'danger',
      onOk: async () => { await delJob(ids); message.success('删除成功'); setSelectedKeys([]); loadList(); } });
  };
  const handleStatus = async (row: any, checked: boolean) => {
    const status = checked ? '0' : '1';
    await changeJobStatus(row.jobId, status);
    message.success(`${checked ? '启用' : '停用'}成功`);
    loadList();
  };
  const handleRun = (row: any) => {
    Modal.confirm({
      title: '提示', content: `确认要立即执行一次 "${row.jobName}" 任务吗？`,
      onOk: async () => { await runJob(row.jobId, row.jobGroup); message.success('执行成功'); }
    });
  };
  const handleView = async (row: any) => {
    const res: any = await getJob(row.jobId);
    setDetail(res.data || res);
  };

  /** 当前编辑中的固定任务推荐信息 */
  const editingAdvice = dlg.data?.jobId ? adviceMap.get(dlg.data.jobId) : null;
  const editingFixed = isFixed(dlg.data);

  const pressureMeta = PRESSURE_LEVEL_META[pressure?.level] || PRESSURE_LEVEL_META.LOW;

  const tabItems = [
    { key: 'all', label: <span>任务总览</span> },
    { key: 'fixed', label: <span><LockOutlined style={{ marginRight: 4 }} />固定任务</span> },
    { key: 'optional', label: <span>可选任务</span> }
  ];

  return (
    <div className="crud-page">
      <div style={{
        position: 'relative', borderRadius: 16, padding: '22px 26px', marginBottom: 16,
        color: '#fff', overflow: 'hidden',
        background: 'linear-gradient(120deg, #0f172a 0%, #1e293b 45%, #155e75 100%)',
        boxShadow: '0 10px 28px rgba(15,23,42,.18)'
      }}>
        <span aria-hidden style={{ position: 'absolute', width: 220, height: 220, borderRadius: '50%', right: -50, top: -70, background: 'radial-gradient(circle, rgba(34,211,238,.4) 0%, transparent 70%)', filter: 'blur(36px)' }} />
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          <span style={{ width: 56, height: 56, borderRadius: 14, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 26, background: 'linear-gradient(135deg,#22d3ee,#0ea5e9)', boxShadow: '0 8px 20px rgba(14,165,233,.45)' }}>
            <FieldTimeOutlined />
          </span>
          <div style={{ flex: 1, minWidth: 260 }}>
            <div style={{ fontSize: 20, fontWeight: 700 }}>定时任务调度</div>
            <div style={{ color: 'rgba(226,232,240,.8)', fontSize: 13, marginTop: 4 }}>
              固定任务为系统必备（禁止关闭，仅可在安全范围内调整频率），可选任务允许自由开关。推荐频率按数据库承载压力动态计算
            </div>
          </div>
          {pressure && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, background: 'rgba(15,23,42,.45)', borderRadius: 12, padding: '10px 16px' }}>
              <ThunderboltOutlined style={{ fontSize: 20, color: pressureMeta.color }} />
              <div style={{ fontSize: 12, lineHeight: 1.7 }}>
                <div>数据库压力：<span style={{ color: pressureMeta.color, fontWeight: 700 }}>{pressureMeta.text}</span>{pressure.available === false && <span style={{ color: '#94a3b8' }}>（采集失败，按低压力兜底）</span>}</div>
                <div style={{ color: 'rgba(226,232,240,.75)' }}>
                  连接池 {pressure.activeCount ?? '-'}/{pressure.maxActive ?? '-'}（{pressure.poolUsagePercent ?? 0}%）
                  {pressure.waitThreadCount > 0 && <span> · 等待线程 {pressure.waitThreadCount}</span>}
                </div>
              </div>
              <Button size="small" ghost icon={<ReloadOutlined />} onClick={loadAdvice}>刷新</Button>
            </div>
          )}
        </div>
      </div>
      <Card className="page-card" bordered={false}>
        <Form form={queryForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="jobName" label="任务名称"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="jobGroup" label="任务组名"><Select allowClear style={{ width: 160 }} options={groupDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 140 }} options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item><Space>
            <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
            <Button onClick={() => { queryForm.resetFields(); setActiveTab('all'); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
          </Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false} style={{ marginTop: 16 }}>
        <Tabs activeKey={activeTab} onChange={onTabChange} items={tabItems} />
        {activeTab === 'fixed' && (
          <Alert type="warning" showIcon style={{ marginBottom: 12 }}
            message="固定任务是业务系统必备任务（任务调度、计费补偿、支付兜底、僵尸回收等），停用会导致任务卡死、余额冻结、支付漏单等数据错误，因此禁止暂停和删除，仅允许在安全频率范围内调整执行频率。" />
        )}
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <Auth permission="monitor:job:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
          <Auth permission="monitor:job:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
          <Auth permission="monitor:job:export"><Button icon={<DownloadOutlined />} onClick={() => download('/monitor/job/export', query, `job_${Date.now()}.xlsx`)}>导出</Button></Auth>
          <Auth permission="monitor:job:query"><Button icon={<FileTextOutlined />} onClick={() => navigate('/monitor/job-log/index/0')}>日志</Button></Auth>
        </div>
        <Table rowKey="jobId" size="small" loading={loading} dataSource={list} scroll={{ x: 1600 }}
          rowSelection={{
            selectedRowKeys: selectedKeys, onChange: setSelectedKeys,
            getCheckboxProps: (r: any) => ({ disabled: isFixed(r) })
          }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          columns={[
            { title: '任务编号', dataIndex: 'jobId', width: 90 },
            { title: '任务名称', dataIndex: 'jobName', width: 170, ellipsis: true, render: (v: string, r: any) => (
              <Space size={6}>
                <span style={{ fontWeight: 500 }}>{v}</span>
              </Space>
            ) },
            { title: '任务类型', dataIndex: 'jobType', width: 100, render: (v: string) => (
              v === JOB_TYPE_FIXED
                ? <Tag color="volcano" icon={<LockOutlined />}>固定任务</Tag>
                : <Tag color="blue">可选任务</Tag>
            ) },
            { title: '任务组名', dataIndex: 'jobGroup', width: 100, render: (v: any) => <DictTag options={groupDict} value={v} /> },
            { title: '调用目标字符串', dataIndex: 'invokeTarget', width: 230, ellipsis: true, render: (v: string) => <code style={{ background: '#f1f5f9', color: '#475569', padding: '2px 8px', borderRadius: 6, fontSize: 12, fontFamily: 'Consolas, Menlo, monospace' }}>{v}</code> },
            { title: 'cron表达式', dataIndex: 'cronExpression', width: 150, render: (v: string) => <code style={{ color: '#0e7490', fontFamily: 'Consolas, Menlo, monospace' }}>{v}</code> },
            { title: '推荐频率', key: 'advice', width: 170, render: (_: any, r: any) => {
              const ad = adviceMap.get(r.jobId);
              if (!ad) return <span style={{ color: '#cbd5e1' }}>-</span>;
              const same = ad.recommendedCron === r.cronExpression;
              return (
                <Tooltip title={ad.daily ? '按日任务，频率固定不参与动态计算' : `按当前数据库压力计算：约每 ${formatInterval(ad.recommendedIntervalSeconds)} 一次${ad.minIntervalSeconds > 0 || ad.maxIntervalSeconds > 0 ? `（安全范围 ${formatInterval(ad.minIntervalSeconds)} ~ ${formatInterval(ad.maxIntervalSeconds)}）` : ''}`}>
                  <span>
                    <code style={{ color: same ? '#16a34a' : '#d97706', fontFamily: 'Consolas, Menlo, monospace' }}>{ad.recommendedCron}</code>
                    {!same && <Tag color="orange" style={{ marginLeft: 6 }}>可优化</Tag>}
                  </span>
                </Tooltip>
              );
            } },
            { title: '状态', dataIndex: 'status', width: 120, render: (v: string, r: any) => (
              isFixed(r)
                ? <Tooltip title="固定任务为系统必备，禁止关闭"><Space size={6}><Switch size="small" checked disabled /><span style={{ color: '#16a34a', fontSize: 12 }}>运行中</span></Space></Tooltip>
                : <Space size={6}><Switch size="small" checked={v === '0'} onChange={(c) => handleStatus(r, c)} /><span style={{ color: v === '0' ? '#16a34a' : '#94a3b8', fontSize: 12 }}>{v === '0' ? '运行中' : '已暂停'}</span></Space>
            ) },
            { title: '操作', key: 'ops', width: 220, fixed: 'right' as const, render: (_: any, r: any) => (
              <Space size={0}>
                <Auth permission="monitor:job:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>{isFixed(r) ? '调频' : '修改'}</Button></Auth>
                {!isFixed(r) && (
                  <Auth permission="monitor:job:remove"><Popconfirm title="确认删除？" onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
                )}
                <Dropdown menu={{
                  items: [
                    { key: 'run', label: '执行一次', icon: <PlayCircleOutlined />, onClick: () => handleRun(r) },
                    { key: 'view', label: '任务详细', icon: <EyeOutlined />, onClick: () => handleView(r) },
                    { key: 'log', label: '调度日志', icon: <FileTextOutlined />, onClick: () => navigate(`/monitor/job-log/index/${r.jobId}`) }
                  ]
                }}>
                  <Button type="link" size="small" icon={<MoreOutlined />}>更多</Button>
                </Dropdown>
              </Space>
            ) }
          ]}
        />
      </Card>

      {/* 编辑弹窗 */}
      <Modal open={dlg.open} title={dlg.title} onCancel={() => setDlg({ open: false, title: '' })} onOk={handleSave} confirmLoading={saving} width={760} destroyOnClose maskClosable={false}>
        {editingFixed && (
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message="固定任务仅允许调整执行频率（cron 表达式），且必须落在安全频率范围内；名称、调用目标、状态均由系统管理。"
            description={editingAdvice ? (
              <span>
                当前推荐频率：<code style={{ color: '#0e7490' }}>{editingAdvice.recommendedCron}</code>
                （约每 {formatInterval(editingAdvice.recommendedIntervalSeconds)} 一次，
                安全范围 {formatInterval(editingAdvice.minIntervalSeconds)} ~ {formatInterval(editingAdvice.maxIntervalSeconds)}）
                <Button type="link" size="small" onClick={() => editForm.setFieldsValue({ cronExpression: editingAdvice.recommendedCron })}>应用推荐值</Button>
              </span>
            ) : undefined} />
        )}
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="jobName" label="任务名称" rules={[{ required: true }]}><Input disabled={editingFixed} /></Form.Item></Col>
            <Col span={12}><Form.Item name="jobGroup" label="任务分组" rules={[{ required: true }]}><Select disabled={editingFixed} options={groupDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item></Col>
            <Col span={24}>
              <Form.Item name="invokeTarget" label={<span>调用方法 <Tooltip title="Bean调用示例：ryTask.ryParams('ry')。Class调用示例：com.ruoyi.quartz.task.RyTask.ryParams('ry')"><QuestionCircleOutlined style={{ color: '#94a3b8' }} /></Tooltip></span>} rules={[{ required: true }]}>
                <Input placeholder="请输入调用目标字符串" disabled={editingFixed} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="cronExpression" label="cron表达式" rules={[{ required: true }]}>
                <Input
                  placeholder="请输入 cron 执行表达式，如 0 0 12 * * ?"
                  addonAfter={
                    <span style={{ cursor: 'pointer', color: '#2563eb' }} onClick={() => setCronOpen(true)}>
                      <FieldTimeOutlined /> 生成表达式
                    </span>
                  }
                />
              </Form.Item>
            </Col>
            {dlg.data?.jobId !== undefined && !editingFixed && (
              <Col span={24}><Form.Item name="status" label="状态"><Radio.Group>{statusDict.map((d: any) => <Radio key={d.value} value={d.value}>{d.label}</Radio>)}</Radio.Group></Form.Item></Col>
            )}
            <Col span={12}>
              <Form.Item name="misfirePolicy" label="执行策略" rules={[{ required: true }]}>
                <Radio.Group disabled={editingFixed}><Radio.Button value="1">立即执行</Radio.Button><Radio.Button value="2">执行一次</Radio.Button><Radio.Button value="3">放弃执行</Radio.Button></Radio.Group>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="concurrent" label="是否并发" rules={[{ required: true }]}>
                <Radio.Group disabled={editingFixed}><Radio.Button value="0">允许</Radio.Button><Radio.Button value="1">禁止</Radio.Button></Radio.Group>
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      {/* 任务详细弹窗 */}
      <Modal open={!!detail} title="任务详细" onCancel={() => setDetail(null)} footer={<Button onClick={() => setDetail(null)}>关闭</Button>} width={700}>
        {detail && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="任务编号">{detail.jobId}</Descriptions.Item>
            <Descriptions.Item label="任务名称">{detail.jobName}</Descriptions.Item>
            <Descriptions.Item label="任务类型">
              {detail.jobType === JOB_TYPE_FIXED
                ? <Tag color="volcano" icon={<LockOutlined />}>固定任务（禁止关闭）</Tag>
                : <Tag color="blue">可选任务</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="任务分组"><DictTag options={groupDict} value={detail.jobGroup} /></Descriptions.Item>
            <Descriptions.Item label="创建时间">{parseTime(detail.createTime)}</Descriptions.Item>
            <Descriptions.Item label="下次执行时间">{parseTime(detail.nextValidTime)}</Descriptions.Item>
            <Descriptions.Item label="cron表达式">{detail.cronExpression}</Descriptions.Item>
            <Descriptions.Item label="任务状态">{detail.status === '0' ? '正常' : '暂停'}</Descriptions.Item>
            <Descriptions.Item label="调用目标方法" span={2}>{detail.invokeTarget}</Descriptions.Item>
            <Descriptions.Item label="是否并发">{detail.concurrent === '0' ? '允许' : '禁止'}</Descriptions.Item>
            <Descriptions.Item label="执行策略">
              {detail.misfirePolicy === '0' ? '默认策略' :
               detail.misfirePolicy === '1' ? '立即执行' :
               detail.misfirePolicy === '2' ? '执行一次' :
               detail.misfirePolicy === '3' ? '放弃执行' : '-'}
            </Descriptions.Item>
            {detail.remark && <Descriptions.Item label="任务说明" span={2}>{detail.remark}</Descriptions.Item>}
          </Descriptions>
        )}
      </Modal>

      {/* Cron 表达式生成器弹窗 */}
      <CronGeneratorModal
        open={cronOpen}
        value={editForm.getFieldValue('cronExpression') || ''}
        onOk={(expr: string) => {
          editForm.setFieldsValue({ cronExpression: expr });
          setCronOpen(false);
        }}
        onCancel={() => setCronOpen(false)}
      />
    </div>
  );
}
