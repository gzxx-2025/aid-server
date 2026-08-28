import React, { useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Col, Empty, Form, Input, InputNumber, Modal, Popconfirm, Radio, Row,
  Select, Space, Table, Tag, Tooltip, message
} from 'antd';
import {
  DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, TagsOutlined
} from '@ant-design/icons';

import { listType, addType, updateType, delType, refreshCache } from '@/api/system/dict/type';
import { listData, addData, updateData, delData } from '@/api/system/dict/data';
import Auth from '@/components/Auth';
import './style.less';

/** listClass → antd Tag color */
const listClassToColor: Record<string, string> = {
  default: 'default', primary: 'processing', success: 'success',
  info: 'default', warning: 'warning', danger: 'error'
};
const LIST_CLASS_OPTIONS = [
  { label: '默认', value: 'default' }, { label: '主要', value: 'primary' },
  { label: '成功', value: 'success' }, { label: '信息', value: 'info' },
  { label: '警告', value: 'warning' }, { label: '危险', value: 'danger' }
];
const STATUS_OPTIONS = [
  { label: '正常', value: '0' }, { label: '停用', value: '1' }
];

/**
 * 字典管理（需求4 优化）：左侧字典类型、右侧字典数据，主从联动、无需跳转，
 * 类型与数据的增删改均在本页内联完成。
 */
