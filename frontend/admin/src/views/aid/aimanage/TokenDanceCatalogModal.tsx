import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Descriptions, Divider, Empty, Input, Modal, Select, Space, Spin,
  Table, Tag, Tooltip, Typography, message
} from 'antd';
import { CloudDownloadOutlined, EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import type { Provider } from './types';
import {
  getTokenDanceCatalogDetail,
  getTokenDanceCatalogStatus,
  importTokenDanceCatalogModels,
  listTokenDanceCatalog,
  previewTokenDanceCatalogModel,
  refreshTokenDanceCatalog,
  repairTokenDanceEmptyPricing,
  type TokenDanceCatalogCompatibility,
  type TokenDanceCatalogCostItem,
  type TokenDanceCatalogDetail,
  type TokenDanceCatalogPreview,
  type TokenDanceCatalogStatus,
  type TokenDanceCatalogSummary
} from '@/api/aid/aimanage';

interface Props {
  open: boolean;
  provider: Provider | null;
  onClose: () => void;
  onImported?: () => void | Promise<void>;
}

interface CostRow {
  key: string;
  kind: '阶梯' | 'SKU';
  rate: string | number;
  unit?: string;
  description?: string;
  conditions?: Record<string, unknown>;
}

const PROTOCOL_LABELS: Record<string, string> = {
  'openai:chat-completions': 'OpenAI Chat Completions',
  'openai:responses': 'OpenAI Responses',
  'anthropic:messages': 'Anthropic Messages',
  'ark:image-generations': '方舟图片生成',
  'openai:image-generations': 'OpenAI 图片生成',
  'seedance:generations': 'Seedance 视频任务',
  'kling:text2video': '可灵文生视频',
  'kling:image2video': '可灵图生视频',
  'kling:omni-video': '可灵 Omni 视频',
  'wan3:video-synthesis': '通义 Wan3 视频',
  'happyhorse:video-synthesis': '通义舞动视频',
  'minimax:video_generation_v2': 'MiniMax 视频 V2',
  'minimax:t2a_v2': 'MiniMax 配音',
  'minimax:t2a_v2_ws': 'MiniMax 流式配音',
  'minimax:voice_clone': 'MiniMax 音色复刻',
  'ark:tts': '方舟配音',
  'ark:tts_ws': '方舟流式配音'
};

const FIELD_LABELS: Record<string, string> = {
  resolution: '清晰度', duration: '时长', size: '尺寸', aspectRatio: '比例',
  item: '计量项', unit: '区间单位', lower: '下限', upper: '上限',
  quality: '质量', audio: '生成音频', imageCount: '图片数量',
  inputVideo: '输入视频', inputImage: '输入图片',
  inputModalities: '输入模态', outputModalities: '输出模态', allowedInputs: '允许输入',
  sizeOptions: '尺寸/清晰度', aspectRatioOptions: '宽高比', durationOptions: '生成时长(秒)',
  defaultSize: '默认尺寸/清晰度', defaultAspectRatio: '默认宽高比',
  defaultDurationSeconds: '默认生成时长(秒)', allowCustomWH: '允许自定义宽高',
  outputFormatOptions: '输出格式', defaultOutputFormat: '默认输出格式',
  maxPromptCharacters: '非中日韩提示词最大字符', maxPromptCharactersCjk: '中日韩提示词最大字符', contextWindowTokens: '上下文 Token', maxOutputTokens: '最大输出 Token',
  maxInputImages: '输入图片上限', maxInputVideos: '输入视频上限',
  maxInputAudios: '输入音频上限', maxInputDocuments: '输入文档上限', maxInputDocumentPages: '输入文档页数上限',
  inputImageFormats: '输入图片格式', inputVideoFormats: '输入视频格式',
  minInputVideoDurationSeconds: '单个视频最短秒数', maxInputVideoDurationSeconds: '单个视频最长秒数',
  maxInputVideoTotalDurationSeconds: '视频总秒数',
  minInputAudioDurationSeconds: '单个音频最短秒数', maxInputAudioDurationSeconds: '单个音频最长秒数',
  maxInputAudioTotalDurationSeconds: '音频总秒数',
  inputAudioFormats: '输入音频格式', inputDocumentFormats: '输入文档格式',
  maxInputImageFileSizeMb: '单张输入图片上限(MB)', maxInputVideoFileSizeMb: '单个输入视频上限(MB)',
  maxInputAudioFileSizeMb: '单个输入音频上限(MB)', maxInputDocumentFileSizeMb: '单个输入文档上限(MB)',
  maxReferenceImages: '参考图片上限', minReferenceImages: '参考图片下限',
  referenceImageFormats: '参考图片格式',
  referenceImageMinDimensionPixels: '单图最小边长(px)', referenceImageMaxDimensionPixels: '单图最大边长(px)',
  referenceImageMinPixels: '单图最小总像素数', referenceImageMaxPixels: '单图最大总像素数',
  referenceImageMinAspectRatio: '参考图片最小宽高比', referenceImageMaxAspectRatio: '参考图片最大宽高比',
  minOutputAspectRatio: '输出图片最小宽高比', maxOutputAspectRatio: '输出图片最大宽高比',
  referenceImageMaxFileSizeMb: '单张参考图上限(MB)', maxInputMediaTotalFileSizeMb: '全部输入媒体合计(MB)',
  minReferenceVideos: '参考视频下限', maxReferenceVideos: '参考视频上限',
  referenceVideoMinDurationSeconds: '参考视频单段最短(秒)', referenceVideoMaxDurationSeconds: '参考视频单段最长(秒)',
  referenceVideoMaxTotalDurationSeconds: '参考视频总时长(秒)', referenceVideoFormats: '参考视频格式',
  referenceVideoMaxFileSizeMb: '单个参考视频上限(MB)',
  referenceVideoMinDimensionPixels: '参考视频最小边长(px)', referenceVideoMaxDimensionPixels: '参考视频最大边长(px)',
  referenceVideoMinAspectRatio: '参考视频最小宽高比', referenceVideoMaxAspectRatio: '参考视频最大宽高比',
  referenceVideoMinFps: '参考视频最低 FPS', referenceVideoMaxFps: '参考视频最高 FPS',
  minReferenceAudios: '参考音频下限', maxReferenceAudios: '参考音频上限',
  referenceAudioMinDurationSeconds: '参考音频单段最短(秒)', referenceAudioMaxDurationSeconds: '参考音频单段最长(秒)',
  referenceAudioMaxTotalDurationSeconds: '参考音频总时长(秒)', referenceAudioFormats: '参考音频格式',
  referenceAudioMaxFileSizeMb: '单个参考音频上限(MB)', maxReferenceMaterials: '全部参考素材数量上限',
  maxInputOutputVideoDurationSeconds: '输入输出视频合计(秒)', supportsAudio: '支持生成音频',
  defaultAudio: '默认生成音频', audioModeOptions: '音频模式', audioTypes: '音频类型',
  supportsBgm: '支持背景音乐', supportsVoiceId: '支持指定音色', supportsVoiceControl: '支持音色控制',
  supportsElements: '支持主体元素', maxElements: '主体元素上限', elementTypeRequired: '主体必须声明类型',
  klingScenario: '可灵协议场景', videoScenario: '通用视频场景',
  supportsVideoInput: '支持视频输入', supportsReferenceAudio: '支持参考音频',
  supportsBase64Image: '官方支持 Base64 图片', supportsReasoning: '支持思考',
  supportsStreaming: '流式输出', supportsToolCalling: '工具调用',
  supportsStructuredOutput: '结构化输出', supportsContextCaching: '上下文缓存',
  supportsBuiltinTools: '内置工具',
  supportsReasoningDisable: '允许关闭思考', supportsReasoningContent: '返回思考内容',
  supportsImageInput: '支持图片输入', supportsAudioInput: '支持音频输入', supportsDocumentInput: '支持文档输入',
  audioOperation: '音频操作', ttsTextRequired: '配音文本必填', ttsVoiceRequired: '音色必填',
  builtInVoiceOptions: '内置音色', audioFormatOptions: '音频格式', defaultAudioFormat: '默认音频格式',
  audioSampleRateOptions: '采样率', defaultAudioSampleRate: '默认采样率',
  supportsAudioStreaming: '音频流式输出', supportsTimestamp: '字句时间戳',
  speechRateMin: '最慢语速', speechRateMax: '最快语速', loudnessRateMin: '最低音量', loudnessRateMax: '最高音量',
  pitchMin: '最低音调', pitchMax: '最高音调', emotionOptions: '情绪选项', supportsEmotionScale: '情绪强度',
  voiceSampleRequired: '参考音频必填', voiceSampleFormats: '参考音频格式', voiceSampleMaxFileSizeMb: '参考音频上限(MB)'
};

const CAPABILITY_DISPLAY_FIELDS = Object.keys(FIELD_LABELS).filter((key) => ![
  'resolution', 'duration', 'size', 'aspectRatio', 'item', 'unit', 'lower', 'upper',
  'quality', 'audio', 'imageCount', 'inputVideo', 'inputImage'
].includes(key));

const statusTag = (status?: string) => {
  if (status === 'CREATED_DISABLED') return <Tag color="success">已创建（停用）</Tag>;
  if (status === 'UNCHANGED') return <Tag>已有配置未改动</Tag>;
  if (status === 'VERIFIED') return <Tag color="success">已核验</Tag>;
  return <Tag color="warning">待核验</Tag>;
};

const COMPATIBILITY_LABELS: Record<string, { label: string; color?: string }> = {
  IMPORTABLE: { label: '可导入', color: 'success' },
  AID_INCOMPATIBLE: { label: '不兼容 AID', color: 'error' },
  UPGRADE_REQUIRED: { label: '需要升级', color: 'warning' },
  PROTOCOL_UNSUPPORTED: { label: '协议不支持', color: 'error' },
  CAPABILITY_UNSUPPORTED: { label: '能力不支持', color: 'error' },
  PRICING_INCOMPLETE: { label: 'SKU 不完整', color: 'warning' },
  CATALOG_UNVERIFIED: { label: '资料待核验', color: 'warning' },
  DEPRECATED: { label: '已停用' },
  IMPORTED: { label: '已导入', color: 'blue' },
  UPDATE_AVAILABLE: { label: '有目录更新', color: 'processing' }
};

const compatibilityTag = (compatibility?: TokenDanceCatalogCompatibility) => {
  if (!compatibility) return <Tag>未知</Tag>;
  const meta = COMPATIBILITY_LABELS[compatibility.status] || { label: compatibility.status };
  return <Space direction="vertical" size={2}>
    <Tooltip title={compatibility.reason}><Tag color={meta.color}>{meta.label}</Tag></Tooltip>
    {compatibility.status === 'AID_INCOMPATIBLE' && <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {compatibility.reason}；禁止导入，目录更新不会解除此限制
    </Typography.Text>}
  </Space>;
};

const compatibleProtocol = (model: TokenDanceCatalogSummary, protocol: string) =>
  model.compatibilityByProtocol?.[protocol];

const defaultProtocol = (model: TokenDanceCatalogSummary) =>
  model.supportedProtocols.find((protocol) => compatibleProtocol(model, protocol)?.selectable)
  || model.supportedProtocols.find((protocol) => compatibleProtocol(model, protocol)?.importable)
  || model.supportedProtocols[0];

const CATALOG_SOURCE_LABELS = {
  REMOTE: '在线目录',
  CACHE: '可信缓存',
  BUNDLED: '内置目录'
} as const;

const ACTIVATION_CHECK_LABELS: Record<string, string> = {
  PROTOCOL_RUNTIME_VERIFICATION_REQUIRED: '使用有效 TokenDance 账号完成一次真实协议联调',
  PRICING_COVERAGE_REQUIRED: '补齐当前协议的全部成本计费组合',
  CREDENTIAL_REQUIRED: '先完成 TokenDance 授权',
  OFFICIAL_CAPABILITY_EVIDENCE_MISSING: '缺少可核验的模型能力资料',
  OFFICIAL_CAPABILITY_DETAILS_MISSING: '缺少模型能力细节资料',
  OFFICIAL_PROTOCOL_CAPABILITY_MISSING: '缺少当前协议的能力定义',
  CHANNEL_VARIANT_HAS_NO_ORIGINAL_VENDOR_SPEC: '渠道型号没有对应的原厂独立规格页',
  REFERENCE_IMAGE_FILE_LIMITS_MISSING: '参考图片格式、体积或尺寸上限未公开',
  MEDIA_COUNT_AND_FILE_LIMITS_NOT_PUBLISHED: '输入媒体数量、格式或文件上限未公开',
  EXACT_VENDOR_MODEL_PAGE_NOT_INDEXED: '原厂文档暂未索引到该精确型号',
  EXACT_ORIGINAL_VENDOR_MODEL_PAGE_NOT_FOUND: '暂未找到该精确型号的原厂页面',
  EXACT_VENDOR_MODEL_PAGE_NOT_FOUND: '暂未找到该精确型号的原厂页面',
  THINKING_DISABLE_BEHAVIOR_NOT_CONFIRMED: '关闭思考模式的行为尚未得到原厂确认'
};

const activationCheckLabel = (value: string) => ACTIVATION_CHECK_LABELS[value] || value;

const protocolLabel = (value: string) => PROTOCOL_LABELS[value] || value;

const scalarText = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.map(scalarText).join('、');
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, item]) => `${FIELD_LABELS[key] || key}：${scalarText(item)}`)
      .join('；');
  }
  return String(value);
};

