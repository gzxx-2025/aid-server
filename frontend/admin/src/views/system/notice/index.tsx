import React, { useEffect, useState } from 'react';
import { Button, Card, Col, Form, Input, Modal, Popconfirm, Radio, Row, Select, Space, Table, Tag, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { listNotice, getNotice, addNotice, updateNotice, delNotice } from '@/api/system/notice';
import { useDict } from '@/hooks/useDict';
import DictTag from '@/components/DictTag';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import RichTextEditor from '@/components/RichTextEditor';
import { parseTime } from '@/utils/ruoyi';

export default function NoticePage() {
  const [queryForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [saving, setSaving] = useState(false);

  const dicts = useDict('sys_notice_type', 'sys_notice_status');
  const typeDict = dicts['sys_notice_type'] || [];
  const statusDict = dicts['sys_notice_status'] || [];

  const loadList = async () => {
    setLoading(true);
    try { const res: any = await listNotice(query); setList(res.rows || []); setTotal(res.total || 0); }
    finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);

  const openAdd = () => {
    editForm.resetFields();
    editForm.setFieldsValue({ status: '0' });
    setDlg({ open: true, title: '添加公告' });
  };
  const openEdit = async (row: any) => {
    editForm.resetFields();
    const res: any = await getNotice(row.noticeId);
    editForm.setFieldsValue(res.data || res);
    setDlg({ open: true, title: '修改公告', data: res.data || res });
  };
  const handleSave = async () => {
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      if (dlg.data?.noticeId) { await updateNotice({ ...dlg.data, ...values }); message.success('修改成功'); }
      else { await addNotice(values); message.success('新增成功'); }
      setDlg({ open: false, title: '' });
      loadList();
    } finally { setSaving(false); }
  };
  const handleDelete = (row?: any) => {
    const ids = row?.noticeId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({
      title: '提示', content: `是否确认删除公告编号为 "${ids}" 的数据项？`, okType: 'danger',
      onOk: async () => { await delNotice(ids); message.success('删除成功'); setSelectedKeys([]); loadList(); }
    });
  };

  return (
    <div className="crud-page">
      <PageHeader title="系统公告" desc="发布系统公告与通知，支持富文本排版" />
      <Card className="page-card" bordered={false}>
        <Form form={queryForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="noticeTitle" label="公告标题"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="createBy" label="操作人员"><Input allowClear style={{ width: 160 }} /></Form.Item>
          <Form.Item name="noticeType" label="类型"><Select allowClear style={{ width: 140 }} options={typeDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item><Space>
            <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
            <Button onClick={() => { queryForm.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
          </Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="system:notice:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
            <Auth permission="system:notice:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
          </Space>
          <div className="crud-page__stats">
            {selectedKeys.length > 0 && <Tag color="blue">已选 {selectedKeys.length}</Tag>}
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table rowKey="noticeId" size="middle" loading={loading} dataSource={list}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          columns={[
            { title: '序号', dataIndex: 'noticeId', width: 100 },
            { title: '公告标题', dataIndex: 'noticeTitle', ellipsis: true },
            { title: '公告类型', dataIndex: 'noticeType', width: 120, render: (v: string) => <DictTag options={typeDict} value={v} /> },
            { title: '状态', dataIndex: 'status', width: 110, render: (v: string) => <DictTag options={statusDict} value={v} /> },
            { title: '创建者', dataIndex: 'createBy', width: 120 },
            { title: '创建时间', dataIndex: 'createTime', width: 160, render: (v: string) => parseTime(v, 'YYYY-MM-DD') },
            { title: '操作', key: 'ops', width: 140, fixed: 'right' as const, render: (_: any, r: any) => (
              <Space size={0}>
                <Auth permission="system:notice:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>修改</Button></Auth>
                <Auth permission="system:notice:remove"><Popconfirm title="确认删除？" onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
              </Space>
            ) }
          ]}
        />
      </Card>

      <Modal open={dlg.open} title={dlg.title} onCancel={() => setDlg({ open: false, title: '' })} onOk={handleSave} confirmLoading={saving} width={820} destroyOnClose maskClosable={false}>
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="noticeTitle" label="公告标题" rules={[{ required: true, max: 50 }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="noticeType" label="公告类型" rules={[{ required: true }]}><Select options={typeDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item></Col>
            <Col span={24}><Form.Item name="status" label="状态"><Radio.Group optionType="button" buttonStyle="solid">{statusDict.map((d: any) => <Radio key={d.value} value={d.value}>{d.label}</Radio>)}</Radio.Group></Form.Item></Col>
            <Col span={24}>
              <Form.Item name="noticeContent" label="公告内容" rules={[{ required: true, message: '请输入公告内容' }]}>
                <RichTextEditor placeholder="请输入公告内容，支持富文本排版" height={300} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
