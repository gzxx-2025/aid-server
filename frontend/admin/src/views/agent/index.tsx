import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  Dropdown,
  Form,
  Image,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  message
} from 'antd';
import type { MenuProps } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowRightOutlined,
  BookOutlined,
  CheckCircleOutlined,
  CloudDownloadOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  ExportOutlined,
  EyeOutlined,
  FileTextOutlined,
  FilterOutlined,
  PauseCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
  ThunderboltOutlined
} from '@ant-design/icons';
import {
  listAgent,
  getAgent,
  createAgent,
  updateAgent,
  listAgentByBizCategoryAdmin,
  type AgentItem
} from '@/api/aid/agent';
import {
  getAgentRetirementImpact,
  retireAgent,
  type OrchestrationImpact
} from '@/api/aid/orchestration';
import { getDocLinks } from '@/api/aidconfig/upgrade';
import { listModel } from '@/api/aid/aimanage';
import { listFuncconfig } from '@/api/aid/funcconfig';
import Auth from '@/components/Auth';
import ImageUpload from '@/components/ImageUpload';
import PageHeader from '@/components/PageHeader';
import StatCard from '@/components/StatCard';
import { useAuth } from '@/hooks/useAuth';
import { resolveAppUrl } from '@/utils/ruoyi';
import RetirementModal from '@/views/aid/orchestration/RetirementModal';

const STATUS_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 }
];

const AGENT_PROMPT_MAX_LENGTH = 100_000;

interface ModelOption {
  id: number;
  modelCode: string;
  realModelCode?: string;
  modelName: string;
  modelType?: string;
  providerName?: string;
}

interface FuncOption {
  id: number;
  funcCode: string;
  funcName: string;
  modelType?: string;
  generateMode?: string;
  modelIds?: string;
  status?: string;
}

interface SearchForm {
  agentCode?: string;
  name?: string;
  bizCategoryCode?: string;
  status?: number;
}

/** 解析 modelIds JSON 字符串为 number[]，脏数据自动跳过 */
function parseModelIds(json?: string): number[] {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    if (!Array.isArray(arr)) return [];
    return arr.map((v) => Number(v)).filter((n) => Number.isFinite(n) && n > 0);
  } catch {
    return [];
  }
}

/**
 * 业务分类编码（= funcCode）下拉，附带功能名 / 模型类型 标签。
 * 选中后联动外层"默认模型"下拉收紧到该功能可选模型池。
 */
function BizCategoryCodeSelect({
  value,
  onChange,
  funcs,
  loading
}: {
  value?: string;
  onChange?: (v: string | undefined) => void;
  funcs: FuncOption[];
  loading: boolean;
}) {
  const options = useMemo(() => {
    return funcs
      .filter((f) => f.funcCode && f.status !== '1')
      .map((f) => ({
        label: (
          <span>
            <span style={{ fontWeight: 500 }}>{f.funcName || f.funcCode}</span>
            <span style={{ color: '#94a3b8', marginLeft: 8, fontSize: 12 }}>{f.funcCode}</span>
            {f.modelType && (
              <Tag bordered={false} color="geekblue" style={{ marginLeft: 6, fontSize: 11, lineHeight: '16px' }}>
                {f.modelType}
              </Tag>
            )}
          </span>
        ),
        value: f.funcCode,
        rawLabel: `${f.funcName || ''} ${f.funcCode}`.toLowerCase()
      }));
  }, [funcs]);

  return (
    <Select
      showSearch
      allowClear
      value={value}
      onChange={onChange}
      placeholder="请选择业务分类（即功能编码，与AI模型功能配置打通）"
      style={{ width: '100%' }}
      optionFilterProp="rawLabel"
      filterOption={(input, option: any) => String(option?.rawLabel || '').includes(input.toLowerCase())}
      options={options}
      notFoundContent={loading ? <Spin size="small" /> : <span style={{ color: '#94a3b8' }}>暂无功能配置</span>}
    />
  );
}

/**
 * 默认模型下拉：按当前选中的 bizCategoryCode（funcCode）收紧可选范围，
 * 与历史实现保持一致的提示文案。
 */
