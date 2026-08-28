import React, { useEffect, useState } from 'react';
import { Button, Card, Form, Input, Modal, Space, Table, Tag, message } from 'antd';
import { ArrowLeftOutlined, CloseCircleOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  allocatedUserList, unallocatedUserList,
  authUserCancel, authUserCancelAll, authUserSelectAll
} from '@/api/system/role';
import { useDict } from '@/hooks/useDict';
import DictTag from '@/components/DictTag';
import Auth from '@/components/Auth';
import { parseTime } from '@/utils/ruoyi';

export default function RoleAuthUserPage() {
  const { roleId } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [selectOpen, setSelectOpen] = useState(false);

  const dicts = useDict('sys_normal_disable');
  const statusDict = dicts['sys_normal_disable'] || [];

  const loadList = async () => {
    setLoading(true);
    try {
      const res: any = await allocatedUserList({ ...query, roleId });
      setList(res.rows || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { if (roleId) loadList(); }, [query, roleId]);

  const cancelAuth = (row: any) => {
    Modal.confirm({
      title: '提示',
      content: `确认要取消该用户"${row.userName}"角色吗？`,
      onOk: async () => {
        await authUserCancel({ userId: row.userId, roleId });
        message.success('取消授权成功');
        loadList();
      }
    });
  };

  const batchCancel = () => {
    if (!selectedKeys.length) return;
    Modal.confirm({
      title: '提示',
      content: '是否取消选中用户授权数据项？',
      okType: 'danger',
      onOk: async () => {
        await authUserCancelAll({ roleId, userIds: selectedKeys.join(',') });
        message.success('取消授权成功');
        setSelectedKeys([]);
        loadList();
      }
    });
  };

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false} title={
        <Space><Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/system/role')}>返回</Button>已授权用户</Space>
      }>
        <Form form={form} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8, marginBottom: 12 }}>
          <Form.Item name="userName" label="用户名称"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="phonenumber" label="手机号码"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item><Space>
            <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
            <Button onClick={() => { form.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
          </Space></Form.Item>
        </Form>

        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <Auth permission="system:role:add"><Button type="primary" icon={<PlusOutlined />} onClick={() => setSelectOpen(true)}>添加用户</Button></Auth>
          <Auth permission="system:role:remove"><Button danger disabled={!selectedKeys.length} icon={<CloseCircleOutlined />} onClick={batchCancel}>批量取消授权</Button></Auth>
        </div>

        <Table
          rowKey="userId"
          size="middle"
          loading={loading}
          dataSource={list}
          scroll={{ x: 1000 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          columns={[
            { title: '用户名称', dataIndex: 'userName', ellipsis: true },
            { title: '用户昵称', dataIndex: 'nickName', ellipsis: true },
            { title: '邮箱', dataIndex: 'email', ellipsis: true },
            { title: '手机', dataIndex: 'phonenumber', width: 140 },
            { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <DictTag options={statusDict} value={v} /> },
            { title: '创建时间', dataIndex: 'createTime', width: 180, render: (v: string) => parseTime(v) },
            {
              title: '操作', key: 'ops', width: 120,
              render: (_: any, r: any) => (
                <Auth permission="system:role:remove">
                  <Button type="link" size="small" danger icon={<CloseCircleOutlined />} onClick={() => cancelAuth(r)}>取消授权</Button>
                </Auth>
              )
            }
          ]}
        />
      </Card>

      <SelectUserModal
        roleId={roleId}
        open={selectOpen}
        onCancel={() => setSelectOpen(false)}
        onOk={() => { setSelectOpen(false); loadList(); }}
      />
    </div>
  );
}

/** 内嵌：未分配用户选择弹窗 */
function SelectUserModal({ roleId, open, onCancel, onOk }: { roleId: any; open: boolean; onCancel: () => void; onOk: () => void }) {
  const [form] = Form.useForm();
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await unallocatedUserList({ ...query, roleId });
      setList(res.rows || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { if (open && roleId) load(); }, [open, query, roleId]);

  const handleOk = async () => {
    if (!selectedKeys.length) { message.warning('请选择要分配的用户'); return; }
    setSaving(true);
    try {
      await authUserSelectAll({ roleId, userIds: selectedKeys.join(',') });
      message.success('分配成功');
      setSelectedKeys([]);
      onOk();
    } finally { setSaving(false); }
  };

  return (
    <Modal open={open} title="选择用户" onCancel={onCancel} onOk={handleOk} confirmLoading={saving} width={860} destroyOnClose>
      <Form form={form} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8, marginBottom: 12 }}>
        <Form.Item name="userName" label="用户名称"><Input allowClear style={{ width: 160 }} /></Form.Item>
        <Form.Item name="phonenumber" label="手机号"><Input allowClear style={{ width: 160 }} /></Form.Item>
        <Form.Item><Space>
          <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
          <Button onClick={() => { form.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
        </Space></Form.Item>
      </Form>
      <Table
        rowKey="userId"
        size="small"
        loading={loading}
        dataSource={list}
        rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
        pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
        columns={[
          { title: '用户名称', dataIndex: 'userName', width: 140 },
          { title: '用户昵称', dataIndex: 'nickName', width: 140 },
          { title: '手机号', dataIndex: 'phonenumber', width: 140 },
          { title: '状态', dataIndex: 'status', width: 80, render: (v: string) => v === '0' ? <Tag color="success">正常</Tag> : <Tag color="red">停用</Tag> }
        ]}
      />
    </Modal>
  );
}
