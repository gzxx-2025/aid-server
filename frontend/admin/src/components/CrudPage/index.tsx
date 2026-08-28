import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Image,
  Modal,
  Popconfirm,
  Popover,
  Space,
  Table,
  Tag,
  Tooltip,
  message
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileSearchOutlined
} from '@ant-design/icons';
import type { TableProps } from 'antd';
import dayjs from 'dayjs';

import { useAuth } from '@/hooks/useAuth';
import { useDict } from '@/hooks/useDict';
import { parseTime } from '@/utils/ruoyi';
import { download } from '@/utils/request';
import DictTag from '@/components/DictTag';
import PageCard from '@/components/PageCard';

import SearchBar from './SearchBar';
import FormRenderer from './FormRenderer';
import type { CrudConfig, ColumnConfig } from './types';

import './style.less';

export type { CrudConfig, ColumnConfig, SearchConfig, FieldConfig, CrudApi, EmbeddedScope } from './types';

interface Props<T = any> {
  config: CrudConfig<T>;
}

/**
 * 生成带作用域的列表配置：把 projectId/episodeId/userId 等合并进 defaultQuery，
 * 供「项目工作台」内嵌复用各列表页（需求16 整合）。无 scope 时原样返回。
 */
export function scopedConfig<T extends Record<string, any>>(
  config: CrudConfig<T>,
  scope?: import('./types').EmbeddedScope
): CrudConfig<T> {
  if (!scope) return config;
  const clean: Record<string, any> = {};
  Object.entries(scope).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') clean[k] = v;
  });
  if (Object.keys(clean).length === 0) return config;
  const scopeKeys = Object.keys(clean);
  return {
    ...config,
    defaultQuery: { ...(config.defaultQuery || {}), ...clean },
    // 移除被作用域固定的搜索项，避免用户在工作台内改写 projectId/userId 跳出当前项目维度
    searchFields: (config.searchFields || []).filter((f) => !scopeKeys.includes(f.name))
  };
}

function resolvePerms(config: CrudConfig) {
  if (config.perms) return config.perms;
  const p = config.permPrefix;
  if (!p) return {};
  return {
    add: `${p}:add`,
    edit: `${p}:edit`,
    remove: `${p}:remove`,
    export: `${p}:export`,
    query: `${p}:query`
  };
}

