import React, { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Radio,
  Row,
  Select,
  Space,
  Table,
  TreeSelect,
  message
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  RedoOutlined,
  SearchOutlined,
  SwapOutlined
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';

import {
  listDept,
  getDept,
  addDept,
  updateDept,
  delDept,
  listDeptExcludeChild
} from '@/api/system/dept';
import Auth from '@/components/Auth';
import { handleTree } from '@/utils/ruoyi';

interface DeptRow {
  deptId: number;
  parentId: number;
  deptName: string;
  orderNum: number;
  leader?: string;
  phone?: string;
  email?: string;
  status: string;
  createTime?: string;
  children?: DeptRow[];
}

export default function DeptManagePage() {
  const [queryForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [rows, setRows] = useState<DeptRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [treeOptions, setTreeOptions] = useState<any[]>([]);
  const [dialog, setDialog] = useState<{ open: boolean; row?: Partial<DeptRow>; title: string }>({
    open: false,
    title: ''
  });
  const [submitting, setSubmitting] = useState(false);
  const [expandAll, setExpandAll] = useState(true);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

  const collectKeys = (list: DeptRow[]): React.Key[] => {
    const keys: React.Key[] = [];
    const walk = (nodes: DeptRow[]) => {
      nodes.forEach((n) => {
        keys.push(n.deptId);
        if (n.children?.length) walk(n.children);
      });
    };
    walk(list);
    return keys;
  };

  const fetch = async () => {
    setLoading(true);
    try {
      const res: any = await listDept(queryForm.getFieldsValue());
      const list = res.data || [];
      const tree = handleTree<DeptRow>(list, 'deptId');
      setRows(tree);
      if (expandAll) setExpandedKeys(collectKeys(tree));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toTreeOpts = (list: any[]): any[] =>
    list.map((n) => ({
      title: n.deptName,
      value: n.deptId,
      key: n.deptId,
      children: n.children ? toTreeOpts(n.children) : undefined
    }));

  const openAdd = (parent?: DeptRow) => {
    editForm.resetFields();
    editForm.setFieldsValue({ parentId: parent?.deptId ?? 0, status: '0', orderNum: 0 });
    listDept({}).then((res: any) => {
      const tree = handleTree(res.data || [], 'deptId');
      setTreeOptions([{ title: '主类目', value: 0, key: 0, children: toTreeOpts(tree) }]);
    });
    setDialog({ open: true, row: {}, title: '新增部门' });
  };

  const openEdit = async (row: DeptRow) => {
    editForm.resetFields();
    const res: any = await getDept(row.deptId);
    const data = res.data || res;
    const treeRes: any = await listDeptExcludeChild(row.deptId);
    const tree = handleTree(treeRes.data || [], 'deptId');
    setTreeOptions([{ title: '主类目', value: 0, key: 0, children: toTreeOpts(tree) }]);
    setDialog({ open: true, row: data, title: '编辑部门' });
    setTimeout(() => editForm.setFieldsValue(data), 0);
  };

  const handleSubmit = async () => {
    const values = await editForm.validateFields();
    const isEdit = !!dialog.row?.deptId;
    setSubmitting(true);
    try {
      if (isEdit) {
        await updateDept({ ...dialog.row, ...values });
        message.success('修改成功');
      } else {
        await addDept(values);
        message.success('新增成功');
      }
      setDialog({ open: false, title: '' });
      fetch();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (row: DeptRow) => {
    await delDept(row.deptId);
    message.success('删除成功');
    fetch();
  };

  const columns: ColumnsType<DeptRow> = [
    { title: '部门名称', dataIndex: 'deptName', width: 240 },
    { title: '排序', dataIndex: 'orderNum', width: 100, align: 'center' },
    { title: '负责人', dataIndex: 'leader', width: 120 },
    { title: '联系电话', dataIndex: 'phone', width: 140 },
    { title: '邮箱', dataIndex: 'email', width: 180, ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v) => (v === '0' ? '正常' : '停用')
    },
    { title: '创建时间', dataIndex: 'createTime', width: 160 },
    {
      title: '操作',
      key: '__ops__',
      width: 220,
      fixed: 'right',
      render: (_: any, row: DeptRow) => (
        <Space size={0} wrap>
          <Auth permission="system:dept:edit">
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
              编辑
            </Button>
          </Auth>
          <Auth permission="system:dept:add">
            <Button type="link" size="small" icon={<PlusOutlined />} onClick={() => openAdd(row)}>
              新增
            </Button>
          </Auth>
          <Auth permission="system:dept:remove">
            <Popconfirm title={`确认删除部门 ${row.deptName} 吗？`} onConfirm={() => handleDelete(row)}>
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          </Auth>
        </Space>
      )
    }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Form form={queryForm} layout="inline" onFinish={fetch}>
          <Form.Item name="deptName" label="部门名称">
            <Input placeholder="请输入" allowClear style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              style={{ width: 140 }}
              options={[
                { label: '正常', value: '0' },
                { label: '停用', value: '1' }
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">
                搜索
              </Button>
              <Button
                icon={<RedoOutlined />}
                onClick={() => {
                  queryForm.resetFields();
                  fetch();
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="system:dept:add">
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openAdd()}>
                新增
              </Button>
            </Auth>
            <Button
              icon={<SwapOutlined />}
              onClick={() => {
                const next = !expandAll;
                setExpandAll(next);
                setExpandedKeys(next ? collectKeys(rows) : []);
              }}
            >
              展开/折叠
            </Button>
          </Space>
        </div>
        <Table<DeptRow>
          rowKey="deptId"
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={false}
          scroll={{ x: 'max-content' }}
          expandable={{
            expandedRowKeys: expandedKeys,
            onExpandedRowsChange: (keys) => setExpandedKeys([...keys])
          }}
        />
      </Card>

      <Modal
        open={dialog.open}
        title={dialog.title}
        onCancel={() => setDialog({ open: false, title: '' })}
        onOk={handleSubmit}
        confirmLoading={submitting}
        width={680}
        destroyOnClose
        maskClosable={false}
      >
        <Form form={editForm} layout="vertical" style={{ marginTop: 12 }}>
          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="parentId" label="上级部门" rules={[{ required: true }]}>
                <TreeSelect
                  treeData={treeOptions}
                  placeholder="请选择上级部门"
                  treeDefaultExpandAll
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="deptName" label="部门名称" rules={[{ required: true }]}>
                <Input maxLength={30} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="orderNum" label="显示排序" rules={[{ required: true }]}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="leader" label="负责人">
                <Input maxLength={20} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="phone" label="联系电话">
                <Input maxLength={11} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="email"
                label="邮箱"
                rules={[{ type: 'email', message: '请输入正确的邮箱' }]}
              >
                <Input maxLength={50} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="status" label="部门状态">
                <Radio.Group>
                  <Radio value="0">正常</Radio>
                  <Radio value="1">停用</Radio>
                </Radio.Group>
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
