import React, { useEffect, useState } from 'react';
import {
  Button, Card, Checkbox, DatePicker, Dropdown, Form, Input, InputNumber, Modal,
  Popconfirm, Radio, Select, Space, Switch, Table, Tag, Tooltip, Tree, message
} from 'antd';
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, MoreOutlined, PlusOutlined,
  QuestionCircleOutlined, SafetyOutlined, SearchOutlined, UserOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  listRole, getRole, addRole, updateRole, delRole, dataScope as saveDataScope,
  changeRoleStatus, deptTreeSelect
} from '@/api/system/role';
import { treeselect as menuTreeselect, roleMenuTreeselect } from '@/api/system/menu';
import { download } from '@/utils/request';
import { useDict } from '@/hooks/useDict';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import { parseTime } from '@/utils/ruoyi';
import './style.less';

const DATA_SCOPE = [
  { label: '全部数据权限', value: '1' },
  { label: '自定数据权限', value: '2' },
  { label: '本部门数据权限', value: '3' },
  { label: '本部门及以下数据权限', value: '4' },
  { label: '仅本人数据权限', value: '5' }
];

function getAllKeys(nodes: any[]): any[] {
  const keys: any[] = [];
  const walk = (list: any[]) => list.forEach((n) => {
    // 跳过禁用节点
    if (!n.disabled) keys.push(n.id);
    if (n.children) walk(n.children);
  });
  walk(nodes || []);
  return keys;
}