export default function CrudPage<T extends Record<string, any>>({ config }: Props<T>) {
  const {
    title,
    rowKey = 'id',
    api,
    columns,
    searchFields = [],
    formFields = [],
    modalWidth = 680,
    defaultQuery = {},
    pageSize: initialPageSize = 10,
    selectable = true,
    exportable = true,
    hideAdd,
    hideEdit,
    hideDelete,
    viewable,
    toolbarExtra,
    rowActions = [],
    dictTypes = [],
    beforeSubmit,
    afterFetch
  } = config;

  const perms = resolvePerms(config);
  const { hasPermi } = useAuth();

  // 搜集所有需要的字典 key（列 + 搜索 + 表单 + 手动声明）
  const neededDicts = useMemo(() => {
    const s = new Set<string>(dictTypes);
    columns.forEach((c) => c.dictType && s.add(c.dictType));
    searchFields.forEach((f) => f.dictType && s.add(f.dictType));
    formFields.forEach((f) => f.dictType && s.add(f.dictType));
    return Array.from(s);
  }, [columns, searchFields, formFields, dictTypes]);
  const dicts = useDict(...neededDicts);

  const [rows, setRows] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [queryParams, setQueryParams] = useState<Record<string, any>>({
    pageNum: 1,
    pageSize: initialPageSize,
    ...defaultQuery
  });
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [selectedRows, setSelectedRows] = useState<T[]>([]);

  const [dialog, setDialog] = useState<{ open: boolean; row?: Partial<T>; title: string }>({
    open: false,
    title: ''
  });
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  // 查看详情抽屉
  const [viewState, setViewState] = useState<{ open: boolean; row?: any }>({ open: false });

  const openView = async (row: T) => {
    let data: any = row;
    if (api.get) {
      try {
        const res: any = await api.get((row as any)[rowKey]);
        data = res.data ?? res;
      } catch {
        data = row;
      }
    }
    if (afterFetch) data = afterFetch(data);
    setViewState({ open: true, row: data });
  };

  const fetchData = async () => {
    setLoading(true);
    try {
      const res: any = await api.list(queryParams);
      const list = res.rows ?? res.data?.rows ?? res.data ?? [];
      const totalNum = res.total ?? res.data?.total ?? (Array.isArray(list) ? list.length : 0);
      setRows(Array.isArray(list) ? list : []);
      setTotal(totalNum);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryParams]);

  const handleSearch = (values: Record<string, any>) => {
    setQueryParams({ pageNum: 1, pageSize: queryParams.pageSize, ...defaultQuery, ...values });
  };

  const handleResetSearch = () => {
    setQueryParams({ pageNum: 1, pageSize: queryParams.pageSize, ...defaultQuery });
  };

  const openAdd = () => {
    setDialog({ open: true, row: {}, title: `新增${title}` });
    form.resetFields();
    // 应用 initialValue
    const init: Record<string, any> = {};
    formFields.forEach((f) => {
      if (f.initialValue !== undefined) init[f.name] = f.initialValue;
    });
    form.setFieldsValue(init);
  };

  const openEdit = async (row: T) => {
    form.resetFields();
    let data: any = row;
    if (api.get) {
      const res: any = await api.get((row as any)[rowKey]);
      data = res.data ?? res;
    }
    if (afterFetch) data = afterFetch(data);
    setDialog({ open: true, row: data, title: `编辑${title}` });
    // 日期字段转 dayjs
    const formData = { ...data };
    formFields.forEach((f) => {
      if (f.type === 'date' && formData[f.name]) {
        formData[f.name] = dayjs(formData[f.name]);
      }
    });
    setTimeout(() => form.setFieldsValue(formData), 0);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    // 处理 dayjs → string
    const payload: any = { ...values };
    formFields.forEach((f) => {
      if (f.type === 'date' && payload[f.name]) {
        payload[f.name] = dayjs.isDayjs(payload[f.name])
          ? payload[f.name].format('YYYY-MM-DD HH:mm:ss')
          : payload[f.name];
      }
    });

    const isEdit = !!dialog.row && (dialog.row as any)[rowKey] !== undefined;
    const finalData = beforeSubmit ? beforeSubmit(payload, isEdit) : payload;

    setSubmitting(true);
    try {
      if (isEdit && api.update) {
        await api.update({ ...(dialog.row as any), ...finalData });
        message.success('修改成功');
      } else if (!isEdit && api.add) {
        await api.add(finalData);
        message.success('新增成功');
      }
      setDialog({ open: false, title: '' });
      fetchData();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (row: T) => {
    if (!api.remove) return;
    await api.remove((row as any)[rowKey]);
    message.success('删除成功');
    fetchData();
  };

  const handleBatchDelete = async () => {
    if (!api.remove || selectedRowKeys.length === 0) return;
    Modal.confirm({
      title: '确认删除',
      content: `确认删除选中的 ${selectedRowKeys.length} 条数据吗？`,
      okType: 'danger',
      onOk: async () => {
        await api.remove!(selectedRowKeys.join(','));
        message.success('删除成功');
        setSelectedRowKeys([]);
        setSelectedRows([]);
        fetchData();
      }
    });
  };

  const handleExport = () => {
    if (!api.exportUrl) return;
    download(api.exportUrl, queryParams, `${title}_${Date.now()}.xlsx`);
  };

  // 列组装
  const finalColumns = useMemo<TableProps<T>['columns']>(() => {
    const list = columns.map<ColumnConfig<T>>((c) => {
      const cloned: ColumnConfig<T> = { ...c };
      if (cloned.ellipsis && !cloned.render) {
        const origin = cloned;
        cloned.render = (v: any) => {
          const text = v == null || v === '' ? '-' : String(v);
          const isLong = text.length > 40;
          if (!isLong || text === '-') {
            return (
              <span title={text} style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {text}
              </span>
            );
          }
          return (
            <Popover
              placement="topLeft"
              content={
                <div
                  style={{
                    maxWidth: 520,
                    maxHeight: 360,
                    overflow: 'auto',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    lineHeight: 1.7,
                    fontSize: 13
                  }}
                >
                  {text}
                </div>
              }
              overlayStyle={{ maxWidth: 560 }}
            >
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  maxWidth: '100%',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  cursor: 'pointer',
                  color: 'inherit'
                }}
              >
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{text}</span>
                <FileSearchOutlined style={{ color: '#6366f1', flexShrink: 0 }} />
              </span>
            </Popover>
          );
        };
        cloned.ellipsis = origin.ellipsis;
      }
      if (cloned.dateFormat) {
        cloned.render = (v: any) => {
          if (!v) return '-';
          const fmt = typeof cloned.dateFormat === 'string' ? cloned.dateFormat : 'YYYY-MM-DD HH:mm:ss';
          return parseTime(v, fmt) || '-';
        };
      } else if (cloned.dictType) {
        const dictKey = cloned.dictType;
        cloned.render = (v: any) => <DictTag options={dicts[dictKey] || []} value={v} />;
      } else if (cloned.prefix || cloned.suffix) {
        const pre = cloned.prefix || '';
        const suf = cloned.suffix || '';
        cloned.render = (v: any) => (v === null || v === undefined || v === '' ? '-' : `${pre}${v}${suf}`);
      }
      return cloned;
    });

    // 自动补充操作列
    const hasOps =
      !hideEdit || !hideDelete || rowActions.length > 0 || viewable;
    if (hasOps) {
      list.push({
        title: '操作',
        key: '__ops__',
        fixed: 'right',
        width: Math.max(140, 80 * (rowActions.length + (viewable ? 1 : 0) + (hideEdit ? 0 : 1) + (hideDelete ? 0 : 1))),
        render: (_: any, record: T) => (
          <Space size={0} wrap>
            {viewable && (
              <Button
                type="link"
                size="small"
                icon={<EyeOutlined />}
                onClick={() => openView(record)}
              >
                查看
              </Button>
            )}
            {!hideEdit && api.update && (!perms.edit || hasPermi(perms.edit)) && (
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                onClick={() => openEdit(record)}
              >
                编辑
              </Button>
            )}
            {rowActions.map((act, idx) => {
              if (act.visible && !act.visible(record)) return null;
              if (act.perm && !hasPermi(act.perm)) return null;
              const onClick = async () => {
                if (act.confirm) {
                  const text = typeof act.confirm === 'function' ? act.confirm(record) : act.confirm;
                  Modal.confirm({
                    title: '提示',
                    content: text,
                    onOk: () => act.onClick(record, { refresh: fetchData })
                  });
                } else {
                  await act.onClick(record, { refresh: fetchData });
                }
              };
              return (
                <Button
                  key={idx}
                  type="link"
                  size="small"
                  danger={act.danger}
                  icon={act.icon}
                  onClick={onClick}
                >
                  {act.label}
                </Button>
              );
            })}
            {!hideDelete && api.remove && (!perms.remove || hasPermi(perms.remove)) && (
              <Popconfirm
                title={`确认删除此条${title}吗？`}
                onConfirm={() => handleDelete(record)}
              >
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            )}
          </Space>
        )
      } as any);
    }
    return list as any;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [columns, dicts, hideEdit, hideDelete, rowActions, perms, title, api, viewable]);

  const pagination = {
    current: queryParams.pageNum,
    pageSize: queryParams.pageSize,
    total,
    showSizeChanger: true,
    showQuickJumper: true,
    showTotal: (t: number) => `共 ${t} 条`,
    onChange: (page: number, size: number) =>
      setQueryParams({ ...queryParams, pageNum: page, pageSize: size })
  };

  const canAdd = !hideAdd && api.add && (!perms.add || hasPermi(perms.add));
  const canExport = exportable && api.exportUrl && (!perms.export || hasPermi(perms.export));
  const canBatchDel = !hideDelete && api.remove && (!perms.remove || hasPermi(perms.remove));

  return (
    <div className="crud-page">
      {searchFields.length > 0 && (
        <Card className="crud-page__search page-card" bordered={false}>
          <SearchBar
            fields={searchFields}
            loading={loading}
            onSearch={handleSearch}
            onReset={handleResetSearch}
          />
        </Card>
      )}

      <Card className="crud-page__table page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            {canAdd && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>
                新增
              </Button>
            )}
            {canBatchDel && (
              <Button
                danger
                disabled={selectedRowKeys.length === 0}
                icon={<DeleteOutlined />}
                onClick={handleBatchDelete}
              >
                批量删除
              </Button>
            )}
            {canExport && (
              <Button icon={<DownloadOutlined />} onClick={handleExport}>
                导出
              </Button>
            )}
            {toolbarExtra && toolbarExtra({ refresh: fetchData, selected: selectedRows })}
          </Space>
          <div className="crud-page__stats">
            {selectedRowKeys.length > 0 && (
              <Tag color="blue">已选 {selectedRowKeys.length}</Tag>
            )}
            <span>共 {total} 条</span>
          </div>
        </div>

        <Table<T>
          rowKey={rowKey}
          size="middle"
          loading={loading}
          dataSource={rows}
          columns={finalColumns}
          scroll={{ x: 'max-content' }}
          rowSelection={
            selectable
              ? {
                  selectedRowKeys,
                  onChange: (keys, selected) => {
                    setSelectedRowKeys(keys);
                    setSelectedRows(selected as T[]);
                  }
                }
              : undefined
          }
          pagination={pagination}
        />
      </Card>

      <Modal
        open={dialog.open}
        title={dialog.title}
        onCancel={() => setDialog({ open: false, title: '' })}
        onOk={handleSubmit}
        confirmLoading={submitting}
        width={modalWidth}
        destroyOnClose
        maskClosable={false}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <FormRenderer
            fields={formFields}
            form={form}
            isEdit={!!dialog.row && (dialog.row as any)[rowKey] !== undefined}
          />
        </Form>
      </Modal>

      {/* 查看详情抽屉：只读展示，富文本字段渲染 HTML */}
      <Drawer
        title={`${title}详情`}
        width={720}
        open={viewState.open}
        onClose={() => setViewState({ open: false })}
        destroyOnClose
      >
        {viewState.row && (
          <Descriptions bordered column={1} size="small">
            {(formFields.length
              ? formFields.map((f) => ({ key: f.name, label: f.label, type: f.type, field: f as any }))
              : columns
                  .filter((c) => c.dataIndex)
                  .map((c) => ({
                    key: Array.isArray(c.dataIndex) ? c.dataIndex.join('.') : (c.dataIndex as string),
                    label: (c.title as string) || '',
                    type: undefined as any,
                    field: undefined as any
                  }))
            ).map((item) => {
              const raw = viewState.row?.[item.key];
              return (
                <Descriptions.Item key={item.key} label={item.label}>
                  {item.field?.viewRender ? (
                    item.field.viewRender(raw, viewState.row)
                  ) : item.type === 'richtext' && raw ? (
                    <div
                      className="rich-content-view"
                      style={{ lineHeight: 1.8, wordBreak: 'break-word' }}
                      // 内容来自后台编辑器，受信任；展示富文本
                      dangerouslySetInnerHTML={{ __html: String(raw) }}
                    />
                  ) : item.type === 'image' && raw ? (
                    <Image
                      src={String(raw)}
                      width={160}
                      style={{ borderRadius: 8, objectFit: 'cover' }}
                    />
                  ) : raw === null || raw === undefined || raw === '' ? (
                    <span style={{ color: '#94a3b8' }}>-</span>
                  ) : (
                    <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{String(raw)}</span>
                  )}
                </Descriptions.Item>
              );
            })}
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
}