function ModelCodeSelect({
  value,
  onChange,
  models,
  modelLoading,
  funcs,
  bizCategoryCode
}: {
  value?: string;
  onChange?: (v: string | undefined) => void;
  models: ModelOption[];
  modelLoading: boolean;
  funcs: FuncOption[];
  bizCategoryCode?: string;
}) {
  const { modelIdSet, hint } = useMemo(() => {
    if (!bizCategoryCode) {
      return { modelIdSet: undefined as Set<number> | undefined, hint: '未选择业务分类，可选所有文本模型' };
    }
    const cfg = funcs.find((f) => f.funcCode === bizCategoryCode);
    if (!cfg) {
      return { modelIdSet: new Set<number>(), hint: '该业务分类未在 AI模型功能配置 中登记，请先去登记或换一个分类' };
    }
    if (cfg.status === '1') {
      return { modelIdSet: new Set<number>(), hint: '该业务分类对应的功能配置已停用，请先启用或换一个分类' };
    }
    const ids = parseModelIds(cfg.modelIds);
    if (ids.length === 0) {
      return { modelIdSet: new Set<number>(), hint: `「${cfg.funcName || cfg.funcCode}」尚未配置可选模型，请先去 AI模型功能配置 中维护` };
    }
    return { modelIdSet: new Set<number>(ids), hint: '' };
  }, [funcs, bizCategoryCode]);

  const options = useMemo(() => {
    let pool = models;
    if (modelIdSet) {
      pool = models.filter((m) => modelIdSet.has(m.id));
    } else {
      pool = models.filter((m) => !m.modelType || m.modelType === 'text');
    }
    return pool.map((m) => ({
      label: (
        <span>
          <span style={{ fontWeight: 500 }}>{m.modelName || m.modelCode}</span>
          <span style={{ color: '#94a3b8', marginLeft: 8, fontSize: 12 }}>{m.modelCode}</span>
          {m.realModelCode && m.realModelCode !== m.modelCode && (
            <span style={{ color: '#cbd5e1', marginLeft: 6, fontSize: 12 }}>→ {m.realModelCode}</span>
          )}
          {m.providerName && (
            <Tag bordered={false} color="blue" style={{ marginLeft: 6, fontSize: 11, lineHeight: '16px' }}>
              {m.providerName}
            </Tag>
          )}
          {m.modelType && (
            <Tag bordered={false} color="purple" style={{ marginLeft: 4, fontSize: 11, lineHeight: '16px' }}>
              {m.modelType}
            </Tag>
          )}
        </span>
      ),
      value: m.modelCode,
      rawLabel: `${m.modelName || ''} ${m.modelCode} ${m.realModelCode || ''}`.toLowerCase()
    }));
  }, [models, modelIdSet]);

  const placeholder = modelIdSet && modelIdSet.size === 0
    ? '该业务分类暂无可选模型'
    : '请选择默认模型（不填则业务调用时由用户传入）';

  return (
    <div>
      <Select
        showSearch
        allowClear
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        style={{ width: '100%' }}
        optionFilterProp="rawLabel"
        filterOption={(input, option: any) => String(option?.rawLabel || '').includes(input.toLowerCase())}
        options={options}
        notFoundContent={modelLoading ? <Spin size="small" /> : <span style={{ color: '#94a3b8' }}>暂无可选模型</span>}
      />
      {hint && (
        <div style={{ marginTop: 6, fontSize: 12, color: hint.startsWith('未选') ? '#94a3b8' : '#fa8c16' }}>
          {hint.startsWith('未选') ? null : <span style={{ color: '#fa8c16', marginRight: 4 }}>⚠</span>}
          {hint}
        </div>
      )}
    </div>
  );
}

