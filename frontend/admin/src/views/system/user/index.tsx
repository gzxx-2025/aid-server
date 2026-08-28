import React, { useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Col, DatePicker, Dropdown, Form, Input, Modal, Popconfirm,
  Radio, Row, Select, Space, Switch, Table, Tag, Tree, TreeSelect, Upload, message
} from 'antd';
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, KeyOutlined,
  MoreOutlined, PlusOutlined, SearchOutlined, SolutionOutlined, UploadOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';

import {
  listUser, getUser, addUser, updateUser, delUser,
  resetUserPwd, changeUserStatus, deptTreeSelect
} from '@/api/system/user';
import { download, request } from '@/utils/request';
import { getToken } from '@/utils/auth';
import { useDict } from '@/hooks/useDict';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import { parseTime } from '@/utils/ruoyi';

function toAntdTree(nodes: any[]): any[] {
  return (nodes || []).map((n) => ({
    title: n.label,
    key: n.id,
    disabled: n.disabled,
    children: n.children ? toAntdTree(n.children) : undefined
  }));
}

function filterDisabledDept(nodes: any[]): any[] {
  return (nodes || []).filter((d) => {
    if (d.disabled) return false;
    if (d.children?.length) d.children = filterDisabledDept(d.children);
    return true;
  });
}