const ConditionTags = ({ value }: { value?: Record<string, unknown> }) => {
  const entries = Object.entries(value || {});
  if (!entries.length) return <Typography.Text type="secondary">无附加条件</Typography.Text>;
  return (
    <Space size={[4, 4]} wrap>
      {entries.map(([key, item]) => (
        <Tag key={key}>{FIELD_LABELS[key] || key}：{scalarText(item)}</Tag>
      ))}
    </Space>
  );
};

const costRows = (item: TokenDanceCatalogCostItem): CostRow[] => [
  ...(item.plans || []).map((plan, index) => ({
    key: `${item.id}-plan-${index}`,
    kind: '阶梯' as const,
    rate: plan.rate,
    unit: plan.unit,
    conditions: plan.range
  })),
  ...(item.skus || []).map((sku, index) => ({
    key: `${item.id}-sku-${index}`,
    kind: 'SKU' as const,
    rate: sku.rate,
    unit: sku.unit,
    description: sku.description,
    conditions: sku.specs
  }))
];

const COST_COLUMNS: ColumnsType<CostRow> = [
  { title: '类型', dataIndex: 'kind', width: 70, render: (value) => <Tag>{value}</Tag> },
  { title: '适用条件', dataIndex: 'conditions', render: (value) => <ConditionTags value={value} /> },
  { title: '成本单价', dataIndex: 'rate', width: 120, render: (value) => <Typography.Text strong>¥{value}</Typography.Text> },
  { title: '计费单位', dataIndex: 'unit', width: 140, render: (value) => value || '-' },
  { title: '说明', dataIndex: 'description', width: 150, render: (value) => value || '-' }
];