/** 内嵌于「组织与权限」合并页时隐藏页头，避免与整页标题叠加 */
export default function RoleManagePage({ embedded = false }: { embedded?: boolean }) {
  const [queryForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [dsForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [dateRange, setDateRange] = useState<any>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [dsDlg, setDsDlg] = useState<{ open: boolean; data?: any }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [menuOptions, setMenuOptions] = useState<any[]>([]);
  const [deptOptions, setDeptOptions] = useState<any[]>([]);
  const [checkedMenus, setCheckedMenus] = useState<React.Key[]>([]);
  const [checkedDepts, setCheckedDepts] = useState<React.Key[]>([]);
  const [menuExpanded, setMenuExpanded] = useState<React.Key[]>([]);
  const [deptExpanded, setDeptExpanded] = useState<React.Key[]>([]);
  const [menuStrictly, setMenuStrictly] = useState(true);
  const [deptStrictly, setDeptStrictly] = useState(true);
  const [currentDataScope, setCurrentDataScope] = useState('1');

  const navigate = useNavigate();
  const dicts = useDict('sys_normal_disable');
  const statusDict = dicts['sys_normal_disable'] || [];

  const loadList = async () => {
    setLoading(true);
    try {
      const params: any = { ...query };
      if (dateRange?.length === 2) {
        params.params = {
          beginTime: dateRange[0]?.format('YYYY-MM-DD'),
          endTime: dateRange[1]?.format('YYYY-MM-DD')
        };
      }
      const res: any = await listRole(params);
      setList(res.rows || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);

  const handleStatusSwitch = async (row: any, checked: boolean) => {
    const status = checked ? '0' : '1';
    const text = checked ? '启用' : '停用';
    Modal.confirm({
      title: '提示',
      content: `确认要"${text}""${row.roleName}"角色吗？`,
      onOk: async () => {
        await changeRoleStatus(row.roleId, status);
        message.success(text + '成功');
        loadList();
      }
    });
  };

  const openAdd = async () => {
    editForm.resetFields();
    const res: any = await menuTreeselect();
    setMenuOptions(res.data || []);
    setCheckedMenus([]);
    setMenuStrictly(true);
    setMenuExpanded([]);
    editForm.setFieldsValue({ status: '0', roleSort: 0, menuCheckStrictly: true });
    setDlg({ open: true, title: '添加角色' });
  };

  const openEdit = async (row: any) => {
    editForm.resetFields();
    const [menuRes, detailRes, checkedRes]: any[] = await Promise.all([
      menuTreeselect(), getRole(row.roleId), roleMenuTreeselect(row.roleId)
    ]);
    setMenuOptions(menuRes.data || []);
    const data = detailRes.data || detailRes;
    setMenuStrictly(!!data.menuCheckStrictly);
    setCheckedMenus(checkedRes.checkedKeys || []);
    setMenuExpanded([]);
    setDlg({ open: true, title: '修改角色', data });
    setTimeout(() => editForm.setFieldsValue({ ...data, menuCheckStrictly: !!data.menuCheckStrictly }), 0);
  };

  const handleSave = async () => {
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      const payload = { ...(dlg.data || {}), ...values, menuIds: checkedMenus };
      if (dlg.data?.roleId) {
        await updateRole(payload);
        message.success('修改成功');
      } else {
        await addRole(payload);
        message.success('新增成功');
      }
      setDlg({ open: false, title: '' });
      loadList();
    } finally { setSaving(false); }
  };

  const handleDelete = async (row?: any) => {
    const ids = row?.roleId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({
      title: '提示',
      content: `是否确认删除角色编号为 "${ids}" 的数据项？`,
      okType: 'danger',
      onOk: async () => {
        await delRole(ids);
        message.success('删除成功');
        setSelectedKeys([]);
        loadList();
      }
    });
  };

  const openDataScope = async (row: any) => {
    const [deptRes, detailRes]: any[] = await Promise.all([deptTreeSelect(row.roleId), getRole(row.roleId)]);
    setDeptOptions(deptRes.data || []);
    setCheckedDepts(deptRes.checkedKeys || []);
    const d = detailRes.data || detailRes;
    setDeptStrictly(!!d.deptCheckStrictly);
    setCurrentDataScope(d.dataScope || '1');
    dsForm.setFieldsValue({ roleName: d.roleName, roleKey: d.roleKey, dataScope: d.dataScope || '1', deptCheckStrictly: !!d.deptCheckStrictly });
    setDeptExpanded([]);
    setDsDlg({ open: true, data: d });
  };

  const handleDataScopeSave = async () => {
    const values = await dsForm.validateFields();
    setSaving(true);
    try {
      await saveDataScope({ ...(dsDlg.data || {}), ...values, deptIds: checkedDepts });
      message.success('保存成功');
      setDsDlg({ open: false });
      loadList();
    } finally { setSaving(false); }
  };

  const handleAuthUser = (row: any) => {
    navigate(`/system/role-auth/user/${row.roleId}`);
  };

  const toTreeData = (list: any[]): any[] => (list || []).map((n) => ({ title: n.label, key: n.id, children: n.children ? toTreeData(n.children) : undefined }));

  const columns: any[] = [
    { title: '角色编号', dataIndex: 'roleId', width: 100 },
    { title: '角色名称', dataIndex: 'roleName', width: 160, ellipsis: true },
    { title: '权限字符', dataIndex: 'roleKey', width: 160, ellipsis: true },
    { title: '显示顺序', dataIndex: 'roleSort', width: 100 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string, r: any) => <Switch checked={v === '0'} disabled={r.admin} onChange={(c) => handleStatusSwitch(r, c)} /> },
    { title: '创建时间', dataIndex: 'createTime', width: 180, render: (v: string) => parseTime(v) },
    {
      title: '操作', key: 'ops', width: 260, fixed: 'right' as const,
      render: (_: any, r: any) => r.admin ? null : (
        <Space size={0}>
          <Auth permission="system:role:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>修改</Button></Auth>
          <Auth permission="system:role:remove"><Popconfirm title={`确认删除 ${r.roleName}？`} onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
          <Dropdown menu={{
            items: [
              { key: 'ds', icon: <SafetyOutlined />, label: '数据权限', onClick: () => openDataScope(r) },
              { key: 'au', icon: <UserOutlined />, label: '分配用户', onClick: () => handleAuthUser(r) }
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
        <PageHeader title="角色管理" desc="维护角色及其菜单权限与数据权限范围" />
      )}
      <Card className="page-card" bordered={false}>
        <Form form={queryForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="roleName" label="角色名称"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="roleKey" label="权限字符"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 160 }} options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item label="创建时间"><DatePicker.RangePicker value={dateRange} onChange={setDateRange as any} /></Form.Item>
          <Form.Item><Space>
            <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
            <Button onClick={() => { queryForm.resetFields(); setDateRange(null); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
          </Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false} style={{ marginTop: 16 }}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="system:role:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
            <Auth permission="system:role:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
            <Auth permission="system:role:export"><Button icon={<DownloadOutlined />} onClick={() => download('/system/role/export', query, `role_${Date.now()}.xlsx`)}>导出</Button></Auth>
          </Space>
          <div className="crud-page__stats">
            {selectedKeys.length > 0 && <Tag color="blue">已选 {selectedKeys.length}</Tag>}
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table rowKey="roleId" size="small" loading={loading} dataSource={list} columns={columns} scroll={{ x: 1200 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys, getCheckboxProps: (r) => ({ disabled: r.admin }) }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
        />
      </Card>

      {/* 新增/修改弹窗 */}
      <Modal open={dlg.open} title={dlg.title} onCancel={() => setDlg({ open: false, title: '' })} onOk={handleSave} confirmLoading={saving} width={560} destroyOnClose maskClosable={false}>
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="roleName" label="角色名称" rules={[{ required: true, max: 30 }]}><Input /></Form.Item>
          <Form.Item name="roleKey" label={<span>权限字符 <Tooltip title="控制器中定义的权限字符，如：@PreAuthorize(`@ss.hasRole('admin')`)"><QuestionCircleOutlined style={{ color: '#94a3b8' }} /></Tooltip></span>} rules={[{ required: true, max: 100 }]}><Input /></Form.Item>
          <Form.Item name="roleSort" label="角色顺序" rules={[{ required: true }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="status" label="状态"><Radio.Group>{statusDict.map((d: any) => <Radio key={d.value} value={d.value}>{d.label}</Radio>)}</Radio.Group></Form.Item>
          <Form.Item label="菜单权限">
            <Space style={{ marginBottom: 8 }}>
              <Checkbox onChange={(e) => setMenuExpanded(e.target.checked ? getAllKeys(menuOptions) : [])}>展开/折叠</Checkbox>
              <Checkbox onChange={(e) => setCheckedMenus(e.target.checked ? getAllKeys(menuOptions) : [])}>全选/全不选</Checkbox>
              <Checkbox checked={menuStrictly} onChange={(e) => { setMenuStrictly(e.target.checked); editForm.setFieldValue('menuCheckStrictly', e.target.checked); }}>父子联动</Checkbox>
            </Space>
            <div className="tree-box">
              <Tree
                checkable
                checkStrictly={!menuStrictly}
                treeData={toTreeData(menuOptions)}
                checkedKeys={checkedMenus}
                onCheck={(keys: any) => setCheckedMenus(Array.isArray(keys) ? keys : keys.checked)}
                expandedKeys={menuExpanded}
                onExpand={setMenuExpanded}
              />
            </div>
          </Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      {/* 数据权限弹窗 */}
      <Modal open={dsDlg.open} title="分配数据权限" onCancel={() => setDsDlg({ open: false })} onOk={handleDataScopeSave} confirmLoading={saving} width={560} destroyOnClose maskClosable={false}>
        <Form form={dsForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="roleName" label="角色名称"><Input disabled /></Form.Item>
          <Form.Item name="roleKey" label="权限字符"><Input disabled /></Form.Item>
          <Form.Item name="dataScope" label="权限范围"><Select options={DATA_SCOPE} onChange={setCurrentDataScope} /></Form.Item>
          {currentDataScope === '2' && (
            <Form.Item label="数据权限">
              <Space style={{ marginBottom: 8 }}>
                <Checkbox onChange={(e) => setDeptExpanded(e.target.checked ? getAllKeys(deptOptions) : [])}>展开/折叠</Checkbox>
                <Checkbox onChange={(e) => setCheckedDepts(e.target.checked ? getAllKeys(deptOptions) : [])}>全选/全不选</Checkbox>
                <Checkbox checked={deptStrictly} onChange={(e) => setDeptStrictly(e.target.checked)}>父子联动</Checkbox>
              </Space>
              <div className="tree-box">
                <Tree
                  checkable
                  checkStrictly={!deptStrictly}
                  treeData={toTreeData(deptOptions)}
                  checkedKeys={checkedDepts}
                  onCheck={(keys: any) => setCheckedDepts(Array.isArray(keys) ? keys : keys.checked)}
                  expandedKeys={deptExpanded}
                  onExpand={setDeptExpanded}
                  defaultExpandAll
                />
              </div>
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