export default function AgentPage() {
  const navigate = useNavigate();
  const { hasPermi } = useAuth();
  const canViewFuncConfig = hasPermi('aid:funcconfig:list');
  const canViewModelConfig = hasPermi('aid:aidmodel:list');
  // 字典数据
  const [models, setModels] = useState<ModelOption[]>([]);
  const [modelLoading, setModelLoading] = useState(false);
  const [funcs, setFuncs] = useState<FuncOption[]>([]);
  const [funcLoading, setFuncLoading] = useState(false);

  // 官方提示词开发教程地址（后端从缓存返回，随更新清单静默刷新）
  const [promptDocsUrl, setPromptDocsUrl] = useState<string>('');

  // 列表
  const [list, setList] = useState<AgentItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [searchForm] = Form.useForm<SearchForm>();
  const [searchValues, setSearchValues] = useState<SearchForm>({});
  const [retireState, setRetireState] = useState<{
    open: boolean;
    loading: boolean;
    submitting: boolean;
    row?: AgentItem;
    impact?: OrchestrationImpact;
    replacements: { label: string; value: string }[];
  }>({ open: false, loading: false, submitting: false, replacements: [] });

  // 查看
  const [viewOpen, setViewOpen] = useState(false);
  const [viewLoading, setViewLoading] = useState(false);
  const [viewData, setViewData] = useState<AgentItem | null>(null);

  // 新建 / 编辑
  const [editOpen, setEditOpen] = useState(false);
  const [editMode, setEditMode] = useState<'create' | 'edit'>('create');
  const [editLoading, setEditLoading] = useState(false);
  const [editForm] = Form.useForm<AgentItem>();
  const [saving, setSaving] = useState(false);

  // 拉模型 / 功能配置（仅一次）
  useEffect(() => {
    let cancelled = false;
    setModelLoading(true);
    listModel({ pageNum: 1, pageSize: 999 })
      .then((res: any) => {
        if (cancelled) return;
        const items: ModelOption[] = (res.rows || res.data || [])
          .map((m: any) => ({
            id: Number(m.id),
            modelCode: m.modelCode,
            realModelCode: m.realModelCode,
            modelName: m.modelName,
            modelType: m.modelType,
            providerName: m.providerName
          }))
          .filter((m: ModelOption) => m.id > 0 && m.modelCode);
        setModels(items);
      })
      .finally(() => { if (!cancelled) setModelLoading(false); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setFuncLoading(true);
    listFuncconfig({ pageNum: 1, pageSize: 999 })
      .then((res: any) => {
        if (cancelled) return;
        const items: FuncOption[] = (res.rows || res.data || [])
          .map((f: any) => ({
            id: Number(f.id),
            funcCode: f.funcCode,
            funcName: f.funcName,
            modelType: f.modelType,
            generateMode: f.generateMode,
            modelIds: f.modelIds,
            status: f.status
          }))
          .filter((f: FuncOption) => f.funcCode);
        setFuncs(items);
      })
      .finally(() => { if (!cancelled) setFuncLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // 拉官方教程文档地址（仅一次，后端读缓存不回源）
  useEffect(() => {
    let cancelled = false;
    getDocLinks()
      .then((res: any) => {
        if (cancelled) return;
        const links = res.data || res;
        if (links?.promptDocsUrl) setPromptDocsUrl(links.promptDocsUrl);
      })
      .catch(() => { /* 教程入口拿不到地址时静默隐藏 */ });
    return () => { cancelled = true; };
  }, []);

  // funcCode → funcName 反向映射，列表展示用
  const funcCodeNameMap = useMemo(() => {
    const m = new Map<string, string>();
    funcs.forEach((f) => f.funcCode && m.set(f.funcCode, f.funcName || f.funcCode));
    return m;
  }, [funcs]);

  // funcCode 全量下拉数据，搜索区用
  const funcOptionsForSearch = useMemo(() => {
    return funcs
      .filter((f) => f.funcCode)
      .map((f) => ({
        label: f.funcName ? `${f.funcName} (${f.funcCode})` : f.funcCode,
        value: f.funcCode
      }));
  }, [funcs]);

  // 拉智能体列表
  const loadList = async (pn = pageNum, ps = pageSize, sv = searchValues) => {
    setLoading(true);
    try {
      const res: any = await listAgent({ pageNum: pn, pageSize: ps, ...sv });
      setList(res.rows || res.data || []);
      setTotal(res.total ?? (res.rows || []).length);
    } finally { setLoading(false); }
  };

  useEffect(() => {
    loadList(pageNum, pageSize, searchValues);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageNum, pageSize, searchValues]);

  // 概览统计
  const stats = useMemo(() => {
    const enabled = list.filter((a) => Number(a.status) === 1).length;
    const disabled = list.filter((a) => Number(a.status) !== 1).length;
    const noModel = list.filter((a) => !a.modelCode).length;
    return { total, enabled, disabled, noModel };
  }, [list, total]);

  // === 操作 ===
  const handleSearch = () => {
    const values = searchForm.getFieldsValue();
    // 过滤空值，避免后端解析空字符串
    const cleaned: SearchForm = {};
    if (values.agentCode) cleaned.agentCode = values.agentCode.trim();
    if (values.name) cleaned.name = values.name.trim();
    if (values.bizCategoryCode) cleaned.bizCategoryCode = values.bizCategoryCode;
    if (values.status !== undefined && values.status !== null && (values.status as any) !== '') {
      cleaned.status = Number(values.status);
    }
    setPageNum(1);
    setSearchValues(cleaned);
  };

  const handleResetSearch = () => {
    searchForm.resetFields();
    setPageNum(1);
    setSearchValues({});
  };

  const handleView = async (row: AgentItem) => {
    setViewOpen(true);
    setViewLoading(true);
    setViewData(null);
    try {
      const res: any = await getAgent(row.id);
      setViewData(res.data || res);
    } finally { setViewLoading(false); }
  };

  const handleCopy = (text?: string) => {
    if (!text) {
      message.warning('内容为空');
      return;
    }
    if (navigator?.clipboard) {
      navigator.clipboard.writeText(text).then(
        () => message.success('已复制'),
        () => message.error('复制失败')
      );
    } else {
      message.error('当前浏览器不支持复制');
    }
  };

  const openCreate = () => {
    setEditMode('create');
    setEditLoading(false);
    editForm.resetFields();
    editForm.setFieldsValue({ status: 1 } as any);
    setEditOpen(true);
  };

  const openEdit = async (row: AgentItem) => {
    setEditMode('edit');
    setEditOpen(true);
    setEditLoading(true);
    editForm.resetFields();
    try {
      const res: any = await getAgent(row.id);
      const d = res.data || res;
      editForm.setFieldsValue(d);
    } finally { setEditLoading(false); }
  };

  const handleSave = async () => {
    const values = await editForm.validateFields();
    const payload: any = { ...values };
    if (payload.status === '' || payload.status == null) {
      delete payload.status;
    } else {
      payload.status = Number(payload.status);
    }
    setSaving(true);
    try {
      if (editMode === 'create') {
        await createAgent(payload);
        message.success('新增成功');
      } else {
        await updateAgent(payload);
        message.success('修改成功');
      }
      setEditOpen(false);
      loadList();
    } finally { setSaving(false); }
  };

  const handleDelete = async (row: AgentItem) => {
    setRetireState({ open: true, loading: true, submitting: false, row, replacements: [] });
    try {
      const [impactRes, candidatesRes]: any[] = await Promise.all([
        getAgentRetirementImpact(row.id),
        row.bizCategoryCode
          ? listAgentByBizCategoryAdmin(row.bizCategoryCode)
          : Promise.resolve({ data: [] })
      ]);
      const replacements = (candidatesRes.data || [])
        .filter((agent: AgentItem) => agent.id !== row.id && agent.status === 1)
        .map((agent: AgentItem) => ({
          value: agent.agentCode,
          label: `${agent.name}（${agent.agentCode}）`
        }));
      setRetireState({
        open: true,
        loading: false,
        submitting: false,
        row,
        impact: impactRes.data,
        replacements
      });
    } catch {
      setRetireState({ open: false, loading: false, submitting: false, replacements: [] });
    }
  };

  const confirmRetireAgent = async (replacementCode?: string) => {
    if (!retireState.row) return;
    setRetireState((state) => ({ ...state, submitting: true }));
    try {
      await retireAgent(retireState.row.id, replacementCode);
      message.success(replacementCode ? '智能体引用已替换并完成下线' : '智能体引用已清理并完成下线');
      setRetireState({ open: false, loading: false, submitting: false, replacements: [] });
      loadList();
    } finally {
      setRetireState((state) => state.open ? { ...state, submitting: false } : state);
    }
  };

  const handleRemoteUpdate = () => {
    message.info('远程更新功能即将上线');
  };

  // 表单值变化：业务分类切换时校验当前 modelCode 是否还在新分类的可选池里，不在则清空
  const handleEditFormChange = (changed: Partial<AgentItem>) => {
    if (!Object.prototype.hasOwnProperty.call(changed, 'bizCategoryCode')) return;
    const currentModel = editForm.getFieldValue('modelCode');
    if (!currentModel) return;
    const newBiz = changed.bizCategoryCode;
    if (!newBiz) {
      editForm.setFieldsValue({ modelCode: undefined } as any);
      return;
    }
    const cfg = funcs.find((f) => f.funcCode === newBiz);
    if (!cfg) {
      editForm.setFieldsValue({ modelCode: undefined } as any);
      return;
    }
    const allowed = new Set(parseModelIds(cfg.modelIds));
    const cur = models.find((m) => m.modelCode === currentModel);
    if (!cur || !allowed.has(cur.id)) {
      editForm.setFieldsValue({ modelCode: undefined } as any);
    }
  };

  // === 跨页交互 ===

  const openInNewTab = (path: string) => {
    window.open(resolveAppUrl(path), '_blank', 'noopener,noreferrer');
  };

  /** 在当前 SPA 内跳转到目标路由；同时支持中键 / Ctrl 点击新开窗口 */
  const jumpInApp = (path: string, e?: React.MouseEvent) => {
    if (e && (e.metaKey || e.ctrlKey || e.button === 1)) {
      openInNewTab(path);
      return;
    }
    navigate(path);
  };

  /** 用搜索条件高亮过滤当前页（funcCode 或 modelCode） */
  const filterByFuncCode = (code: string) => {
    searchForm.setFieldsValue({ bizCategoryCode: code, agentCode: '', name: '', status: undefined } as any);
    setPageNum(1);
    setSearchValues({ bizCategoryCode: code });
    message.success('已按业务分类过滤');
  };

  /** 业务分类标签的下拉菜单：跳转 / 复制 / 仅看本分类 */
  const getFuncMenu = (_funcCode: string): MenuProps['items'] => [
    {
      key: 'open',
      icon: <ExportOutlined />,
      label: '前往「AI模型功能配置」',
      disabled: !canViewFuncConfig
    },
    {
      key: 'open-blank',
      icon: <ArrowRightOutlined />,
      label: '在新窗口打开',
      disabled: !canViewFuncConfig
    },
    { type: 'divider' as const },
    {
      key: 'filter',
      icon: <FilterOutlined />,
      label: '仅看本业务分类'
    },
    {
      key: 'copy',
      icon: <CopyOutlined />,
      label: '复制功能编码'
    }
  ];

  const onFuncMenuClick = (funcCode: string): MenuProps['onClick'] => ({ key, domEvent }) => {
    domEvent?.stopPropagation?.();
    if (key === 'open') {
      jumpInApp('/ai-model/funcconfig');
    } else if (key === 'open-blank') {
      openInNewTab('/ai-model/funcconfig');
    } else if (key === 'filter') {
      filterByFuncCode(funcCode);
    } else if (key === 'copy') {
      handleCopy(funcCode);
    }
  };

  /** 默认模型 code 的下拉菜单 */
  const getModelMenu = (_modelCode: string): MenuProps['items'] => [
    {
      key: 'open',
      icon: <ExportOutlined />,
      label: '前往「AI模型配置」',
      disabled: !canViewModelConfig
    },
    {
      key: 'open-blank',
      icon: <ArrowRightOutlined />,
      label: '在新窗口打开',
      disabled: !canViewModelConfig
    },
    { type: 'divider' as const },
    {
      key: 'copy',
      icon: <CopyOutlined />,
      label: '复制模型编码'
    }
  ];

  const onModelMenuClick = (modelCode: string): MenuProps['onClick'] => ({ key, domEvent }) => {
    domEvent?.stopPropagation?.();
    if (key === 'open') {
      jumpInApp('/ai-model/modelconfig');
    } else if (key === 'open-blank') {
      openInNewTab('/ai-model/modelconfig');
    } else if (key === 'copy') {
      handleCopy(modelCode);
    }
  };

  // 列定义
  const columns: ColumnsType<AgentItem> = [
    { title: 'ID', dataIndex: 'id', width: 70, align: 'center' },
    {
      title: '智能体编码',
      dataIndex: 'agentCode',
      width: 220,
      ellipsis: true,
      render: (v: string) => v
        ? (
            <Tooltip title={v}>
              <code style={{ background: '#f1f5f9', color: '#475569', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>{v}</code>
            </Tooltip>
          )
        : '-'
    },
    { title: '名称', dataIndex: 'name', width: 180, ellipsis: true, render: (v: string) => <span style={{ fontWeight: 500 }}>{v}</span> },
    {
      title: '图标',
      dataIndex: 'iconUrl',
      width: 80,
      align: 'center',
      render: (v: string) => v
        ? (
            <Image
              src={v}
              width={40}
              height={40}
              style={{ objectFit: 'cover', borderRadius: 8 }}
              preview={{ mask: <EyeOutlined /> }}
            />
          )
        : <span style={{ color: '#cbd5e1' }}>-</span>
    },
    {
      title: '业务分类(功能)',
      dataIndex: 'bizCategoryCode',
      width: 240,
      ellipsis: true,
      render: (v: string) => {
        if (!v) return <span style={{ color: '#94a3b8' }}>-</span>;
        const fname = funcCodeNameMap.get(v);
        return (
          <Dropdown menu={{ items: getFuncMenu(v), onClick: onFuncMenuClick(v) }} trigger={['click']} placement="bottomLeft">
            <Space size={4} className="agent-link-tag" style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center' }}>
              <Tag color="geekblue" style={{ borderRadius: 6, marginRight: 0 }}>{v}</Tag>
              {fname && fname !== v && <span style={{ color: '#64748b', fontSize: 12 }}>{fname}</span>}
              <ArrowRightOutlined style={{ fontSize: 11, color: '#94a3b8', transition: 'transform 0.2s ease, color 0.2s ease' }} className="agent-link-arrow" />
            </Space>
          </Dropdown>
        );
      }
    },
    {
      title: '默认模型',
      dataIndex: 'modelCode',
      width: 220,
      ellipsis: true,
      render: (v: string) => {
        if (!v) return <span style={{ color: '#94a3b8' }}>未配置</span>;
        return (
          <Dropdown menu={{ items: getModelMenu(v), onClick: onModelMenuClick(v) }} trigger={['click']} placement="bottomLeft">
            <Space size={4} className="agent-link-tag" style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center' }}>
              <code style={{ background: '#f0f9ff', color: '#0369a1', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>{v}</code>
              <ArrowRightOutlined style={{ fontSize: 11, color: '#94a3b8', transition: 'transform 0.2s ease, color 0.2s ease' }} className="agent-link-arrow" />
            </Space>
          </Dropdown>
        );
      }
    },
    {
      title: '副标题',
      dataIndex: 'subTitle',
      width: 180,
      ellipsis: true,
      render: (v: string) => v || <span style={{ color: '#cbd5e1' }}>-</span>
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      align: 'center',
      render: (v: number) => Number(v) === 1
        ? <Tag color="success" icon={<CheckCircleOutlined />} style={{ borderRadius: 6 }}>启用</Tag>
        : <Tag color="default" icon={<PauseCircleOutlined />} style={{ borderRadius: 6 }}>停用</Tag>
    },
    { title: '创建时间', dataIndex: 'createTime', width: 160, render: (v: string) => v || '-' },
    {
      title: '操作',
      key: 'ops',
      fixed: 'right',
      width: 240,
      align: 'center',
      render: (_: any, r: AgentItem) => (
        <Space size={0}>
          <Auth permission="aid:agent:query">
            <Tooltip title="查看"><Button type="link" size="small" icon={<EyeOutlined />} onClick={() => handleView(r)} /></Tooltip>
          </Auth>
          <Auth permission="aid:agent:edit">
            <Tooltip title="编辑"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} /></Tooltip>
          </Auth>
          <Tooltip title="复制编码"><Button type="link" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(r.agentCode)} /></Tooltip>
          <Auth permission="aid:agent:remove">
            <Tooltip title="受控下线"><Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r)} /></Tooltip>
          </Auth>
        </Space>
      )
    }
  ];

  // 编辑表单 ModelCodeSelect 切换业务分类时联动校验
  return (
    <div className="crud-page">
      {/* 局部样式：业务分类 / 默认模型可点击元素的悬浮反馈 */}
      <style>{`
        .agent-link-tag {
          padding: 2px 6px;
          border-radius: 6px;
          transition: background 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
        }
        .agent-link-tag:hover {
          background: rgba(99, 102, 241, 0.08);
          box-shadow: 0 1px 4px rgba(99, 102, 241, 0.15);
        }
        .agent-link-tag:hover .agent-link-arrow {
          transform: translateX(2px);
          color: #6366f1 !important;
        }
        .agent-link-tag:active {
          transform: scale(0.97);
        }
      `}</style>

      <Card className="page-card" bordered={false}>
        {/* 顶部标题区 */}
        <PageHeader
          title={<><ThunderboltOutlined />智能体管理</>}
          desc="管理业务智能体（system prompt + 默认模型 + 业务分类联动），后续将支持远程模板拉取与版本回退"
          extra={(
            <Space wrap>
              <Auth permission="aid:agent:add">
                <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增智能体</Button>
              </Auth>
              {promptDocsUrl && (
                <Tooltip title="打开官方提示词开发教程（入参、出参与 AI 生成指引）">
                  <Button icon={<BookOutlined />} href={promptDocsUrl} target="_blank" rel="noreferrer">提示词开发教程</Button>
                </Tooltip>
              )}
              <Tooltip title="远程更新功能即将上线">
                <Button icon={<CloudDownloadOutlined />} onClick={handleRemoteUpdate}>远程更新</Button>
              </Tooltip>
              <Tooltip title="刷新列表">
                <Button icon={<ReloadOutlined />} onClick={() => loadList()} />
              </Tooltip>
            </Space>
          )}
        />

        {/* 业务说明 */}
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 18 }}
          message="业务分类编码 = 功能编码"
          description={
            <span>
              智能体的「业务分类编码」直接对应「<b>AI模型功能配置</b>」里的功能编码（func_code）。选择业务分类后，下方「默认模型」下拉会自动收紧到该功能允许的模型池；C 端调用业务接口时，如果用户传入的模型不在池内，后端会拦截。
              <br />
              如果某个业务分类还没在「AI模型功能配置」里登记，请先去登记，再回来配置智能体。
              <br />
              系统内置的是官方简化版提示词，可直接使用；如需深度定制每个智能体的提示词（入参、出参、AI 生成方法），请查看
              {promptDocsUrl
                ? <a href={promptDocsUrl} target="_blank" rel="noreferrer"><b>官方提示词开发教程</b></a>
                : <b>官方提示词开发教程</b>}
              。
            </span>
          }
        />

        {/* 概览统计 */}
        <Row gutter={[14, 14]} style={{ marginBottom: 18 }}>
          <Col xs={12} sm={6}>
            <StatCard label="智能体总数" value={stats.total} icon={<ThunderboltOutlined />} color="#2563eb" />
          </Col>
          <Col xs={12} sm={6}>
            <StatCard label="已启用（当前页）" value={stats.enabled} icon={<CheckCircleOutlined />} color="#10b981" />
          </Col>
          <Col xs={12} sm={6}>
            <StatCard label="已停用（当前页）" value={stats.disabled} icon={<PauseCircleOutlined />} color="#64748b" />
          </Col>
          <Col xs={12} sm={6}>
            <StatCard label="未配默认模型" value={stats.noModel} icon={<FileTextOutlined />} color="#f59e0b" />
          </Col>
        </Row>

        {/* 搜索条件 */}
        <Card size="small" bordered={false} style={{ marginBottom: 14, background: '#f8fafc', borderRadius: 10 }}>
          <Form form={searchForm} layout="inline" onFinish={handleSearch} style={{ rowGap: 10 }}>
            <Form.Item name="agentCode" label="编码" style={{ marginBottom: 0 }}>
              <Input allowClear placeholder="智能体编码" style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="name" label="名称" style={{ marginBottom: 0 }}>
              <Input allowClear placeholder="智能体名称" style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="bizCategoryCode" label="业务分类" style={{ marginBottom: 0 }}>
              <Select
                allowClear
                showSearch
                style={{ width: 240 }}
                placeholder="选择业务分类"
                options={funcOptionsForSearch}
                filterOption={(i, o) => String(o?.label || '').toLowerCase().includes(i.toLowerCase())}
              />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ marginBottom: 0 }}>
              <Select allowClear style={{ width: 110 }} placeholder="状态" options={STATUS_OPTIONS} />
            </Form.Item>
            <Form.Item style={{ marginBottom: 0 }}>
              <Space>
                <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>查询</Button>
                <Button icon={<SyncOutlined />} onClick={handleResetSearch}>重置</Button>
              </Space>
            </Form.Item>
          </Form>
        </Card>

        {/* 表格 */}
        <Table
          rowKey="id"
          size="middle"
          loading={loading}
          dataSource={list}
          columns={columns}
          scroll={{ x: 1460 }}
          pagination={{
            current: pageNum,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, ps) => {
              setPageNum(p);
              setPageSize(ps);
            }
          }}
        />
      </Card>

      {/* === 查看弹窗 === */}
      <Modal
        open={viewOpen}
        title={
          <Space>
            <EyeOutlined style={{ color: '#2563eb' }} />
            <span>查看智能体</span>
            {viewData?.name && <Tag color="blue" style={{ marginLeft: 4, borderRadius: 6 }}>{viewData.name}</Tag>}
          </Space>
        }
        onCancel={() => setViewOpen(false)}
        footer={<Button onClick={() => setViewOpen(false)}>关闭</Button>}
        width={960}
        destroyOnClose
      >
        {viewLoading ? (
          <div style={{ textAlign: 'center', padding: 60, color: '#94a3b8' }}>加载中...</div>
        ) : !viewData ? (
          <div style={{ textAlign: 'center', padding: 60, color: '#94a3b8' }}>暂无数据</div>
        ) : (
          <div>
            <Row gutter={[12, 12]} style={{ marginBottom: 14 }}>
              <Col span={24}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>智能体图标</div>
                {viewData.iconUrl
                  ? <Image src={viewData.iconUrl} width={72} height={72} style={{ objectFit: 'cover', borderRadius: 10 }} />
                  : <span style={{ color: '#94a3b8' }}>-</span>}
              </Col>
              <Col span={12}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>智能体编码</div>
                <code style={{ background: '#f1f5f9', color: '#475569', padding: '4px 10px', borderRadius: 6, fontSize: 13 }}>{viewData.agentCode}</code>
              </Col>
              <Col span={12}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>状态</div>
                {Number(viewData.status) === 1
                  ? <Tag color="success" icon={<CheckCircleOutlined />} style={{ borderRadius: 6 }}>启用</Tag>
                  : <Tag color="default" icon={<PauseCircleOutlined />} style={{ borderRadius: 6 }}>停用</Tag>}
              </Col>
              <Col span={12}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>业务分类（功能）</div>
                {viewData.bizCategoryCode ? (
                  <Dropdown
                    menu={{ items: getFuncMenu(viewData.bizCategoryCode), onClick: onFuncMenuClick(viewData.bizCategoryCode) }}
                    trigger={['click']}
                    placement="bottomLeft"
                  >
                    <Space size={4} className="agent-link-tag" style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center' }}>
                      <Tag color="geekblue" style={{ borderRadius: 6, marginRight: 0 }}>{viewData.bizCategoryCode}</Tag>
                      {funcCodeNameMap.get(viewData.bizCategoryCode) && funcCodeNameMap.get(viewData.bizCategoryCode) !== viewData.bizCategoryCode && (
                        <span style={{ color: '#64748b', fontSize: 12 }}>{funcCodeNameMap.get(viewData.bizCategoryCode)}</span>
                      )}
                      <ArrowRightOutlined className="agent-link-arrow" style={{ fontSize: 11, color: '#94a3b8', transition: 'transform 0.2s ease, color 0.2s ease' }} />
                    </Space>
                  </Dropdown>
                ) : <span style={{ color: '#94a3b8' }}>-</span>}
              </Col>
              <Col span={12}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>默认模型</div>
                {viewData.modelCode ? (
                  <Dropdown
                    menu={{ items: getModelMenu(viewData.modelCode), onClick: onModelMenuClick(viewData.modelCode) }}
                    trigger={['click']}
                    placement="bottomLeft"
                  >
                    <Space size={4} className="agent-link-tag" style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center' }}>
                      <code style={{ background: '#f0f9ff', color: '#0369a1', padding: '4px 10px', borderRadius: 6, fontSize: 13 }}>{viewData.modelCode}</code>
                      <ArrowRightOutlined className="agent-link-arrow" style={{ fontSize: 11, color: '#94a3b8', transition: 'transform 0.2s ease, color 0.2s ease' }} />
                    </Space>
                  </Dropdown>
                ) : <span style={{ color: '#94a3b8' }}>未配置</span>}
              </Col>
              <Col span={24}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>副标题</div>
                <div>{viewData.subTitle || <span style={{ color: '#94a3b8' }}>-</span>}</div>
              </Col>
              <Col span={24}>
                <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 4 }}>介绍</div>
                <div style={{ background: '#fafbff', padding: '10px 14px', borderRadius: 8, fontSize: 13, color: '#1f2937', minHeight: 36 }}>
                  {viewData.introduction || <span style={{ color: '#94a3b8' }}>-</span>}
                </div>
              </Col>
              <Col span={24}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                  <span style={{ fontSize: 12, color: '#94a3b8' }}>系统提示词正文</span>
                  <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(viewData?.promptContent)}>复制</Button>
                </div>
                <pre style={{
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  background: 'linear-gradient(180deg, #fafbff 0%, #f5f7fb 100%)',
                  padding: '18px 20px',
                  borderRadius: 12,
                  border: '1px solid rgba(15, 23, 42, 0.06)',
                  maxHeight: 480,
                  overflow: 'auto',
                  fontFamily: 'Consolas, Menlo, Monaco, "Courier New", monospace',
                  fontSize: 13.5,
                  lineHeight: 1.8,
                  color: '#1f2937',
                  margin: 0
                }}>
                  {viewData.promptContent || '（未配置）'}
                </pre>
              </Col>
            </Row>
          </div>
        )}
      </Modal>

      {/* === 新建 / 编辑弹窗 === */}
      <Modal
        open={editOpen}
        title={
          <Space>
            {editMode === 'create'
              ? <PlusOutlined style={{ color: '#16a34a' }} />
              : <EditOutlined style={{ color: '#2563eb' }} />}
            <span>{editMode === 'create' ? '新增智能体' : '修改智能体'}</span>
          </Space>
        }
        onCancel={() => setEditOpen(false)}
        onOk={handleSave}
        confirmLoading={saving}
        width={960}
        destroyOnClose
        maskClosable={false}
      >
        <Spin spinning={editLoading}>
          <Form form={editForm} layout="vertical" style={{ marginTop: 8 }} onValuesChange={handleEditFormChange}>
            <Form.Item name="id" hidden><Input /></Form.Item>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="agentCode"
                  label="智能体编码"
                  rules={[{ required: true, message: '智能体编码不能为空' }]}
                  tooltip="业务唯一标识，例如 aid_casting_director"
                >
                  <Input maxLength={100} placeholder="例如 aid_casting_director" disabled={editMode === 'edit'} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="name" label="智能体名称" rules={[{ required: true, message: '名称不能为空' }]}>
                  <Input maxLength={100} placeholder="请输入智能体名称" />
                </Form.Item>
              </Col>

              <Col span={12}>
                <Form.Item
                  name="bizCategoryCode"
                  label="业务分类（功能编码）"
                  rules={[{ required: true, message: '业务分类不能为空' }]}
                >
                  <BizCategoryCodeSelect funcs={funcs} loading={funcLoading} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="status" label="状态" initialValue={1}>
                  <Select options={STATUS_OPTIONS} />
                </Form.Item>
              </Col>

              <Col span={12}>
                <Form.Item name="subTitle" label="副标题">
                  <Input maxLength={200} placeholder="可选，列表页副标题" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item shouldUpdate={(prev, cur) => prev.bizCategoryCode !== cur.bizCategoryCode || prev.modelCode !== cur.modelCode}>
                  {() => (
                    <Form.Item label="默认模型" name="modelCode" style={{ marginBottom: 0 }}>
                      <ModelCodeSelect
                        models={models}
                        modelLoading={modelLoading}
                        funcs={funcs}
                        bizCategoryCode={editForm.getFieldValue('bizCategoryCode')}
                      />
                    </Form.Item>
                  )}
                </Form.Item>
              </Col>

              <Col span={24}>
                <Form.Item
                  name="iconUrl"
                  label="智能体图标"
                  tooltip="上传后随智能体信息返回给 C 端展示；建议正方形图片"
                >
                  <ImageUpload
                    maxCount={1}
                    maxSize={5}
                    accept="image/*"
                  />
                </Form.Item>
              </Col>

              <Col span={24}>
                <Form.Item name="introduction" label="介绍">
                  <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} maxLength={500} showCount placeholder="介绍文案，对内可见" />
                </Form.Item>
              </Col>

              <Col span={24}>
                <Form.Item
                  name="promptContent"
                  label={
                    <Space size={8}>
                      <span>系统提示词正文</span>
                      {promptDocsUrl && (
                        <a href={promptDocsUrl} target="_blank" rel="noreferrer" style={{ fontSize: 12, fontWeight: 400 }}>
                          <BookOutlined /> 提示词开发教程
                        </a>
                      )}
                    </Space>
                  }
                  rules={[{ required: true, message: '系统提示词不能为空' }]}
                  tooltip="C 端不会返回此字段，仅作业务调用 LLM 时的 system prompt；系统内置官方简化版提示词，深度定制请参考官方教程"
                >
                  <Input.TextArea
                    autoSize={{ minRows: 10, maxRows: 22 }}
                    maxLength={AGENT_PROMPT_MAX_LENGTH}
                    showCount
                    placeholder="请输入 system prompt"
                    style={{ fontFamily: 'Consolas, Menlo, Monaco, "Courier New", monospace', lineHeight: 1.8, fontSize: 13.5 }}
                  />
                </Form.Item>
              </Col>

              <Col span={24}>
                <Form.Item name="remark" label="备注">
                  <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={500} showCount />
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Spin>
      </Modal>
      <RetirementModal
        open={retireState.open}
        loading={retireState.loading}
        submitting={retireState.submitting}
        impact={retireState.impact}
        replacementOptions={retireState.replacements}
        onCancel={() => setRetireState({ open: false, loading: false, submitting: false, replacements: [] })}
        onConfirm={confirmRetireAgent}
      />
    </div>
  );
}
