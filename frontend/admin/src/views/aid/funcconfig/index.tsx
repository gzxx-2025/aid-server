import React, { useEffect, useState } from 'react';
import { Button, Card, Col, Form, Input, Modal, Popconfirm, Row, Select, Space, Table, Tag, message } from 'antd';
import { AppstoreOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, PlusOutlined, RedoOutlined, SearchOutlined } from '@ant-design/icons';
import {
  listFuncconfig, getFuncconfig, addFuncconfig, updateFuncconfig, delFuncconfig,
  listModelForFuncconfig
} from '@/api/aid/funcconfig';
import { listProvider } from '@/api/aid/aimanage';
import { MODEL_TYPE_OPTIONS, GENERATE_MODE_OPTIONS, INPUT_REQUIREMENT_OPTIONS, getLabelByValue } from '@/utils/enums';
import Auth from '@/components/Auth';
import SectionTitle from '@/components/SectionTitle';
import { download } from '@/utils/request';
import { useDict } from '@/hooks/useDict';
import ModelPoolSelector, { type PoolModel } from './ModelPoolSelector';

export default function FuncconfigPage() {
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [searchForm] = Form.useForm();
  const [modelPool, setModelPool] = useState<PoolModel[]>([]);
  const [providerNameMap, setProviderNameMap] = useState<Record<number, string>>({});
  const [dlgOpen, setDlgOpen] = useState(false);
  const [dlgTitle, setDlgTitle] = useState('');
  const [form] = Form.useForm();
  const [selectedModels, setSelectedModels] = useState<PoolModel[]>([]);
  const [saving, setSaving] = useState(false);
  const [editingId, setEditingId] = useState<any>(null);
  const [editingData, setEditingData] = useState<any>(null);
  const dicts = useDict('one_or_zero');
  const statusDict = dicts['one_or_zero'] || [];

  const loadPool = async () => {
    try {
      // 全量加载（含停用模型）：停用模型需在列表/已选中标识展示而非变成“未知”，
      // 且编辑保存时不能丢失停用模型的原有配置，模型恢复后配置原样生效
      const res: any = await listModelForFuncconfig({ pageNum: 1, pageSize: 999 });
      setModelPool(res.rows || []);
      return res.rows || [];
    } catch { setModelPool([]); return []; }
  };

  /** 服务商 id→名称映射（模型池标签展示用，加载失败不阻断） */
  const loadProviders = async () => {
    try {
      const res: any = await listProvider({ pageNum: 1, pageSize: 999 });
      const map: Record<number, string> = {};
      (res.rows || []).forEach((p: any) => { if (p.id != null) map[p.id] = p.providerName || p.providerCode || `#${p.id}`; });
      setProviderNameMap(map);
    } catch { /* 忽略：仅影响标签展示 */ }
  };

  const loadList = async () => {
    setLoading(true);
    try {
      if (modelPool.length === 0) await loadPool();
      const res: any = await listFuncconfig(query);
      const rows = (res.rows || []).map((r: any) => {
        let ids: number[] = [];
        try { ids = r.modelIds ? JSON.parse(r.modelIds) : []; } catch {}
        return { ...r, _parsedIds: ids };
      });
      setList(rows);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);
  useEffect(() => { loadProviders(); }, []);

  const resolveModelName = (id: number) => {
    const m = modelPool.find((x) => x.id === id);
    return m ? (m.modelName || m.modelCode || `#${id}`) : `#${id}(已删除)`;
  };

  const openAdd = async () => {
    await loadPool();
    form.resetFields();
    setEditingId(null);
    setEditingData(null);
    form.setFieldsValue({ status: '0' });
    setSelectedModels([]);
    setDlgTitle('新增功能配置');
    setDlgOpen(true);
  };

  const openEdit = async (row: any) => {
    const [pool, detail]: any[] = await Promise.all([loadPool(), getFuncconfig(row.id)]);
    const data = detail.data || detail;
    form.resetFields();
    form.setFieldsValue(data);
    setEditingId(data.id);
    setEditingData(data);
    let ids: number[] = [];
    try { ids = data.modelIds ? JSON.parse(data.modelIds) : []; } catch {}
    const byId = new Map((pool || []).map((m: any) => [m.id, m]));
    const seen = new Set<number>();
    // 池中不存在的历史模型保留占位（标记未知），避免编辑保存后悬空 ID 被静默丢弃
    const hydrated = ids
      .filter((id) => typeof id === 'number' && id > 0 && !seen.has(id) && seen.add(id))
      .map((id) => (byId.get(id) as PoolModel | undefined)
        || ({ id, modelCode: `#${id}`, modelName: `未知模型#${id}`, modelType: '', _missing: true } as PoolModel));
    setSelectedModels(hydrated);
    setDlgTitle('修改功能配置');
    setDlgOpen(true);
  };

  const handleDelete = async (row: any) => {
    await delFuncconfig(row.id);
    message.success('删除成功');
    loadList();
  };

  const handleExport = () => {
    download('/aid/funcconfig/export', query, `funcconfig_${Date.now()}.xlsx`);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    if (selectedModels.length === 0) { message.error('请至少选择一个可用模型'); return; }
    const modelIds = JSON.stringify(selectedModels.map((m) => m.id));
    setSaving(true);
    try {
      if (editingId) {
        await updateFuncconfig({ ...(editingData || {}), ...values, id: editingId, modelIds });
        message.success('修改成功');
      } else {
        await addFuncconfig({ ...values, modelIds });
        message.success('新增成功');
      }
      setDlgOpen(false);
      loadList();
    } finally { setSaving(false); }
  };

  const columns: any[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '功能名称', dataIndex: 'funcName', width: 160, render: (v: string) => <span style={{ fontWeight: 500 }}>{v}</span> },
    { title: '功能编码', dataIndex: 'funcCode', width: 200, ellipsis: true,
      render: (v: string) => <code className="code-text">{v}</code> },
    { title: '模型大类', dataIndex: 'modelType', width: 110,
      render: (v: string) => v ? <Tag color="purple" style={{ borderRadius: 6 }}>{getLabelByValue(MODEL_TYPE_OPTIONS, v, '--')}</Tag> : <span style={{ color: '#94a3b8' }}>--</span> },
    { title: '生成模式', dataIndex: 'generateMode', width: 130,
      render: (v: string) => v ? <Tag color="cyan" style={{ borderRadius: 6 }}>{getLabelByValue(GENERATE_MODE_OPTIONS, v, '--')}</Tag> : <span style={{ color: '#94a3b8' }}>--</span> },
    { title: '可选模型', key: 'models', render: (_: any, r: any) => {
      const ids: number[] = r._parsedIds || [];
      if (!ids.length) return <span style={{ color: '#94a3b8' }}>--</span>;
      return <Space wrap size={[4, 6]}>{ids.map((id, idx) => {
        const m = modelPool.find((x) => x.id === id);
        const req = m?.inputRequirement;
        // 已删除标红、已停用置灰，正常模型保持原有蓝色，保证池内引用状态一目了然
        const disabled = m?.status === '1';
        const color = !m ? 'error' : disabled ? 'default' : 'geekblue';
        return (
          <Tag key={idx} color={color} style={{ borderRadius: 6, margin: 0 }}>
            {resolveModelName(id)}
            {disabled && <span style={{ marginLeft: 4, opacity: 0.75, fontSize: 11 }}>[已停用]</span>}
            {req && req !== 'text_only' && (
              <span style={{ marginLeft: 4, opacity: 0.75, fontSize: 11 }}>
                [{getLabelByValue(INPUT_REQUIREMENT_OPTIONS, req)}]
              </span>
            )}
          </Tag>
        );
      })}</Space>;
    } },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => {
      const hit = statusDict.find((d: any) => d.value === v);
      const label = hit?.label || v;
      const color = v === '0' ? 'success' : 'default';
      return <Tag color={color} style={{ borderRadius: 6 }}>{label}</Tag>;
    } },
    { title: '备注', dataIndex: 'remark', ellipsis: true, width: 180 },
    { title: '操作', key: 'ops', width: 150, fixed: 'right' as const, render: (_: any, r: any) => (
      <Space size={0}>
        <Auth permission="aid:funcconfig:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>修改</Button></Auth>
        <Auth permission="aid:funcconfig:remove"><Popconfirm title="确认删除？" onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
      </Space>
    ) }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Form form={searchForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="funcName" label="功能名称"><Input allowClear style={{ width: 160 }} placeholder="请输入" /></Form.Item>
          <Form.Item name="funcCode" label="功能编码"><Input allowClear style={{ width: 180 }} placeholder="请输入" /></Form.Item>
          <Form.Item name="modelType" label="模型大类"><Select allowClear style={{ width: 140 }} options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择" /></Form.Item>
          <Form.Item name="generateMode" label="生成模式"><Select allowClear style={{ width: 160 }} options={GENERATE_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择" /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 120 }} options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} placeholder="请选择" /></Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
              <Button icon={<RedoOutlined />} onClick={() => { searchForm.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="aid:funcconfig:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
            <Auth permission="aid:funcconfig:export"><Button icon={<DownloadOutlined />} onClick={handleExport}>导出</Button></Auth>
          </Space>
          <div className="crud-page__stats">
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table rowKey="id" size="middle" loading={loading} dataSource={list} columns={columns} scroll={{ x: 1200 }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
        />
      </Card>

      <Modal open={dlgOpen} title={<Space><AppstoreOutlined style={{ color: '#2563eb' }} /><span>{dlgTitle}</span></Space>} onCancel={() => setDlgOpen(false)} onOk={handleSave} confirmLoading={saving} width={1180} destroyOnClose maskClosable={false}>
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="funcName" label="功能名称" rules={[{ required: true, message: '功能名称不能为空' }]}><Input placeholder="如：图片编辑、图片高清" /></Form.Item></Col>
            <Col span={12}><Form.Item name="funcCode" label="功能编码" rules={[{ required: true, message: '功能编码不能为空' }]}><Input placeholder="如：image_edit / image_upscale" /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelType" label="模型大类"><Select allowClear options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择模型大类" /></Form.Item></Col>
            <Col span={12}><Form.Item name="generateMode" label="生成模式"><Select allowClear options={GENERATE_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择生成模式" /></Form.Item></Col>
          </Row>

          <SectionTitle title="可选模型" desc="必选；可按「输入要求」区分图片必传 / 图片可选等类型" />

          <ModelPoolSelector
            pool={modelPool}
            selected={selectedModels}
            onChange={setSelectedModels}
            providerNameMap={providerNameMap}
          />

          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col span={12}><Form.Item name="status" label="状态" rules={[{ required: true }]}><Select options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} placeholder="请选择状态" /></Form.Item></Col>
            <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder="请输入备注说明" /></Form.Item></Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
