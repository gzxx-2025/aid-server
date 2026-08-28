import React, { useEffect, useMemo, useState } from 'react';
import { Button, Card, Form, Input, Popconfirm, Select, Space, Switch, Table, Tabs, Tag, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import {
  listVoiceTag, getVoiceTag, addVoiceTag, updateVoiceTag, delVoiceTag
} from '@/api/aid/voicelibrary';
import Auth from '@/components/Auth';
import { Modal } from 'antd';

const TAG_TYPES = [
  { label: '角色类型', value: 'character_type', icon: '👤', color: 'default' },
  { label: '使用场景', value: 'voice_style', icon: '🎬', color: 'orange' },
  { label: '音调', value: 'tone', icon: '🎵', color: 'green' }
];

export default function VoiceTagPage() {
  const [activeType, setActiveType] = useState('character_type');
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [searchForm] = Form.useForm();
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [editForm] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const activeTypeInfo = TAG_TYPES.find((t) => t.value === activeType)!;

  const loadList = async () => {
    setLoading(true);
    try {
      const res: any = await listVoiceTag({ ...query, tagType: activeType });
      setList(res.rows || res.data || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query, activeType]);

  const handleAdd = () => {
    editForm.resetFields();
    editForm.setFieldsValue({ tagType: activeType, status: '0', sortOrder: 0 });
    setDlg({ open: true, title: `新增 ${activeTypeInfo.label} 标签` });
  };
  const handleEdit = async (row: any) => {
    const res: any = await getVoiceTag(row.id);
    editForm.resetFields();
    editForm.setFieldsValue(res.data || res);
    setDlg({ open: true, title: `修改 ${activeTypeInfo.label} 标签`, data: res.data || res });
  };
  const handleSave = async () => {
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      if (values.id) { await updateVoiceTag(values); message.success('已更新'); }
      else { await addVoiceTag({ ...values, tagType: activeType }); message.success('已新增'); }
      setDlg({ open: false, title: '' });
      loadList();
    } finally { setSaving(false); }
  };
  const handleDelete = async (ids: any) => {
    await delVoiceTag(ids);
    message.success('删除成功');
    loadList();
  };
  const handleStatusSwitch = async (row: any, checked: boolean) => {
    await updateVoiceTag({ ...row, status: checked ? '0' : '1' });
    message.success(checked ? '已启用' : '已停用');
    loadList();
  };

  const columns: any[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'tagCode', width: 180, render: (v: string) => <code style={{ background: '#f5f7fa', padding: '2px 6px', borderRadius: 4, fontSize: 12 }}>{v}</code> },
    { title: '名称', dataIndex: 'tagName', width: 180, render: (v: string) => <Tag color={activeTypeInfo.color === 'default' ? undefined : activeTypeInfo.color}>{v}</Tag> },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string, r: any) => <Switch checked={v === '0'} onChange={(c) => handleStatusSwitch(r, c)} /> },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '更新时间', dataIndex: 'updateTime', width: 160 },
    { title: '操作', key: 'ops', width: 140, fixed: 'right' as const, render: (_: any, r: any) => (
      <Space size={0}>
        <Auth permission="aid:voice-tag:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(r)}>修改</Button></Auth>
        <Auth permission="aid:voice-tag:remove"><Popconfirm title="确认删除？" onConfirm={() => handleDelete(r.id)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
      </Space>
    ) }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Tabs
          activeKey={activeType}
          onChange={(k) => { setActiveType(k); setQuery({ ...query, pageNum: 1 }); searchForm.resetFields(); }}
          type="card"
          items={TAG_TYPES.map((t) => ({ key: t.value, label: <span>{t.icon} {t.label}</span> }))}
        />
        <Form form={searchForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8, marginTop: 8 }}>
          <Form.Item name="tagCode" label="编码"><Input allowClear placeholder="编码关键字" style={{ width: 160 }} /></Form.Item>
          <Form.Item name="tagName" label="名称"><Input allowClear placeholder="名称关键字" style={{ width: 180 }} /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 110 }} options={[{ label: '启用', value: '0' }, { label: '停用', value: '1' }]} /></Form.Item>
          <Form.Item><Space><Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button><Button onClick={() => { searchForm.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button></Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false}>
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <Auth permission="aid:voice-tag:add"><Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>新增</Button></Auth>
          <Auth permission="aid:voice-tag:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete(selectedKeys.join(','))}>批量删除</Button></Auth>
        </div>
        <Table rowKey="id" size="small" loading={loading} dataSource={list} columns={columns} scroll={{ x: 1000 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
        />
      </Card>
      <Modal open={dlg.open} title={dlg.title} onCancel={() => setDlg({ open: false, title: '' })} onOk={handleSave} confirmLoading={saving} width={560} destroyOnClose>
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Form.Item name="tagCode" label="编码" rules={[{ required: true }]}><Input placeholder="如 young_female" /></Form.Item>
          <Form.Item name="tagName" label="名称" rules={[{ required: true }]}><Input placeholder="如 少女音" /></Form.Item>
          <Form.Item name="sortOrder" label="排序"><Input type="number" /></Form.Item>
          <Form.Item name="status" label="状态"><Select options={[{ label: '启用', value: '0' }, { label: '停用', value: '1' }]} /></Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