export default function TokenDanceCatalogModal({ open, provider, onClose, onImported }: Props) {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [models, setModels] = useState<TokenDanceCatalogSummary[]>([]);
  const [catalogStatus, setCatalogStatus] = useState<TokenDanceCatalogStatus | null>(null);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [catalogPage, setCatalogPage] = useState(1);
  const [catalogPageSize, setCatalogPageSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [importing, setImporting] = useState(false);
  const [selectedProtocols, setSelectedProtocols] = useState<Record<string, string>>({});
  const [protocolChoices, setProtocolChoices] = useState<Record<string, string>>({});
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [detail, setDetail] = useState<TokenDanceCatalogDetail | null>(null);
  const [preview, setPreview] = useState<TokenDanceCatalogPreview | null>(null);
  const scopeRef = useRef(0);
  // 按搜索词合并进行中的目录请求；切换搜索词不会遗失旧请求的去重键。
  const listRequestsRef = useRef<Map<string, Promise<any>>>(new Map());
  const listSequenceRef = useRef(0);
  const previewRequestsRef = useRef<Map<string, Promise<any[]>>>(new Map());
  const previewSequenceRef = useRef(0);
  const importConfirmRef = useRef(false);
  const importRequestRef = useRef(false);
  const repairPendingRef = useRef(false);
  const statusRequestRef = useRef<Promise<any> | null>(null);
  const refreshRequestRef = useRef<Promise<any> | null>(null);
  const [repairing, setRepairing] = useState(false);
  const providerId = provider?.id;

  const loadCatalog = async (search = keyword.trim(), scope = scopeRef.current) => {
    if (!providerId) return;
    const key = `${providerId}:${search.trim()}`;
    const sequence = ++listSequenceRef.current;
    let pending = listRequestsRef.current.get(key);
    if (!pending) {
      pending = listTokenDanceCatalog(providerId, search.trim() || undefined);
      listRequestsRef.current.set(key, pending);
    }
    setLoading(true);
    try {
      const response: any = await pending;
      if (scope === scopeRef.current && sequence === listSequenceRef.current) {
        setModels(response.data || []);
        setCatalogPage(1);
        void loadStatus(scope);
      }
    } catch (error: any) {
      if (scope === scopeRef.current && sequence === listSequenceRef.current) message.error(error?.message || '在线模型目录加载失败');
    } finally {
      if (listRequestsRef.current.get(key) === pending) listRequestsRef.current.delete(key);
      if (scope === scopeRef.current && sequence === listSequenceRef.current) setLoading(false);
    }
  };

  const loadStatus = async (scope = scopeRef.current) => {
    let pending = statusRequestRef.current;
    if (!pending) {
      pending = getTokenDanceCatalogStatus();
      statusRequestRef.current = pending;
    }
    try {
      const response: any = await pending;
      if (scope === scopeRef.current) setCatalogStatus(response.data || null);
    } catch (error: any) {
      if (scope === scopeRef.current) message.error(error?.message || '目录状态加载失败');
    } finally {
      if (statusRequestRef.current === pending) statusRequestRef.current = null;
    }
  };

  const refreshCatalog = async () => {
    if (refreshRequestRef.current) return refreshRequestRef.current;
    const scope = scopeRef.current;
    setRefreshing(true);
    const pending = refreshTokenDanceCatalog();
    refreshRequestRef.current = pending;
    try {
      const response: any = await pending;
      if (scope !== scopeRef.current) return;
      const nextStatus = response.data || null;
      setCatalogStatus(nextStatus);
      await loadCatalog(keyword.trim(), scope);
      if (nextStatus?.checkError) message.warning(`远端刷新失败，已继续使用${nextStatus.source === 'CACHE' ? '可信缓存' : '内置目录'}`);
      else message.success('在线模型目录已刷新');
    } catch (error: any) {
      if (scope === scopeRef.current) message.error(error?.message || '在线模型目录刷新失败');
    } finally {
      if (refreshRequestRef.current === pending) refreshRequestRef.current = null;
      if (scope === scopeRef.current) setRefreshing(false);
    }
  };

  useEffect(() => {
    scopeRef.current += 1;
    const scope = scopeRef.current;
    setKeyword('');
    setModels([]);
    setCatalogStatus(null);
    setStatusFilter('ALL');
    setCatalogPage(1);
    setSelectedProtocols({});
    setProtocolChoices({});
    setPreviewOpen(false);
    setDetail(null);
    setPreview(null);
    // 目录加载成功后统一刷新状态，避免弹窗初始化重复查询状态。
    if (open && providerId) void loadCatalog('', scope);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, providerId]);

  const selectedKeys = useMemo(() => Object.keys(selectedProtocols), [selectedProtocols]);

  const filteredModels = useMemo(() => {
    if (statusFilter === 'ALL') return models;
    return models.filter((model) => Object.values(model.compatibilityByProtocol || {}).some((item) => {
      if (statusFilter === 'IMPORTABLE') return item.selectable;
      if (statusFilter === 'NEW') return item.status === 'IMPORTABLE';
      return item.status === statusFilter;
    }));
  }, [models, statusFilter]);

  const chooseRows = (keys: React.Key[]) => {
    const nextKeys = new Set(keys.map(String));
    setSelectedProtocols((current) => {
      const next: Record<string, string> = {};
      for (const model of filteredModels) {
        if (!nextKeys.has(model.modelId)) continue;
        const existing = current[model.modelId] || protocolChoices[model.modelId];
        const fallback = defaultProtocol(model);
        const selected = model.supportedProtocols.includes(existing) ? existing : fallback;
        if (selected && compatibleProtocol(model, selected)?.selectable) next[model.modelId] = selected;
      }
      return next;
    });
  };

  const chooseProtocol = (model: TokenDanceCatalogSummary, protocol: string) => {
    setProtocolChoices((current) => ({ ...current, [model.modelId]: protocol }));
    setSelectedProtocols((current) => {
      if (!Object.prototype.hasOwnProperty.call(current, model.modelId)) return current;
      const next = { ...current };
      if (compatibleProtocol(model, protocol)?.selectable) next[model.modelId] = protocol;
      else delete next[model.modelId];
      return next;
    });
  };

  const openPreview = async (model: TokenDanceCatalogSummary) => {
    if (!providerId) return;
    const protocol = selectedProtocols[model.modelId] || protocolChoices[model.modelId] || defaultProtocol(model);
    if (!protocol) {
      message.warning('该模型没有可用的接入协议');
      return;
    }
    const scope = scopeRef.current;
    const requestKey = `${providerId}:${model.modelId}:${protocol}`;
    const sequence = ++previewSequenceRef.current;
    let pending = previewRequestsRef.current.get(requestKey);
    if (!pending) {
      pending = Promise.all([
        getTokenDanceCatalogDetail(model.modelId),
        previewTokenDanceCatalogModel(providerId, model.modelId, protocol)
      ]);
      previewRequestsRef.current.set(requestKey, pending);
    }
    setPreviewOpen(true);
    setPreviewLoading(true);
    setDetail(null);
    setPreview(null);
    try {
      const [detailResponse, previewResponse]: any[] = await pending;
      if (scope !== scopeRef.current || sequence !== previewSequenceRef.current) return;
      setDetail(detailResponse.data || null);
      setPreview(previewResponse.data || null);
    } catch (error: any) {
      if (scope === scopeRef.current && sequence === previewSequenceRef.current) message.error(error?.message || '模型导入预览加载失败');
    } finally {
      if (previewRequestsRef.current.get(requestKey) === pending) previewRequestsRef.current.delete(requestKey);
      if (scope === scopeRef.current && sequence === previewSequenceRef.current) setPreviewLoading(false);
    }
  };

  const repairEmptyPricing = () => {
    if (!providerId || !preview?.canRepairEmptyPricing || preview.configVersion == null || repairPendingRef.current) return;
    repairPendingRef.current = true;
    const target = preview;
    const scope = scopeRef.current;
    let submitted = false;
    Modal.confirm({
      title: '使用当前目录成本修复 SKU？',
      content: '仅在当前目录 SKU 为空、全部停用，或仅存在早期目录生成的全 0 优先级时修复。不改能力、倍率和模型启用状态，不覆盖管理员改过的价格或条件，历史任务仍按原快照结算。',
      okText: '修复目录 SKU',
      onCancel: () => { repairPendingRef.current = false; },
      onOk: async () => {
        if (submitted) return;
        submitted = true;
        setRepairing(true);
        try {
          await repairTokenDanceEmptyPricing(providerId, target.modelId, target.protocol, target.configVersion!);
          message.success('目录 SKU 已修复，模型启用状态保持不变');
          if (scope === scopeRef.current) {
            setPreviewOpen(false);
            try { await onImported?.(); }
            catch { message.warning('SKU 已补齐，但模型列表刷新失败，请手动刷新'); }
          }
        } catch (error: any) {
          message.error(error?.message || '补齐失败，请重新打开预览后再试');
        } finally {
          repairPendingRef.current = false;
          setRepairing(false);
        }
      }
    });
  };

  const importSelected = () => {
    if (!providerId || !catalogStatus?.catalogVersion || importing || importConfirmRef.current
      || importRequestRef.current || !selectedKeys.length) return;
    const invalid = selectedKeys.filter((modelId) => {
      const model = models.find((item) => item.modelId === modelId);
      const protocol = selectedProtocols[modelId];
      return !model || !compatibleProtocol(model, protocol)?.selectable;
    });
    if (invalid.length) {
      message.warning('目录状态已变化，请刷新后重新选择');
      return;
    }
    importConfirmRef.current = true;
    Modal.confirm({
      title: `导入 ${selectedKeys.length} 个模型配置？`,
      content: '新模型将以停用状态创建，已有模型及其能力、SKU、倍率和状态保持不变。请完成协议、能力与成本覆盖核验后再启用。',
      okText: '确认导入',
      cancelText: '取消',
      onOk: async () => {
        if (importRequestRef.current) return;
        importRequestRef.current = true;
        setImporting(true);
        try {
          const selections = selectedKeys.map((modelId) => ({ modelId, protocol: selectedProtocols[modelId] }));
          const response: any = await importTokenDanceCatalogModels(providerId, catalogStatus.catalogVersion, selections);
          const results = response.data || [];
          const created = results.filter((item: any) => item.status === 'CREATED_DISABLED').length;
          const unchanged = results.filter((item: any) => item.status === 'UNCHANGED').length;
          message.success(`导入完成：新建并停用 ${created} 个，已有配置保持不变 ${unchanged} 个`);
          setSelectedProtocols({});
          await loadCatalog(keyword.trim(), scopeRef.current);
          try {
            await onImported?.();
          } catch {
            message.warning('模型已导入，但列表刷新失败，请手动刷新');
          }
        } catch (error: any) {
          message.error(error?.message || '模型目录导入失败');
          throw error;
        } finally {
          importRequestRef.current = false;
          importConfirmRef.current = false;
          setImporting(false);
        }
      },
      onCancel: () => { importConfirmRef.current = false; }
    });
  };

  const columns: ColumnsType<TokenDanceCatalogSummary> = [
    {
      title: '模型', dataIndex: 'name', width: 250,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{row.name}</Typography.Text>
          <Typography.Text type="secondary" copyable={{ text: row.modelId }}>{row.modelId}</Typography.Text>
        </Space>
      )
    },
    {
      title: '输入 → 输出', dataIndex: 'architecture', width: 190,
      render: (_, row) => (
        <Space size={[4, 4]} wrap>
          {(row.architecture?.input_modalities || []).map((item) => <Tag key={`in-${item}`} color="blue">{item}</Tag>)}
          <span>→</span>
          {(row.architecture?.output_modalities || []).map((item) => <Tag key={`out-${item}`} color="purple">{item}</Tag>)}
        </Space>
      )
    },
    {
      title: '接入协议', dataIndex: 'supportedProtocols', width: 235,
      render: (_, row) => {
        const selected = selectedProtocols[row.modelId] || protocolChoices[row.modelId] || defaultProtocol(row);
        return (
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Select
              value={selected}
              options={row.supportedProtocols.map((value) => {
                const compatibility = compatibleProtocol(row, value);
                return {
                  value,
                  label: protocolLabel(value),
                  disabled: compatibility ? !compatibility.importable : true
                };
              })}
              onChange={(value) => chooseProtocol(row, value)}
              style={{ width: '100%' }}
              popupMatchSelectWidth={false}
            />
            {compatibilityTag(compatibleProtocol(row, selected))}
          </Space>
        );
      }
    },
    {
      title: '成本快照', dataIndex: 'costItemCount', width: 150,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{row.costItemCount} 个计费项</Typography.Text>
          <Typography.Text type="secondary">{row.fetchedAt || '-'}</Typography.Text>
        </Space>
      )
    },
    {
      title: 'SKU 覆盖', key: 'pricing', width: 105,
      render: (_, row) => row.pricingCompleteByProtocol?.[
        selectedProtocols[row.modelId] || protocolChoices[row.modelId] || defaultProtocol(row)
      ] === true ? <Tag color="success">完整</Tag> : <Tag color="warning">待核验</Tag>
    },
    { title: '核验', dataIndex: 'verificationStatus', width: 90, render: statusTag },
    {
      title: '操作', key: 'action', width: 95, fixed: 'right',
      render: (_, row) => {
        const protocol = selectedProtocols[row.modelId] || protocolChoices[row.modelId] || defaultProtocol(row);
        const disabled = compatibleProtocol(row, protocol)?.status === 'PROTOCOL_UNSUPPORTED';
        return <Tooltip title={disabled ? '当前程序尚未实现该协议，请先升级' : undefined}>
          <Button size="small" icon={<EyeOutlined />} disabled={disabled} onClick={() => openPreview(row)}>预览</Button>
        </Tooltip>;
      }
    }
  ];

  const previewSkus = Array.isArray(preview?.billingRule?.skus) ? preview?.billingRule.skus : [];
  const allowedInputs = Array.isArray(preview?.capability?.allowedInputs)
    ? preview.capability.allowedInputs
      .filter((value) => ['string', 'number'].includes(typeof value)).map((value) => String(value)) : [];
  const capabilityEntries = CAPABILITY_DISPLAY_FIELDS
    .filter((key) => Object.prototype.hasOwnProperty.call(preview?.capability || {}, key))
    .map((key) => ({ key, value: preview?.capability?.[key] }));
  const evidence = preview?.capabilityEvidence;

  return (
    <>
      <Modal
        open={open}
        title={`${provider?.providerName || 'TokenDance'} · 在线模型目录`}
        width={1220}
        footer={null}
        onCancel={onClose}
        destroyOnClose
        maskClosable={false}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="目录由服务端验证签名、文件摘要和程序兼容性；价格是模型调用成本，不是分润价。"
          description="新模型只会导入为停用配置；已有模型的能力、SKU、倍率和启用状态不会被在线目录自动覆盖。"
        />
        {catalogStatus && (
          <div style={{ padding: 12, marginBottom: 12, border: '1px solid #f0f0f0', borderRadius: 8, background: '#fafafa' }}>
            <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 10 }} wrap>
              <Space wrap>
                <Typography.Text strong>目录 {catalogStatus.catalogVersion}</Typography.Text>
                <Tag color={catalogStatus.source === 'REMOTE' ? 'success' : 'warning'}>
                  {CATALOG_SOURCE_LABELS[catalogStatus.source]}
                </Tag>
                {catalogStatus.stale && <Tag color="warning">回退中</Tag>}
              </Space>
              <Space>
                <Button icon={<ReloadOutlined />} loading={refreshing} onClick={refreshCatalog}>刷新目录</Button>
                <Button type="link" onClick={() => navigate('/system/upgrade')}>系统升级</Button>
              </Space>
            </Space>
            <Descriptions size="small" column={4} items={[
              { key: 'app', label: '当前程序', children: catalogStatus.currentAppVersion },
              { key: 'minimum', label: '目录最低版本', children: catalogStatus.minimumAppVersion },
              { key: 'count', label: '模型数量', children: catalogStatus.modelCount },
              { key: 'loaded', label: '加载时间', children: catalogStatus.loadedAt || '-' }
            ]} />
            {catalogStatus.checkError && <Alert style={{ marginTop: 8 }} type="warning" showIcon
              message="在线源暂不可用，当前目录仍可安全使用" description={catalogStatus.checkError} />}
          </div>
        )}
        <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }} wrap>
          <Space wrap>
            <Input.Search
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              onSearch={() => loadCatalog()}
              enterButton={<SearchOutlined />}
              allowClear
              placeholder="搜索模型名称或模型 ID"
              style={{ width: 340 }}
              loading={loading}
            />
            <Select value={statusFilter} onChange={(value) => { setStatusFilter(value); setCatalogPage(1); }} style={{ width: 150 }} options={[
              { value: 'ALL', label: '全部模型' },
              { value: 'IMPORTABLE', label: '可选择导入' },
              { value: 'NEW', label: '新增模型' },
              { value: 'IMPORTED', label: '已导入' },
              { value: 'UPDATE_AVAILABLE', label: '有目录更新' },
              { value: 'UPGRADE_REQUIRED', label: '需要升级' },
              { value: 'AID_INCOMPATIBLE', label: '不兼容 AID · 禁止导入' },
              { value: 'DEPRECATED', label: '已停用' }
            ]} />
          </Space>
          <Space>
            <Typography.Text type="secondary">已选择 {selectedKeys.length} 个模型</Typography.Text>
            <Button
              type="primary"
              icon={<CloudDownloadOutlined />}
              loading={importing}
              disabled={!selectedKeys.length || !catalogStatus?.catalogVersion}
              onClick={importSelected}
            >批量导入</Button>
          </Space>
        </Space>
        <Table<TokenDanceCatalogSummary>
          rowKey="modelId"
          loading={loading}
          dataSource={filteredModels}
          columns={columns}
          rowSelection={{
            selectedRowKeys: selectedKeys,
            onChange: chooseRows,
            getCheckboxProps: (row) => ({
              disabled: !row.supportedProtocols.some((protocol) => compatibleProtocol(row, protocol)?.selectable),
              title: row.supportedProtocols.some((protocol) => compatibleProtocol(row, protocol)?.selectable)
                ? undefined : '当前没有可导入的兼容协议'
            })
          }}
          onRow={(row) => ({
            style: row.supportedProtocols.some((protocol) => compatibleProtocol(row, protocol)?.status === 'AID_INCOMPATIBLE')
              ? undefined : row.supportedProtocols.some((protocol) => compatibleProtocol(row, protocol)?.importable)
              ? undefined : { opacity: 0.55, background: '#fafafa' }
          })}
          pagination={{
            current: Math.min(catalogPage, Math.max(1, Math.ceil(filteredModels.length / catalogPageSize))),
            pageSize: catalogPageSize,
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 50, 100],
            showTotal: (total) => `共 ${total} 个模型`,
            onChange: (page, pageSize) => {
              setCatalogPage(pageSize === catalogPageSize ? page : 1);
              setCatalogPageSize(pageSize);
            }
          }}
          scroll={{ x: 1050, y: 'min(540px, 55vh)' }}
        />
      </Modal>

      <Modal
        open={previewOpen}
        title={detail ? `${detail.name} · 导入预览` : '模型导入预览'}
        width={1060}
        footer={<Space>
          {preview?.canRepairEmptyPricing && <Button type="primary" loading={repairing} onClick={repairEmptyPricing}>修复目录 SKU</Button>}
          <Button onClick={() => setPreviewOpen(false)}>关闭</Button>
        </Space>}
        onCancel={() => setPreviewOpen(false)}
        destroyOnClose
      >
        <Spin spinning={previewLoading}>
          {!previewLoading && (!detail || !preview) && <Empty description="暂无预览数据" />}
          {detail && preview && (
            <>
              <Descriptions bordered size="small" column={3} items={[
                { key: 'id', label: '模型 ID', children: detail.modelId },
                { key: 'protocol', label: '接入协议', children: protocolLabel(preview.protocol) },
                { key: 'verified', label: '核验状态', children: statusTag(preview.verificationStatus) },
                { key: 'compatibility', label: '程序兼容', children: compatibilityTag(preview.compatibility) },
                { key: 'action', label: '导入动作', children: preview.importAction === 'CREATE_DISABLED' ? '创建为停用模型' : '保留已有模型配置' },
                { key: 'cost', label: '成本覆盖', children: preview.pricingComplete
                  ? <Tag color="success">完整，可生成计费 SKU</Tag>
                  : <Tag color="warning">{preview.existingModelId ? '目录成本待核验，现有 SKU 不变' : '目录成本待核验，新 SKU 不启用'}</Tag> },
                { key: 'context', label: '上下文长度', children: detail.contextLength || '-' },
                { key: 'inputs', label: '输入模态', children: <Space wrap>{allowedInputs.map((item) => <Tag key={item}>{item}</Tag>)}</Space>, span: 2 },
                { key: 'skuCount', label: '生成 SKU', children: `${previewSkus.length} 条` },
                { key: 'code', label: '本地模型编码', children: preview.localModelCode, span: 2 },
                { key: 'version', label: '已有配置版本', children: preview.configVersion ?? '-' },
                { key: 'catalogVersion', label: '目录版本', children: preview.catalogVersion || '-' },
                { key: 'abilityChanged', label: '能力差异', children: preview.existingModelId ? (preview.capabilityChanged ? <Tag color="warning">目录能力有变化</Tag> : '无变化') : '新模型' },
                { key: 'billingChanged', label: 'SKU 差异', children: preview.existingModelId ? (preview.billingChanged ? <Tag color="warning">目录成本有变化</Tag> : '无变化') : '新模型' },
                { key: 'source', label: '成本来源', children: detail.sourceUrl ? <Typography.Link href={detail.sourceUrl} target="_blank" rel="noreferrer">TokenDance 模型页</Typography.Link> : '-', span: 3 },
                { key: 'fetched', label: '快照时间', children: detail.fetchedAt || '-', span: 3 }
              ]} />
              {!!preview.warnings?.length && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginTop: 12 }}
                  message="成本转换检查未通过（具体原因如下）"
                  description={<ul style={{ margin: 0, paddingLeft: 20 }}>{preview.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>}
                />
              )}
              <Divider orientation="left">能力与核验证据</Divider>
              {capabilityEntries.length > 0 ? (
                <Descriptions bordered size="small" column={2} items={capabilityEntries.map(({ key, value }) => ({
                  key,
                  label: FIELD_LABELS[key] || key,
                  children: scalarText(value)
                }))} />
              ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="目录未声明可结构化展示的能力" />}
              {evidence && (
                <div style={{ marginTop: 12 }}>
                  <Space wrap>
                    <Tag>证据：{evidence.evidenceStatus === 'VERIFIED' ? '已核验' : '待补充'}</Tag>
                    <Tag>协议覆盖：{evidence.coverageStatus === 'COMPLETE' ? '完整' : '待补充'}</Tag>
                    <Tag color={evidence.autoActivationAllowed ? 'success' : 'warning'}>
                      {evidence.autoActivationAllowed ? '允许自动启用' : '禁止自动启用'}
                    </Tag>
                  </Space>
                  {!!evidence.evidence?.length && <Space direction="vertical" size={2} style={{ width: '100%', marginTop: 8 }}>
                    {evidence.evidence.map((item) => <Typography.Link key={item.url} href={item.url} target="_blank" rel="noreferrer">{item.title}</Typography.Link>)}
                  </Space>}
                  {!!evidence.activationBlockers?.length && <Alert type="warning" showIcon style={{ marginTop: 8 }}
                    message="启用前置检查"
                    description={<ul style={{ margin: 0, paddingLeft: 20 }}>
                      {evidence.activationBlockers.map((item) => <li key={item}>{activationCheckLabel(item)}</li>)}
                    </ul>} />}
                  {!!evidence.requirements?.length && <div style={{ marginTop: 8 }}>
                    <Typography.Text strong>官方组合约束</Typography.Text>
                    <Space direction="vertical" size={4} style={{ width: '100%', marginTop: 4 }}>
                      {evidence.requirements.map((requirement, index) => <ConditionTags key={index} value={requirement} />)}
                    </Space>
                  </div>}
                </div>
              )}
              <Divider orientation="left">模型调用成本明细（CNY）</Divider>
              {detail.items.map((item) => (
                <div key={item.id} style={{ marginBottom: 18 }}>
                  <Space style={{ marginBottom: 8 }}>
                    <Tooltip title={item.id}><Typography.Text strong>{item.id}</Typography.Text></Tooltip>
                    {item.mode && <Tag>{item.mode}</Tag>}
                  </Space>
                  <Table<CostRow>
                    size="small"
                    rowKey="key"
                    columns={COST_COLUMNS}
                    dataSource={costRows(item)}
                    pagination={false}
                  />
                </div>
              ))}
            </>
          )}
        </Spin>
      </Modal>
    </>
  );
}
