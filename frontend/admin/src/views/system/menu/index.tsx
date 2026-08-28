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
  Tag,
  Tooltip,
  TreeSelect,
  message
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  RedoOutlined,
  SearchOutlined,
  SwapOutlined
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';

import {
  listMenu,
  getMenu,
  addMenu,
  updateMenu,
  delMenu
} from '@/api/system/menu';
import Auth from '@/components/Auth';
import IconPicker from '@/components/IconPicker';
import MenuIcon from '@/layouts/components/MenuIcon';
import PageHeader from '@/components/PageHeader';
import { handleTree } from '@/utils/ruoyi';

interface MenuRow {
  menuId: number;
  menuName: string;
  parentId: number;
  orderNum: number;
  path?: string;
  component?: string;
  query?: string;
  routeName?: string;
  perms?: string;
  icon?: string;
  menuType: 'M' | 'C' | 'F';
  visible: string;
  status: string;
  isFrame: string;
  isCache: string;
  children?: MenuRow[];
  createTime?: string;
}

const menuTypeLabel: Record<string, string> = { M: '目录', C: '菜单', F: '按钮' };
const menuTypeTagColor: Record<string, string> = { M: 'blue', C: 'green', F: 'orange' };

const LabelWithTooltip: React.FC<{ label: string; tip: string }> = ({ label, tip }) => (
  <span>
    <Tooltip title={tip}>
      <QuestionCircleOutlined style={{ color: '#94a3b8', marginRight: 4 }} />
    </Tooltip>
    {label}
  </span>
);