export default function DictManager() {
  // 类型列表
  const [types, setTypes] = useState<any[]>([]);
  const [typeLoading, setTypeLoading] = useState(false);
  const [typeKeyword, setTypeKeyword] = useState('');
  const [current, setCurrent] = useState<any | null>(null);

  // 数据列表
  const [data, setData] = useState<any[]>([]);
  const [dataLoading, setDataLoading] = useState(false);
  const [dataKeyword, setDataKeyword] = useState('');

  // 类型弹窗
  const [typeForm] = Form.useForm();
  const [typeDlg, setTypeDlg] = useState<{ open: boolean; row?: any }>({ open: false });
  // 数据弹窗
  const [dataForm] = Form.useForm();
  const [dataDlg, setDataDlg] = useState<{ open: boolean; row?: any }>({ open: false });

  const loadTypes = async () => {
    setTypeLoading(true);
    try {
      const res: any = await listType({ pageNum: 1, pageSize: 1000, dictName: typeKeyword || undefined });
      const list = res.rows || res.data || [];
      setTypes(list);
      // 默认选中第一个
      if (!current && list.length) {
        setCurrent(list[0]);
      }
    } finally {
      setTypeLoading(false);
    }
  };

  const loadData = async (dictType: string) => {
    setDataLoading(true);
    try {
      const res: any = await listData({ pageNum: 1, pageSize: 1000, dictType });
      setData(res.rows || res.data || []);
    } finally {
      setDataLoading(false);
    }
  };

  useEffect(() => {
    loadTypes();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (current?.dictType) loadData(current.dictType);
    else setData([]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.dictType]);

  const filteredData = useMemo(() => {
    if (!dataKeyword.trim()) return data;
    const kw = dataKeyword.trim().toLowerCase();
    return data.filter((d) => String(d.dictLabel || '').toLowerCase().includes(kw)
      || String(d.dictValue || '').toLowerCase().includes(kw));
  }, [data, dataKeyword]);

  // ===== 类型增删改 =====
  const openTypeAdd = () => { typeForm.resetFields(); typeForm.setFieldsValue({ status: '0' }); setTypeDlg({ open: true }); };
  const openTypeEdit = (row: any) => { typeForm.resetFields(); typeForm.setFieldsValue(row); setTypeDlg({ open: true, row }); };
  const saveType = async () => {
    const v = await typeForm.validateFields();
    if (typeDlg.row?.dictId) {
      await updateType({ ...typeDlg.row, ...v });
      message.success('修改成功');
    } else {
      await addType(v);
      message.success('新增成功');
    }
    setTypeDlg({ open: false });
    loadTypes();
  };
  const removeType = async (row: any) => {
    await delType(row.dictId);
    message.success('删除成功');
    if (current?.dictId === row.dictId) setCurrent(null);
    loadTypes();
  };

  // ===== 数据增删改 =====
  const openDataAdd = () => {
    if (!current) { message.warning('请先选择左侧字典类型'); return; }
    dataForm.resetFields();
    dataForm.setFieldsValue({ status: '0', dictSort: 0, listClass: 'default', dictType: current.dictType });
    setDataDlg({ open: true });
  };
  const openDataEdit = (row: any) => { dataForm.resetFields(); dataForm.setFieldsValue(row); setDataDlg({ open: true, row }); };
  const saveData = async () => {
    const v = await dataForm.validateFields();
    if (dataDlg.row?.dictCode) {
      await updateData({ ...dataDlg.row, ...v });
      message.success('修改成功');
    } else {
      await addData({ ...v, dictType: current.dictType });
      message.success('新增成功');
    }
    setDataDlg({ open: false });
    loadData(current.dictType);
  };
  const removeData = async (row: any) => {
    await delData(row.dictCode);
    message.success('删除成功');
    loadData(current!.dictType);
  };

  return (
    <Row gutter={16} wrap={false} align="top" className="dict-manager">
      {/* 左：字典类型 */}
      <Col flex="360px">
        <Card
          bordered={false}
          className="page-card"
          title={<Space><TagsOutlined />字典类型 <Tag color="blue">{types.length}</Tag></Space>}
          extra={
            <Space size={4}>
              <Auth permission="system:dict:add">
                <Tooltip title="新增类型"><Button size="small" type="primary" icon={<PlusOutlined />} onClick={openTypeAdd} /></Tooltip>
              </Auth>
              <Tooltip title="刷新缓存">
                <Button size="small" icon={<ReloadOutlined />} onClick={async () => { await refreshCache(); message.success('刷新成功'); }} />
              </Tooltip>
            </Space>
          }
        >
          <Input
            allowClear
            prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
            placeholder="搜索字典名称"
            value={typeKeyword}
            onChange={(e) => setTypeKeyword(e.target.value)}
            onPressEnter={loadTypes}
            style={{ marginBottom: 10 }}
          />
          <Table
            rowKey="dictId"
            size="small"
            loading={typeLoading}
            dataSource={types}
            pagination={false}
            scroll={{ y: 'calc(100vh - 360px)' }}
            onRow={(record) => ({
              onClick: () => setCurrent(record),
              className: `dict-type-row${current?.dictId === record.dictId ? ' dict-type-row--active' : ''}`
            })}
            columns={[
              {
                title: '字典名称', dataIndex: 'dictName',
                render: (v: string, r: any) => (
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis' }}>{v}</div>
                    <div style={{ color: '#94a3b8', fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.dictType}</div>
                  </div>
                )
              },
              {
                title: '操作', width: 90, align: 'right' as const,
                render: (_: any, r: any) => (
                  <Space size={0} onClick={(e) => e.stopPropagation()}>
                    <Auth permission="system:dict:edit">
                      <Button size="small" type="text" icon={<EditOutlined />} onClick={() => openTypeEdit(r)} />
                    </Auth>
                    <Auth permission="system:dict:remove">
                      <Popconfirm title="确认删除该字典类型？" onConfirm={() => removeType(r)}>
                        <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                      </Popconfirm>
                    </Auth>
                  </Space>
                )
              }
            ]}
          />
        </Card>
      </Col>

      {/* 右：字典数据 */}
      <Col flex="auto" style={{ minWidth: 0 }}>
        <Card
          bordered={false}
          className="page-card"
          title={
            current ? (
              <Space wrap>
                <span style={{ fontWeight: 600 }}>{current.dictName}</span>
                <Tag color="geekblue">{current.dictType}</Tag>
              </Space>
            ) : '字典数据'
          }
          extra={
            <Space>
              <Input
                allowClear
                prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
                placeholder="搜索标签/键值"
                value={dataKeyword}
                onChange={(e) => setDataKeyword(e.target.value)}
                style={{ width: 200 }}
              />
              <Auth permission="system:dict:add">
                <Button type="primary" icon={<PlusOutlined />} onClick={openDataAdd} disabled={!current}>新增数据</Button>
              </Auth>
            </Space>
          }
        >
          {current ? (
            <Table
              rowKey="dictCode"
              size="middle"
              loading={dataLoading}
              dataSource={filteredData}
              scroll={{ x: 'max-content' }}
              pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
              columns={[
                { title: '编码', dataIndex: 'dictCode', width: 90 },
                {
                  title: '标签', dataIndex: 'dictLabel', width: 200,
                  render: (v: string, r: any) => {
                    const lc = r.listClass;
                    if (!lc || lc === 'default') return <span>{v}</span>;
                    return <Tag color={listClassToColor[lc] || 'default'}>{v}</Tag>;
                  }
                },
                { title: '键值', dataIndex: 'dictValue', width: 200 },
                { title: '排序', dataIndex: 'dictSort', width: 80 },
                {
                  title: '状态', dataIndex: 'status', width: 90,
                  render: (v: string) => <Tag color={v === '0' ? 'green' : 'default'}>{v === '0' ? '正常' : '停用'}</Tag>
                },
                { title: '备注', dataIndex: 'remark', ellipsis: true },
                {
                  title: '操作', width: 130, fixed: 'right' as const,
                  render: (_: any, r: any) => (
                    <Space size={0}>
                      <Auth permission="system:dict:edit">
                        <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openDataEdit(r)}>修改</Button>
                      </Auth>
                      <Auth permission="system:dict:remove">
                        <Popconfirm title="确认删除？" onConfirm={() => removeData(r)}>
                          <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
                        </Popconfirm>
                      </Auth>
                    </Space>
                  )
                }
              ]}
            />
          ) : (
            <Empty description="请选择左侧字典类型查看数据" style={{ padding: 60 }} />
          )}
        </Card>
      </Col>

      {/* 类型弹窗 */}
      <Modal open={typeDlg.open} title={typeDlg.row ? '修改字典类型' : '新增字典类型'} onCancel={() => setTypeDlg({ open: false })} onOk={saveType} destroyOnClose maskClosable={false}>
        <Form form={typeForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="dictName" label="字典名称" rules={[{ required: true, max: 100 }]}><Input placeholder="如：用户性别" /></Form.Item>
          <Form.Item name="dictType" label="字典类型" rules={[{ required: true, max: 100 }]}><Input placeholder="如：sys_user_sex" /></Form.Item>
          <Form.Item name="status" label="状态"><Radio.Group>{STATUS_OPTIONS.map((o) => <Radio key={o.value} value={o.value}>{o.label}</Radio>)}</Radio.Group></Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>

      {/* 数据弹窗 */}
      <Modal open={dataDlg.open} title={dataDlg.row ? '修改字典数据' : '新增字典数据'} onCancel={() => setDataDlg({ open: false })} onOk={saveData} destroyOnClose maskClosable={false}>
        <Form form={dataForm} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="dictLabel" label="数据标签" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="dictValue" label="数据键值" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="dictSort" label="显示排序" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={0} /></Form.Item></Col>
            <Col span={12}><Form.Item name="listClass" label="回显样式"><Select options={LIST_CLASS_OPTIONS} /></Form.Item></Col>
            <Col span={12}><Form.Item name="cssClass" label="样式属性"><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="status" label="状态"><Radio.Group>{STATUS_OPTIONS.map((o) => <Radio key={o.value} value={o.value}>{o.label}</Radio>)}</Radio.Group></Form.Item></Col>
            <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item></Col>
          </Row>
        </Form>
      </Modal>
    </Row>
  );
}
