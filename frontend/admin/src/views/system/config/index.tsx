import React, { useEffect, useState } from 'react';
import {
  Button, Card, Col, DatePicker, Form, Input, Modal, Popconfirm, Radio, Row,
  Select, Space, Table, Tag, Tooltip, message
} from 'antd';
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, PlusOutlined,
  ReloadOutlined, SearchOutlined, AppstoreOutlined,
  SafetyCertificateOutlined, EyeInvisibleOutlined, ThunderboltOutlined,
  ClockCircleOutlined, FileTextOutlined, SettingOutlined
} from '@ant-design/icons';
import { listConfig, getConfig, addConfig, updateConfig, delConfig, refreshCache } from '@/api/system/config';
import { download } from '@/utils/request';
import { useDict } from '@/hooks/useDict';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import StatCard from '@/components/StatCard';
import { parseTime } from '@/utils/ruoyi';

// 与后端 SC7 同源：key 含这些模式时视为敏感配置，前端列表不展示明文
function isSensitiveKey(key?: string): boolean {
  if (!key) return false;
  const k = key.toLowerCase();
  return k.includes('password')
    || k.includes('secret')
    || k.includes('apikey')
    || k.includes('api_key')
    || k.includes('token')
    || k.includes('accesskey');
}

/** 内嵌于「字典与参数」合并页时隐藏页头，避免与整页标题叠加 */
export default function ConfigPage({ embedded = false }: { embedded?: boolean }) {
  const [queryForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [dateRange, setDateRange] = useState<any>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [saving, setSaving] = useState(false);
  const dicts = useDict('sys_yes_no');
  const yesNoDict = dicts['sys_yes_no'] || [];

  const loadList = async () => {
    setLoading(true);
    try {
      const params: any = { ...query };
      if (dateRange?.length === 2) {
        params.params = { beginTime: dateRange[0]?.format('YYYY-MM-DD'), endTime: dateRange[1]?.format('YYYY-MM-DD') };
      }
      const res: any = await listConfig(params);
      setList(res.rows || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);

  const openAdd = () => {
    editForm.resetFields();
    editForm.setFieldsValue({ configType: 'N' });
    setDlg({ open: true, title: '添加参数' });
  };
  const openEdit = async (row: any) => {
    editForm.resetFields();
    const res: any = await getConfig(row.configId);
    editForm.setFieldsValue(res.data || res);
    setDlg({ open: true, title: '修改参数', data: res.data || res });
  };
  const handleSave = async () => {
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      if (dlg.data?.configId) {
        await updateConfig({ ...dlg.data, ...values });
        message.success('修改成功');
      } else {
        await addConfig(values);
        message.success('新增成功');
      }
      setDlg({ open: false, title: '' });
      loadList();
    } finally { setSaving(false); }
  };
  const handleDelete = (row?: any) => {
    const ids = row?.configId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({
      title: '提示', content: `是否确认删除参数编号为 "${ids}" 的数据项？`, okType: 'danger',
      onOk: async () => { await delConfig(ids); message.success('删除成功'); setSelectedKeys([]); loadList(); }
    });
  };

  const builtinCount = list.filter((r) => r.configType === 'Y').length;
  const sensitiveCount = list.filter((r) => isSensitiveKey(r.configKey)).length;
  const customCount = list.length - builtinCount;

  // 统计卡：统一 StatCard 组件，语义色区分
  const statCards = [
    { key: 'total', label: '参数总数', value: total, icon: <AppstoreOutlined />, color: '#2563eb' },
    { key: 'builtin', label: '系统内置', value: builtinCount, icon: <SafetyCertificateOutlined />, color: '#0ea5e9' },
    { key: 'custom', label: '业务参数', value: customCount, icon: <ThunderboltOutlined />, color: '#10b981' },
    { key: 'sensitive', label: '敏感配置', value: sensitiveCount, icon: <EyeInvisibleOutlined />, color: '#f59e0b' }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        {!embedded && (
          <PageHeader
            title={<><SettingOutlined />系统参数配置</>}
            desc="维护系统运行所需的全局参数与业务配置，敏感配置值自动脱敏展示"
          />
        )}
        {/* 统计卡 */}
        <Row gutter={[14, 14]}>
          {statCards.map((s) => (
            <Col xs={12} md={6} key={s.key}>
              <StatCard label={s.label} value={s.value} icon={s.icon} color={s.color} />
            </Col>
          ))}
        </Row>
      </Card>

      {/* 搜索区 */}
      <Card className="page-card" bordered={false} style={{ marginTop: 14 }}>
        <Form form={queryForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="configName" label="参数名称">
            <Input allowClear style={{ width: 200 }} placeholder="请输入参数名称" prefix={<FileTextOutlined style={{ color: '#94a3b8' }} />} />
          </Form.Item>
          <Form.Item name="configKey" label="参数键名">
            <Input allowClear style={{ width: 200 }} placeholder="请输入参数键名" />
          </Form.Item>
          <Form.Item name="configType" label="系统内置">
            <Select allowClear style={{ width: 140 }} placeholder="请选择" options={yesNoDict.map((d: any) => ({ label: d.label, value: d.value }))} />
          </Form.Item>
          <Form.Item label="创建时间">
            <DatePicker.RangePicker value={dateRange} onChange={setDateRange as any} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
              <Button icon={<ReloadOutlined />} onClick={() => { queryForm.resetFields(); setDateRange(null); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {/* 表格区 */}
      <Card className="page-card" bordered={false} style={{ marginTop: 14 }}>
        <div style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 10,
          marginBottom: 14,
          flexWrap: 'wrap'
        }}>
          <Space wrap>
            <Auth permission="system:config:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
            <Auth permission="system:config:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
            <Auth permission="system:config:export"><Button icon={<DownloadOutlined />} onClick={() => download('/system/config/export', query, `config_${Date.now()}.xlsx`)}>导出</Button></Auth>
            <Auth permission="system:config:remove">
              <Tooltip title="清空并重新加载后端参数缓存">
                <Button icon={<ThunderboltOutlined />} onClick={async () => { await refreshCache(); message.success('刷新缓存成功'); }}>刷新缓存</Button>
              </Tooltip>
            </Auth>
          </Space>
          <div style={{ color: '#64748b', fontSize: 13, display: 'flex', alignItems: 'center', gap: 10 }}>
            {selectedKeys.length > 0 && <Tag color="blue" style={{ borderRadius: 6 }}>已选 {selectedKeys.length}</Tag>}
            <span>共 <b style={{ color: '#1f2937' }}>{total}</b> 条</span>
          </div>
        </div>

        <Table
          rowKey="configId"
          size="middle"
          loading={loading}
          dataSource={list}
          scroll={{ x: 1200 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          columns={[
            { title: '参数主键', dataIndex: 'configId', width: 100, align: 'center' as const,
              render: (v: any) => <span style={{ color: '#64748b', fontFamily: 'Consolas, Menlo, monospace' }}>#{v}</span> },
            { title: '参数名称', dataIndex: 'configName', width: 220, ellipsis: true,
              render: (v: string) => <span style={{ fontWeight: 500, color: '#1f2937' }}>{v}</span> },
            { title: '参数键名', dataIndex: 'configKey', width: 240, ellipsis: true,
              render: (v: string) => (
                <code style={{
                  background: '#f1f5f9',
                  color: '#475569',
                  padding: '2px 8px',
                  borderRadius: 6,
                  fontSize: 12,
                  fontFamily: 'Consolas, Menlo, monospace'
                }}>{v}</code>
              ) },
            { title: '参数键值', dataIndex: 'configValue', ellipsis: true, width: 260,
              render: (v: string, r: any) => {
                if (isSensitiveKey(r?.configKey)) {
                  return (
                    <Tag
                      icon={<EyeInvisibleOutlined />}
                      style={{
                        borderRadius: 6,
                        background: 'linear-gradient(135deg, #fef3c7 0%, #fde68a 100%)',
                        border: '1px solid #fbbf24',
                        color: '#92400e',
                        fontWeight: 500
                      }}
                    >敏感值已隐藏</Tag>
                  );
                }
                const text = String(v ?? '');
                if (!text) return <span style={{ color: '#94a3b8' }}>-</span>;
                return <span title={text} style={{ display: 'inline-block', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: '#334155' }}>{text}</span>;
              } },
            { title: '系统内置', dataIndex: 'configType', width: 110, align: 'center' as const,
              render: (v: string) => {
                const hit = yesNoDict.find((d: any) => d.value === v);
                const label = hit?.label || v;
                if (v === 'Y') {
                  return (
                    <Tag
                      icon={<SafetyCertificateOutlined />}
                      style={{
                        borderRadius: 6,
                        background: '#eff6ff',
                        border: '1px solid #bfdbfe',
                        color: '#1d4ed8',
                        fontWeight: 500
                      }}
                    >{label}</Tag>
                  );
                }
                return (
                  <Tag style={{ borderRadius: 6, background: '#f1f5f9', border: '1px solid #e2e8f0', color: '#475569' }}>
                    {label}
                  </Tag>
                );
              } },
            { title: '备注', dataIndex: 'remark', ellipsis: true,
              render: (v: string) => v ? <span style={{ color: '#64748b' }}>{v}</span> : <span style={{ color: '#cbd5e1' }}>-</span> },
            { title: '创建时间', dataIndex: 'createTime', width: 180,
              render: (v: string) => (
                <span style={{ color: '#64748b', fontSize: 12.5, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                  <ClockCircleOutlined style={{ color: '#cbd5e1' }} />
                  {parseTime(v)}
                </span>
              ) },
            { title: '操作', key: 'ops', width: 140, fixed: 'right' as const, align: 'center' as const, render: (_: any, r: any) => (
              <Space size={0}>
                <Auth permission="system:config:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>修改</Button></Auth>
                <Auth permission="system:config:remove"><Popconfirm title="确认删除？" onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
              </Space>
            ) }
          ]}
        />
      </Card>

      <Modal
        open={dlg.open}
        title={<Space><SettingOutlined style={{ color: '#6366f1' }} /><span>{dlg.title}</span></Space>}
        onCancel={() => setDlg({ open: false, title: '' })}
        onOk={handleSave}
        confirmLoading={saving}
        width={600}
        destroyOnClose
        maskClosable={false}
      >
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="configName" label="参数名称" rules={[{ required: true, max: 100, message: '请输入参数名称' }]}>
                <Input placeholder="例如：用户管理-账号初始密码" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="configKey" label="参数键名" rules={[{ required: true, max: 100, message: '请输入参数键名' }]}>
                <Input placeholder="例如：sys.user.initPassword" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="configValue" label="参数键值" rules={[{ required: true, message: '请输入参数键值' }]}>
                <Input.TextArea autoSize={{ minRows: 3, maxRows: 8 }} placeholder="请输入参数键值" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="configType" label="系统内置" extra="系统内置参数请谨慎修改，可能影响系统正常运行">
                <Radio.Group>
                  {yesNoDict.map((d: any) => <Radio key={d.value} value={d.value}>{d.label}</Radio>)}
                </Radio.Group>
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="remark" label="备注">
                <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder="请输入备注说明" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