export default function MenuManagePage() {
  const [queryForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [rows, setRows] = useState<MenuRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialog, setDialog] = useState<{ open: boolean; row?: Partial<MenuRow>; title: string }>({
    open: false,
    title: ''
  });
  const [treeOptions, setTreeOptions] = useState<any[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [expandAll, setExpandAll] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

  const collectKeys = (list: MenuRow[]): React.Key[] => {
    const keys: React.Key[] = [];
    const walk = (ns: MenuRow[]) => {
      ns.forEach((n) => {
        keys.push(n.menuId);
        if (n.children?.length) walk(n.children);
      });
    };
    walk(list);
    return keys;
  };

  const fetch = async () => {
    setLoading(true);
    try {
      const res: any = await listMenu(queryForm.getFieldsValue());
      const list = res.data || [];
      const tree = handleTree<MenuRow>(list, 'menuId');
      setRows(tree);
      if (expandAll) setExpandedKeys(collectKeys(tree));
    } finally {
      setLoading(false);
    }
  };

  const buildTreeOptions = (list: any[]): any[] =>
    list.map((item) => ({
      title: item.menuName,
      value: item.menuId,
      key: item.menuId,
      children: item.children ? buildTreeOptions(item.children) : undefined
    }));

  useEffect(() => {
    fetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openAdd = (parent?: MenuRow) => {
    editForm.resetFields();
    editForm.setFieldsValue({
      parentId: parent?.menuId ?? 0,
      menuType: 'M',
      isFrame: '1',
      isCache: '0',
      visible: '0',
      status: '0',
      orderNum: 0
    });
    listMenu({}).then((res: any) => {
      const tree = handleTree(res.data || [], 'menuId');
      setTreeOptions([
        { title: '主类目', value: 0, key: 0, children: buildTreeOptions(tree) }
      ]);
    });
    setDialog({ open: true, row: {}, title: '新增菜单' });
  };

  const openEdit = async (row: MenuRow) => {
    editForm.resetFields();
    const res: any = await getMenu(row.menuId);
    const data = res.data || res;
    const listRes: any = await listMenu({});
    const tree = handleTree(listRes.data || [], 'menuId');
    setTreeOptions([{ title: '主类目', value: 0, key: 0, children: buildTreeOptions(tree) }]);
    setDialog({ open: true, row: data, title: '编辑菜单' });
    setTimeout(() => editForm.setFieldsValue(data), 0);
  };

  const handleSubmit = async () => {
    const values = await editForm.validateFields();
    const isEdit = !!dialog.row?.menuId;
    setSubmitting(true);
    try {
      if (isEdit) {
        await updateMenu({ ...dialog.row, ...values });
        message.success('修改成功');
      } else {
        await addMenu(values);
        message.success('新增成功');
      }
      setDialog({ open: false, title: '' });
      fetch();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (row: MenuRow) => {
    await delMenu(row.menuId);
    message.success('删除成功');
    fetch();
  };

  const columns: ColumnsType<MenuRow> = [
    { title: '菜单名称', dataIndex: 'menuName', width: 220 },
    {
      title: '图标',
      dataIndex: 'icon',
      width: 80,
      align: 'center',
      render: (v) => <MenuIcon icon={v} />
    },
    { title: '排序', dataIndex: 'orderNum', width: 80, align: 'center' },
    { title: '权限标识', dataIndex: 'perms', width: 200, ellipsis: true },
    { title: '组件路径', dataIndex: 'component', width: 220, ellipsis: true },
    {
      title: '类型',
      dataIndex: 'menuType',
      width: 80,
      align: 'center',
      render: (v: string) => (
        <Tag color={menuTypeTagColor[v]}>{menuTypeLabel[v] || v}</Tag>
      )
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      align: 'center',
      render: (v) => (v === '0' ? '正常' : '停用')
    },
    {
      title: '操作',
      key: '__ops__',
      width: 220,
      fixed: 'right',
      render: (_: any, row: MenuRow) => (
        <Space size={0} wrap>
          <Auth permission="system:menu:edit">
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
              编辑
            </Button>
          </Auth>
          <Auth permission="system:menu:add">
            <Button type="link" size="small" icon={<PlusOutlined />} onClick={() => openAdd(row)}>
              新增
            </Button>
          </Auth>
          <Auth permission="system:menu:remove">
            <Popconfirm title={`确认删除菜单 ${row.menuName} 吗？`} onConfirm={() => handleDelete(row)}>
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
      <PageHeader title="菜单管理" desc="维护系统菜单、路由与按钮权限标识" />
      <Card className="page-card" bordered={false}>
        <Form form={queryForm} layout="inline" onFinish={fetch}>
          <Form.Item name="menuName" label="菜单名称">
            <Input placeholder="请输入菜单名称" allowClear style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              style={{ width: 160 }}
              options={[
                { label: '正常', value: '0' },
                { label: '停用', value: '1' }
              ]}
              placeholder="请选择状态"
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
            <Auth permission="system:menu:add">
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
        <Table<MenuRow>
          rowKey="menuId"
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
        width={760}
        destroyOnClose
        maskClosable={false}
      >
        <Form form={editForm} layout="vertical" style={{ marginTop: 12 }}>
          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="menuType" label="菜单类型" rules={[{ required: true }]}>
                <Radio.Group>
                  <Radio.Button value="M">目录</Radio.Button>
                  <Radio.Button value="C">菜单</Radio.Button>
                  <Radio.Button value="F">按钮</Radio.Button>
                </Radio.Group>
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="parentId" label="上级菜单" rules={[{ required: true }]}>
                <TreeSelect
                  treeData={treeOptions}
                  placeholder="请选择上级菜单"
                  treeDefaultExpandAll
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') !== 'F' ? (
                    <Form.Item name="icon" label="菜单图标">
                      <IconPicker />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="orderNum" label="显示排序" rules={[{ required: true }]}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="menuName" label="菜单名称" rules={[{ required: true }]}>
                <Input maxLength={50} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') === 'C' ? (
                    <Form.Item
                      name="routeName"
                      label={<LabelWithTooltip label="路由名称" tip="默认不填则和路由地址相同，如有冲突请自定义保证唯一性" />}
                    >
                      <Input placeholder="请输入路由名称" />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') !== 'F' ? (
                    <Form.Item
                      name="isFrame"
                      label={<LabelWithTooltip label="是否外链" tip="选择是则路由地址需要以 http(s):// 开头" />}
                    >
                      <Radio.Group>
                        <Radio value="0">是</Radio>
                        <Radio value="1">否</Radio>
                      </Radio.Group>
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') !== 'F' ? (
                    <Form.Item
                      name="path"
                      label={<LabelWithTooltip label="路由地址" tip="访问的路由地址，如 user" />}
                      rules={[{ required: true, message: '路由地址不能为空' }]}
                    >
                      <Input placeholder="比如：user" />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') === 'C' ? (
                    <Form.Item
                      name="component"
                      label={<LabelWithTooltip label="组件路径" tip="访问的组件路径，如 system/user/index" />}
                    >
                      <Input placeholder="比如：system/user/index" />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') !== 'M' ? (
                    <Form.Item
                      name="perms"
                      label={<LabelWithTooltip label="权限字符" tip="权限字符，如 system:user:list" />}
                    >
                      <Input placeholder="比如：system:user:list" maxLength={100} />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') === 'C' ? (
                    <Form.Item
                      name="query"
                      label={<LabelWithTooltip label="路由参数" tip='访问路由的默认传递参数，如 {"id": 1, "name": "ry"}' />}
                    >
                      <Input placeholder='路由参数，如 {"id": 1}' maxLength={255} />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') === 'C' ? (
                    <Form.Item
                      name="isCache"
                      label={<LabelWithTooltip label="是否缓存" tip="选择是则会被 keep-alive 缓存，需要匹配组件的 name" />}
                    >
                      <Radio.Group>
                        <Radio value="0">缓存</Radio>
                        <Radio value="1">不缓存</Radio>
                      </Radio.Group>
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.menuType !== cur.menuType}
              >
                {({ getFieldValue }) =>
                  getFieldValue('menuType') !== 'F' ? (
                    <Form.Item
                      name="visible"
                      label={<LabelWithTooltip label="显示状态" tip="选择隐藏则路由不会出现在侧边栏，但仍然可以访问" />}
                    >
                      <Radio.Group>
                        <Radio value="0">显示</Radio>
                        <Radio value="1">隐藏</Radio>
                      </Radio.Group>
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="status"
                label={<LabelWithTooltip label="菜单状态" tip="选择停用则路由不会出现在侧边栏，也不能被访问" />}
              >
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