/** 内嵌于「组织与权限」合并页时隐藏页头，避免与整页标题叠加 */
export default function UserManagePage({ embedded = false }: { embedded?: boolean }) {
  const [queryForm] = Form.useForm();
  const [userList, setUserList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [deptOptions, setDeptOptions] = useState<any[]>([]);
  const [enabledDeptOptions, setEnabledDeptOptions] = useState<any[]>([]);
  const [deptName, setDeptName] = useState('');
  const [selectedDept, setSelectedDept] = useState<any>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dateRange, setDateRange] = useState<any>(null);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [editForm] = Form.useForm();
  const [postOptions, setPostOptions] = useState<any[]>([]);
  const [roleOptions, setRoleOptions] = useState<any[]>([]);
  const [saving, setSaving] = useState(false);
  const [resetPwdOpen, setResetPwdOpen] = useState(false);
  const [resetPwdTarget, setResetPwdTarget] = useState<any>(null);
  const [newPwd, setNewPwd] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  const [updateSupport, setUpdateSupport] = useState(false);
  const navigate = useNavigate();

  const dicts = useDict('sys_normal_disable', 'sys_user_sex');
  const statusDict = dicts['sys_normal_disable'] || [];
  const sexDict = dicts['sys_user_sex'] || [];

  const loadDeptTree = async () => {
    const res: any = await deptTreeSelect();
    const raw = res.data || [];
    setDeptOptions(raw);
    const enabled = filterDisabledDept(JSON.parse(JSON.stringify(raw)));
    setEnabledDeptOptions(enabled);
  };

  const loadList = async () => {
    setLoading(true);
    try {
      const params: any = { ...query };
      if (dateRange && dateRange.length === 2) {
        params.params = {
          beginTime: dateRange[0]?.format('YYYY-MM-DD'),
          endTime: dateRange[1]?.format('YYYY-MM-DD')
        };
      }
      const res: any = await listUser(params);
      setUserList(res.rows || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };

  useEffect(() => {
    loadDeptTree();
    // 原 `getConfigKey('sys.user.initPassword')` 预填已移除：
    //   - 后端 SC7 修复会对包含 password 的 key 返回 `******`，预填结果是占位符；
    //   - 即使不脱敏，把统一的默认密码展示到 UI 也是审计 FE9 指出的风险（便于旁观者偷看）。
    // 改为新增用户时必须手动设置密码。
  }, []);
  useEffect(() => { loadList(); }, [query]);

  const filteredTreeData = useMemo(() => {
    const tree = toAntdTree(deptOptions);
    if (!deptName) return tree;
    const filter = (nodes: any[]): any[] => nodes.reduce((acc: any[], n: any) => {
      const match = String(n.title || '').includes(deptName);
      const children = n.children ? filter(n.children) : [];
      if (match || children.length) acc.push({ ...n, children });
      return acc;
    }, []);
    return filter(tree);
  }, [deptOptions, deptName]);

  const handleStatusSwitch = async (row: any, checked: boolean) => {
    const status = checked ? '0' : '1';
    const text = checked ? '启用' : '停用';
    Modal.confirm({
      title: '提示',
      content: `确认要"${text}""${row.userName}"用户吗？`,
      onOk: async () => {
        await changeUserStatus(row.userId, status);
        message.success(text + '成功');
        loadList();
      }
    });
  };

  const openAdd = async () => {
    editForm.resetFields();
    const res: any = await getUser('');
    setPostOptions(res.posts || []);
    setRoleOptions(res.roles || []);
    editForm.setFieldsValue({ status: '0', postIds: [], roleIds: [] });
    setDlg({ open: true, title: '添加用户' });
  };

  const openEdit = async (row: any) => {
    editForm.resetFields();
    const res: any = await getUser(row.userId);
    setPostOptions(res.posts || []);
    setRoleOptions(res.roles || []);
    const data = { ...(res.data || {}), postIds: res.postIds || [], roleIds: res.roleIds || [], password: '' };
    setDlg({ open: true, title: '修改用户', data });
    setTimeout(() => editForm.setFieldsValue(data), 0);
  };

  const handleSave = async () => {
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      if (dlg.data?.userId) {
        await updateUser({ ...dlg.data, ...values });
        message.success('修改成功');
      } else {
        await addUser(values);
        message.success('新增成功');
      }
      setDlg({ open: false, title: '' });
      loadList();
    } finally { setSaving(false); }
  };

  const handleDelete = async (row?: any) => {
    const ids = row?.userId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({
      title: '提示',
      content: `是否确认删除用户编号为 "${ids}" 的数据项？`,
      okType: 'danger',
      onOk: async () => {
        await delUser(ids);
        message.success('删除成功');
        setSelectedKeys([]);
        loadList();
      }
    });
  };

  const handleExport = () => {
    download('/system/user/export', query, `user_${Date.now()}.xlsx`);
  };
  const importTemplate = () => {
    download('/system/user/importTemplate', {}, `user_template_${Date.now()}.xlsx`);
  };

  const openResetPwd = (row: any) => {
    setResetPwdTarget(row);
    setNewPwd('');
    setResetPwdOpen(true);
  };
  const doResetPwd = async () => {
    if (!newPwd || newPwd.length < 5 || newPwd.length > 20) {
      message.error('用户密码长度必须介于 5 和 20 之间');
      return;
    }
    if (/<|>|"|'|\||\\/.test(newPwd)) {
      message.error('不能包含非法字符：< > " \' \\ |');
      return;
    }
    if (!/(?=.*[a-zA-Z])(?=.*\d)/.test(newPwd)) {
      message.error('密码必须包含字母和数字');
      return;
    }
    await resetUserPwd(resetPwdTarget.userId, newPwd);
    message.success('密码重置成功');
    setResetPwdOpen(false);
  };

  const handleAuthRole = (row: any) => {
    navigate(`/system/user-auth/role/${row.userId}`);
  };

  const columns: any[] = [
    { title: '用户编号', dataIndex: 'userId', width: 100 },
    { title: '用户名称', dataIndex: 'userName', width: 120, ellipsis: true },
    { title: '用户昵称', dataIndex: 'nickName', width: 140, ellipsis: true },
    { title: '部门', dataIndex: ['dept', 'deptName'], width: 140, ellipsis: true },
    { title: '手机号码', dataIndex: 'phonenumber', width: 130 },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string, r: any) => <Switch checked={v === '0'} disabled={r.admin} onChange={(c) => handleStatusSwitch(r, c)} /> },
    { title: '创建时间', dataIndex: 'createTime', width: 160, render: (v: string) => parseTime(v) },
    {
      title: '操作', key: 'ops', width: 220, fixed: 'right' as const,
      render: (_: any, r: any) => r.admin ? null : (
        <Space size={0}>
          <Auth permission="system:user:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>修改</Button></Auth>
          <Auth permission="system:user:remove"><Popconfirm title={`确认删除用户 ${r.userName}？`} onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
          <Dropdown menu={{
            items: [
              { key: 'pwd', label: '重置密码', icon: <KeyOutlined />, onClick: () => openResetPwd(r) },
              { key: 'role', label: '分配角色', icon: <SolutionOutlined />, onClick: () => handleAuthRole(r) }
            ]
          }}>
            <Button type="link" size="small" icon={<MoreOutlined />}>更多</Button>
          </Dropdown>
        </Space>
      )
    }
  ];

  return (
    <div className="crud-page">
      {!embedded && (
        <PageHeader title="用户管理" desc="维护系统用户账号，支持按部门筛选、角色分配与导入导出" />
      )}
      <Row gutter={16}>
      <Col flex="240px">
        <Card className="page-card" bordered={false} title="部门" size="small">
          <Input
            placeholder="请输入部门名称"
            allowClear
            prefix={<SearchOutlined />}
            value={deptName}
            onChange={(e) => setDeptName(e.target.value)}
            style={{ marginBottom: 12 }}
          />
          <Tree
            blockNode
            defaultExpandAll
            treeData={filteredTreeData}
            selectedKeys={selectedDept ? [selectedDept] : []}
            onSelect={(keys) => {
              const k = keys[0];
              setSelectedDept(k);
              setQuery({ ...query, deptId: k, pageNum: 1 });
            }}
          />
        </Card>
      </Col>
      <Col flex="auto" style={{ minWidth: 0 }}>
        <Card className="page-card" bordered={false}>
          <Form form={queryForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
            <Form.Item name="userName" label="用户名称"><Input allowClear style={{ width: 200 }} placeholder="请输入用户名称" /></Form.Item>
            <Form.Item name="phonenumber" label="手机号"><Input allowClear style={{ width: 200 }} placeholder="请输入手机号" /></Form.Item>
            <Form.Item name="status" label="状态"><Select allowClear style={{ width: 140 }} placeholder="用户状态" options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
            <Form.Item label="创建时间"><DatePicker.RangePicker value={dateRange} onChange={setDateRange as any} /></Form.Item>
            <Form.Item><Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
              <Button onClick={() => { queryForm.resetFields(); setDateRange(null); setSelectedDept(null); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
            </Space></Form.Item>
          </Form>
        </Card>
        <Card className="page-card" bordered={false} style={{ marginTop: 16 }}>
          <div className="crud-page__toolbar">
            <Space>
              <Auth permission="system:user:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
              <Auth permission="system:user:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
              <Auth permission="system:user:import"><Button icon={<UploadOutlined />} onClick={() => setImportOpen(true)}>导入</Button></Auth>
              <Auth permission="system:user:export"><Button icon={<DownloadOutlined />} onClick={handleExport}>导出</Button></Auth>
            </Space>
            <div className="crud-page__stats">
              {selectedKeys.length > 0 && <Tag color="blue">已选 {selectedKeys.length}</Tag>}
              <span>共 {total} 条</span>
            </div>
          </div>
          <Table rowKey="userId" size="small" loading={loading} dataSource={userList} columns={columns} scroll={{ x: 1200 }}
            rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys, getCheckboxProps: (r) => ({ disabled: r.admin }) }}
            pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          />
        </Card>
      </Col>

      {/* 新增/编辑弹窗 */}
      <Modal open={dlg.open} title={dlg.title} onCancel={() => setDlg({ open: false, title: '' })} onOk={handleSave} confirmLoading={saving} width={700} destroyOnClose maskClosable={false}>
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="nickName" label="用户昵称" rules={[{ required: true }, { max: 30 }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="deptId" label="归属部门"><DeptTreeSelect data={enabledDeptOptions} /></Form.Item></Col>
            <Col span={12}><Form.Item name="phonenumber" label="手机号码" rules={[{ pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号' }]}><Input maxLength={11} /></Form.Item></Col>
            <Col span={12}><Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入正确的邮箱' }]}><Input maxLength={50} /></Form.Item></Col>
            {!dlg.data?.userId && <Col span={12}><Form.Item name="userName" label="用户名称" rules={[{ required: true, min: 2, max: 20 }]}><Input /></Form.Item></Col>}
            {!dlg.data?.userId && <Col span={12}><Form.Item name="password" label="用户密码" rules={[{ required: true, min: 5, max: 20 }]}><Input.Password /></Form.Item></Col>}
            <Col span={12}><Form.Item name="sex" label="用户性别"><Select allowClear options={sexDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item></Col>
            <Col span={12}><Form.Item name="status" label="状态"><Radio.Group>{statusDict.map((d: any) => <Radio key={d.value} value={d.value}>{d.label}</Radio>)}</Radio.Group></Form.Item></Col>
            <Col span={12}><Form.Item name="postIds" label="岗位"><Select mode="multiple" options={postOptions.map((p) => ({ label: p.postName, value: p.postId, disabled: p.status === '1' }))} /></Form.Item></Col>
            <Col span={12}><Form.Item name="roleIds" label="角色"><Select mode="multiple" options={roleOptions.map((r) => ({ label: r.roleName, value: r.roleId, disabled: r.status === '1' }))} /></Form.Item></Col>
            <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item></Col>
          </Row>
        </Form>
      </Modal>

      {/* 重置密码弹窗 */}
      <Modal open={resetPwdOpen} title={`重置 ${resetPwdTarget?.userName} 的密码`} onCancel={() => setResetPwdOpen(false)} onOk={doResetPwd} width={400}>
        <Input.Password placeholder="请输入新密码（5-20位）" value={newPwd} onChange={(e) => setNewPwd(e.target.value)} />
      </Modal>

      {/* 导入弹窗 */}
      <Modal open={importOpen} title="用户导入" onCancel={() => setImportOpen(false)} footer={null} width={460}>
        <Upload.Dragger
          accept=".xlsx,.xls"
          action={`${import.meta.env.VITE_APP_BASE_API}/system/user/importData?updateSupport=${updateSupport ? 1 : 0}`}
          headers={{ Authorization: getToken() ? `Bearer ${getToken()}` : '' }}
          beforeUpload={(file) => {
            if (updateSupport) {
              return new Promise<File | false>((resolve) => {
                Modal.confirm({
                  title: '二次确认',
                  content: '已开启"更新已存在用户"，导入将覆盖现有用户数据，确认继续？',
                  okType: 'danger',
                  onOk: () => resolve(file),
                  // 返回 false 阻止上传（antd 推荐方式，不会产生 unhandled rejection）
                  onCancel: () => resolve(false)
                });
              });
            }
            return true;
          }}
          onChange={(info) => {
            if (info.file.status === 'done') {
              message.success('导入成功');
              setImportOpen(false);
              loadList();
            } else if (info.file.status === 'error') {
              message.error('导入失败');
            }
          }}
        >
          <p><UploadOutlined style={{ fontSize: 36, color: '#94a3b8' }} /></p>
          <p>将文件拖到此处，或点击上传</p>
        </Upload.Dragger>
        <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Switch size="small" checked={updateSupport} onChange={setUpdateSupport} />
          <span style={{ fontSize: 13 }}>是否更新已经存在的用户数据</span>
          <a style={{ marginLeft: 'auto', fontSize: 12 }} onClick={importTemplate}>下载模板</a>
        </div>
        <div style={{ marginTop: 8, color: '#94a3b8', fontSize: 12 }}>仅允许导入 xls / xlsx 格式文件</div>
      </Modal>
      </Row>
    </div>
  );
}

function DeptTreeSelect({ value, onChange, data }: { value?: any; onChange?: (v: any) => void; data: any[] }) {
  const toOpts = (list: any[]): any[] => list.map((n: any) => ({ title: n.label, value: n.id, key: n.id, children: n.children ? toOpts(n.children) : undefined }));
  return (
    <TreeSelect
      value={value}
      onChange={onChange}
      treeData={toOpts(data)}
      placeholder="请选择归属部门"
      treeDefaultExpandAll
      showSearch
      treeNodeFilterProp="title"
      style={{ width: '100%' }}
    />
  );
}
