import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Col, Descriptions, Divider, Empty, Form, Input,
  InputNumber, Modal, Progress, Row, Select, Space, Spin, Steps, Table, Tag,
  Tooltip, Typography, Upload, message
} from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import {
  AudioOutlined, CheckCircleOutlined, CloudUploadOutlined, DollarOutlined,
  ExperimentOutlined, SoundOutlined
} from '@ant-design/icons';
import {
  createVoiceWorkbenchTask,
  getVoiceWorkbenchCapabilities,
  getVoiceWorkbenchTask,
  publishVoiceWorkbenchResult,
  quoteVoiceWorkbench,
  uploadVoiceWorkbenchSample,
  type VoiceWorkbenchCapabilities,
  type VoiceWorkbenchOperation,
  type VoiceWorkbenchOperationCapability,
  type VoiceWorkbenchPublishResult,
  type VoiceWorkbenchQuote,
  type VoiceWorkbenchRequestFields,
  type VoiceWorkbenchSample,
  type VoiceWorkbenchTask
} from '@/api/aid/voicelibrary';
import { AGE_RANGE_OPTIONS, GENDER_OPTIONS, LANGUAGE_OPTIONS } from './constants';

interface Props {
  open: boolean;
  modelOptions: any[];
  onClose: () => void;
  onPublished?: () => void | Promise<void>;
}

interface WorkbenchForm {
  modelId: number;
  voiceCode?: string;
  text?: string;
  description?: string;
  rightsConfirmed?: boolean;
  audioFormat?: string;
  sampleRate?: number;
  speechRate?: number;
  loudnessRate?: number;
  pitch?: number;
  emotion?: string;
  optimizeTextPreview?: boolean;
}

const OPERATION_META: Record<VoiceWorkbenchOperation, { label: string; description: string; icon: React.ReactNode }> = {
  SYNTHESIZE: { label: '配音试听', description: '使用已有音色生成一段站长试听音频', icon: <SoundOutlined /> },
  REGISTER_CLONE: { label: '注册式音色复刻', description: '向供应商注册长期可复用的新音色', icon: <CloudUploadOutlined /> },
  REFERENCE_CLONE: { label: '参考样本克隆', description: '本次请求携带参考录音进行零样本克隆', icon: <AudioOutlined /> },
  DESIGN: { label: '声音设计', description: '通过文字描述设计新声音并生成试听', icon: <ExperimentOutlined /> }
};

const TASK_TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'CLOSED']);

const taskColor = (status?: string) => {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'CLOSED') return 'error';
  return 'processing';
};

const formatMoney = (value?: number, currency = 'CNY') => {
  if (value == null) return '-';
  return `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 8 })} ${currency}`;
};

const formatDuration = (durationMs?: number) => {
  if (durationMs == null) return '-';
  return `${(durationMs / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 3 })} 秒`;
};

const makeIdempotencyKey = () => typeof crypto.randomUUID === 'function'
  ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;

export default function VoiceWorkbenchWizard({ open, modelOptions, onClose, onPublished }: Props) {
  const [form] = Form.useForm<WorkbenchForm>();
  const [publishForm] = Form.useForm();
  const [step, setStep] = useState(0);
  const [operation, setOperation] = useState<VoiceWorkbenchOperation | null>(null);
  const [capabilities, setCapabilities] = useState<VoiceWorkbenchCapabilities | null>(null);
  const [capabilityLoading, setCapabilityLoading] = useState(false);
  const [sample, setSample] = useState<VoiceWorkbenchSample | null>(null);
  const [sampleFiles, setSampleFiles] = useState<UploadFile[]>([]);
  const [sampleUploading, setSampleUploading] = useState(false);
  const [quote, setQuote] = useState<VoiceWorkbenchQuote | null>(null);
  const [quoteLoading, setQuoteLoading] = useState(false);
  const [chargeConfirmed, setChargeConfirmed] = useState(false);
  const [task, setTask] = useState<VoiceWorkbenchTask | null>(null);
  const [taskCreating, setTaskCreating] = useState(false);
  const [publishResult, setPublishResult] = useState<VoiceWorkbenchPublishResult | null>(null);
  const [publishing, setPublishing] = useState(false);
  const scopeRef = useRef(0);
  const capabilityRequestsRef = useRef<Map<number, Promise<any>>>(new Map());
  const capabilitySequenceRef = useRef(0);
  const sampleRequestRef = useRef(false);
  const quoteRequestRef = useRef(false);
  const createRequestRef = useRef(false);
  const publishRequestRef = useRef(false);
  const pollTimerRef = useRef<number | null>(null);
  const pollRequestRef = useRef(false);
  const taskFlowRef = useRef(0);
  const sampleFlowRef = useRef(0);
  const quoteFlowRef = useRef(0);
  const idempotencyKeyRef = useRef('');
  const selectedModelId = Form.useWatch('modelId', form);
  const rightsConfirmed = Form.useWatch('rightsConfirmed', form) === true;
  const selectedAudioFormat = Form.useWatch('audioFormat', form);
  const optimizeTextPreview = Form.useWatch('optimizeTextPreview', form) === true;

  const clearPoll = () => {
    if (pollTimerRef.current != null) window.clearTimeout(pollTimerRef.current);
    pollTimerRef.current = null;
    pollRequestRef.current = false;
  };

  useEffect(() => {
    scopeRef.current += 1;
    taskFlowRef.current += 1;
    sampleFlowRef.current += 1;
    quoteFlowRef.current += 1;
    clearPoll();
    form.resetFields();
    publishForm.resetFields();
    setStep(0);
    setOperation(null);
    setCapabilities(null);
    setCapabilityLoading(false);
    setSample(null);
    setSampleFiles([]);
    setSampleUploading(false);
    setQuote(null);
    setQuoteLoading(false);
    setChargeConfirmed(false);
    setTask(null);
    setTaskCreating(false);
    setPublishResult(null);
    setPublishing(false);
    idempotencyKeyRef.current = '';
    if (open) form.setFieldsValue({ rightsConfirmed: false });
    return clearPoll;
  }, [form, open, publishForm]);

  const invalidateQuote = () => {
    quoteFlowRef.current += 1;
    setQuote(null);
    setChargeConfirmed(false);
    idempotencyKeyRef.current = '';
    if (step > 1 && !task) setStep(1);
  };

  const selectModel = async (modelId: number) => {
    form.setFieldValue('modelId', modelId);
    setOperation(null);
    sampleFlowRef.current += 1;
    setCapabilities(null);
    setSample(null);
    setSampleFiles([]);
    invalidateQuote();
    const scope = scopeRef.current;
    const sequence = ++capabilitySequenceRef.current;
    let pending = capabilityRequestsRef.current.get(modelId);
    if (!pending) {
      pending = getVoiceWorkbenchCapabilities(modelId);
      capabilityRequestsRef.current.set(modelId, pending);
    }
    setCapabilityLoading(true);
    try {
      const response: any = await pending;
      if (scope === scopeRef.current && sequence === capabilitySequenceRef.current
        && form.getFieldValue('modelId') === modelId) {
        setCapabilities(response.data || null);
      }
    } catch (error: any) {
      if (scope === scopeRef.current && sequence === capabilitySequenceRef.current) {
        message.error(error?.message || '模型音色能力加载失败');
      }
    } finally {
      if (capabilityRequestsRef.current.get(modelId) === pending) capabilityRequestsRef.current.delete(modelId);
      if (scope === scopeRef.current && sequence === capabilitySequenceRef.current) setCapabilityLoading(false);
    }
  };

  const operationCapability = useMemo(() => capabilities?.operations?.find(
    (item) => item.operation === operation
  ) || null, [capabilities, operation]);

  const selectOperation = (item: VoiceWorkbenchOperationCapability) => {
    if (!item.supported) return;
    setOperation(item.operation);
    sampleFlowRef.current += 1;
    setSample(null);
    setSampleFiles([]);
    invalidateQuote();
    form.setFieldsValue({
      voiceCode: undefined, text: undefined, description: undefined,
      audioFormat: item.audioFormats?.[0], sampleRate: item.sampleRates?.[0],
      speechRate: undefined, loudnessRate: undefined, pitch: undefined, emotion: undefined,
      optimizeTextPreview: false,
      rightsConfirmed: false
    });
  };

  const required = (field: string) => operationCapability?.requiredFields?.includes(field) === true;
  const supported = (field: string) => required(field)
    || operationCapability?.optionalFields?.includes(field) === true;
  const needsSample = required('sourceFileId')
    || operation === 'REGISTER_CLONE' || operation === 'REFERENCE_CLONE';
  const showVoiceCode = required('voiceCode') || supported('voiceCode')
    || operation === 'SYNTHESIZE' || operation === 'REGISTER_CLONE';
  const showText = required('text') || supported('text')
    || operation === 'SYNTHESIZE' || operation === 'REFERENCE_CLONE' || operation === 'DESIGN';
  const showDescription = required('description') || supported('description') || operation === 'DESIGN';

  const buildRequest = (values: WorkbenchForm): VoiceWorkbenchRequestFields => ({
    modelId: values.modelId,
    operation: operation!,
    voiceCode: values.voiceCode?.trim() || undefined,
    text: values.text?.trim() || undefined,
    sourceFileId: sample?.fileId,
    description: values.description?.trim() || undefined,
    rightsConfirmed: values.rightsConfirmed === true,
    audioFormat: values.audioFormat,
    sampleRate: values.sampleRate,
    speechRate: values.speechRate,
    loudnessRate: values.loudnessRate,
    pitch: values.pitch,
    emotion: values.emotion?.trim() || undefined,
    optimizeTextPreview: operation === 'DESIGN' ? values.optimizeTextPreview === true : undefined
  });

  const validateForQuote = async () => {
    const values = await form.validateFields();
    if (!operation || !operationCapability?.supported) throw new Error('请选择模型支持的操作');
    if (needsSample && (!sample?.verified || !sample.fileId)) throw new Error('请先上传并通过服务端校验的参考音频');
    if (values.rightsConfirmed !== true) throw new Error('请确认拥有文本、音色及参考音频所需的合法使用授权');
    return buildRequest(values);
  };

  const requestQuote = async () => {
    if (quoteRequestRef.current) return;
    let requestFields: VoiceWorkbenchRequestFields;
    try {
      requestFields = await validateForQuote();
    } catch (error: any) {
      message.warning(error?.message || '请完善操作参数');
      return;
    }
    const scope = scopeRef.current;
    const flow = quoteFlowRef.current;
    quoteRequestRef.current = true;
    setQuoteLoading(true);
    try {
      const response: any = await quoteVoiceWorkbench(requestFields);
      if (scope !== scopeRef.current || flow !== quoteFlowRef.current) return;
      const next = response.data as VoiceWorkbenchQuote;
      setQuote(next);
      setChargeConfirmed(false);
      idempotencyKeyRef.current = makeIdempotencyKey();
      setStep(2);
    } catch (error: any) {
      if (scope === scopeRef.current && flow === quoteFlowRef.current) message.error(error?.message || '成本报价失败');
    } finally {
      quoteRequestRef.current = false;
      if (scope === scopeRef.current && flow === quoteFlowRef.current) setQuoteLoading(false);
    }
  };

  const pollTask = (view: VoiceWorkbenchTask, scope: number, flow: number) => {
    if (scope !== scopeRef.current || flow !== taskFlowRef.current || TASK_TERMINAL.has(view.status)) return;
    pollTimerRef.current = window.setTimeout(async () => {
      if (pollRequestRef.current || scope !== scopeRef.current || flow !== taskFlowRef.current) return;
      pollRequestRef.current = true;
      try {
        const response: any = await getVoiceWorkbenchTask(view.taskId);
        if (scope !== scopeRef.current || flow !== taskFlowRef.current) return;
        const next = response.data as VoiceWorkbenchTask;
        setTask(next);
        pollTask(next, scope, flow);
      } catch {
        if (scope === scopeRef.current && flow === taskFlowRef.current) pollTask(view, scope, flow);
      } finally {
        pollRequestRef.current = false;
      }
    }, 2500);
  };

  const createTask = async () => {
    if (!quote?.quoteRef || !['READY', 'FREE'].includes(quote.pricingStatus) || !chargeConfirmed || createRequestRef.current) return;
    let requestFields: VoiceWorkbenchRequestFields;
    try {
      requestFields = await validateForQuote();
    } catch (error: any) {
      message.warning(error?.message || '参数已变化，请重新报价');
      invalidateQuote();
      setStep(1);
      return;
    }
    const scope = scopeRef.current;
    const flow = ++taskFlowRef.current;
    createRequestRef.current = true;
    setTaskCreating(true);
    try {
      const response: any = await createVoiceWorkbenchTask({
        ...requestFields,
        quoteRef: quote.quoteRef,
        chargeConfirmed: true,
        idempotencyKey: idempotencyKeyRef.current
      });
      if (scope !== scopeRef.current || flow !== taskFlowRef.current) return;
      const next = response.data as VoiceWorkbenchTask;
      setTask(next);
      setStep(3);
      pollTask(next, scope, flow);
    } catch (error: any) {
      if (scope === scopeRef.current && flow === taskFlowRef.current) message.error(error?.message || '音色任务创建失败');
    } finally {
      createRequestRef.current = false;
      if (scope === scopeRef.current && flow === taskFlowRef.current) setTaskCreating(false);
    }
  };

  const saveVoice = async (publishStatus: 'DRAFT' | 'PUBLISHED') => {
    if (!task || task.status !== 'SUCCEEDED' || publishRequestRef.current) return;
    let values: any;
    try {
      values = await publishForm.validateFields();
    } catch {
      return;
    }
    publishRequestRef.current = true;
    setPublishing(true);
    const scope = scopeRef.current;
    const flow = taskFlowRef.current;
    try {
      const response: any = await publishVoiceWorkbenchResult({
        taskId: task.taskId,
        targetModelId: values.targetModelId,
        name: values.name.trim(),
        publishStatus,
        language: values.language,
        gender: values.gender,
        ageRange: values.ageRange
      });
      if (scope !== scopeRef.current || flow !== taskFlowRef.current) return;
      const next = response.data as VoiceWorkbenchPublishResult;
      setPublishResult(next);
      message.success(publishStatus === 'DRAFT' ? '音色草稿已保存' : '音色已正式发布');
      await onPublished?.();
    } catch (error: any) {
      if (scope === scopeRef.current && flow === taskFlowRef.current) message.error(error?.message || '音色保存失败');
    } finally {
      publishRequestRef.current = false;
      if (scope === scopeRef.current && flow === taskFlowRef.current) setPublishing(false);
    }
  };

  const sampleUploadProps: UploadProps = {
    accept: operationCapability?.sampleLimit?.formats?.map((item) => `.${item.replace(/^\./, '')}`).join(',') || 'audio/*',
    maxCount: 1,
    fileList: sampleFiles,
    disabled: !selectedModelId || !operationCapability?.supported || !rightsConfirmed,
    beforeUpload: (file) => {
      const limit = operationCapability?.sampleLimit;
      const extension = file.name.split('.').pop()?.toLowerCase();
      if (limit?.formats?.length && (!extension || !limit.formats.map((item) => item.replace(/^\./, '').toLowerCase()).includes(extension))) {
        message.error(`仅支持 ${limit.formats.join('、')} 格式`);
        return Upload.LIST_IGNORE;
      }
      if (limit?.maxBytes && file.size > limit.maxBytes) {
        message.error(`样本文件不能超过 ${(limit.maxBytes / 1024 / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}MiB`);
        return Upload.LIST_IGNORE;
      }
      return true;
    },
    customRequest: async ({ file, onError, onSuccess }) => {
      if (!selectedModelId || !rightsConfirmed || sampleRequestRef.current) {
        onError?.(new Error('请先确认声音授权'));
        return;
      }
      sampleRequestRef.current = true;
      const flow = ++sampleFlowRef.current;
      const scope = scopeRef.current;
      const requestedModelId = selectedModelId;
      const requestedOperation = operation;
      setSampleUploading(true);
      try {
        const response: any = await uploadVoiceWorkbenchSample(selectedModelId, file as File, true);
        const next = response.data as VoiceWorkbenchSample;
        if (!next?.verified) throw new Error('服务端未能验证样本元数据');
        if (scope !== scopeRef.current || flow !== sampleFlowRef.current
          || requestedModelId !== form.getFieldValue('modelId') || requestedOperation !== operation) return;
        setSample(next);
        invalidateQuote();
        onSuccess?.(next);
      } catch (error: any) {
        if (scope === scopeRef.current && flow === sampleFlowRef.current
          && requestedModelId === form.getFieldValue('modelId') && requestedOperation === operation) {
          setSample(null);
          onError?.(error);
        }
      } finally {
        sampleRequestRef.current = false;
        if (scope === scopeRef.current && flow === sampleFlowRef.current) setSampleUploading(false);
      }
    },
    onChange: ({ fileList }) => setSampleFiles(fileList.slice(-1)),
    onRemove: () => {
      sampleFlowRef.current += 1;
      setSample(null);
      setSampleFiles([]);
      invalidateQuote();
      return true;
    }
  };

  const operationStep = (
    <Spin spinning={capabilityLoading}>
      <Form.Item name="modelId" label="音频模型" rules={[{ required: true, message: '请选择音频模型' }]}>
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="选择模型后读取其真实音色能力"
          options={modelOptions.map((model) => ({
            value: model.id,
            label: `${model.modelName}（${model.modelCode}）`
          }))}
          onChange={selectModel}
        />
      </Form.Item>
      {selectedModelId && capabilities && (
        <Row gutter={[12, 12]}>
          {capabilities.operations.map((item) => {
            const meta = OPERATION_META[item.operation];
            const active = operation === item.operation;
            return (
              <Col span={12} key={item.operation}>
                <Tooltip title={!item.supported ? item.disabledReason || '当前模型不支持' : undefined}>
                  <Card
                    size="small"
                    hoverable={item.supported}
                    onClick={() => selectOperation(item)}
                    style={{
                      cursor: item.supported ? 'pointer' : 'not-allowed',
                      opacity: item.supported ? 1 : 0.55,
                      borderColor: active ? '#1677ff' : undefined,
                      background: active ? '#f0f7ff' : undefined
                    }}
                  >
                    <Space align="start">
                      <span style={{ color: active ? '#1677ff' : undefined, fontSize: 18 }}>{meta?.icon}</span>
                      <div>
                        <Space><Typography.Text strong>{meta?.label || item.operation}</Typography.Text>
                          {item.supported ? <Tag color="success">支持</Tag> : <Tag>不支持</Tag>}
                        </Space>
                        <div style={{ color: '#64748b', fontSize: 12, marginTop: 3 }}>{meta?.description}</div>
                        {!item.supported && item.disabledReason && <div style={{ color: '#b45309', fontSize: 12 }}>{item.disabledReason}</div>}
                      </div>
                    </Space>
                  </Card>
                </Tooltip>
              </Col>
            );
          })}
        </Row>
      )}
      {selectedModelId && capabilities && capabilities.operations.every((item) => !item.supported) && (
        <Empty description="该模型尚未接通任何音色工作台操作" />
      )}
    </Spin>
  );

  const parameterStep = operationCapability && (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={`${OPERATION_META[operationCapability.operation].label} · 仅展示当前模型已声明的参数与硬约束`}
      />
      <Row gutter={16}>
        {showVoiceCode && (
          <Col span={12}><Form.Item name="voiceCode" label="音色编码" rules={[
            ...(required('voiceCode') ? [{ required: true, message: '请输入音色编码' }] : []),
            ...(operation === 'REGISTER_CLONE' ? [{
              pattern: /^[A-Za-z](?:[A-Za-z0-9_-]{6,126}[A-Za-z0-9])$/,
              message: '需为 8–128 位，以字母开头、字母或数字结尾，仅含字母、数字、下划线或横线'
            }] : [])
          ]}>
            <Input maxLength={128} placeholder={operation === 'SYNTHESIZE' ? '已有音色 voice_code' : '为新音色指定供应商编码'} />
          </Form.Item></Col>
        )}
        {showText && (
          <Col span={24}><Form.Item name="text" label={operation === 'SYNTHESIZE' ? '试听文本' : '验证文本'} rules={[
            ...(required('text') && !optimizeTextPreview ? [{ required: true, message: '请输入文本，或开启自动优化试听文本' }] : []),
            ...(operationCapability.textLimit ? [{ max: operationCapability.textLimit }] : [])
          ]}>
            <Input.TextArea rows={3} showCount disabled={operation === 'DESIGN' && optimizeTextPreview}
              placeholder={operation === 'DESIGN' && optimizeTextPreview ? '由供应商根据声音描述自动生成试听文本' : undefined}
              maxLength={operationCapability.textLimit || undefined} />
          </Form.Item></Col>
        )}
        {showDescription && (
          <Col span={24}><Form.Item name="description" label="声音设计描述" rules={required('description') ? [{ required: true, message: '请输入声音设计描述' }] : []}>
            <Input.TextArea rows={3} showCount maxLength={2000} placeholder="描述年龄、音色质感、说话风格、情绪和适用场景" />
          </Form.Item></Col>
        )}
      </Row>
      {operation === 'DESIGN' && supported('optimizeTextPreview') && (
        <Form.Item name="optimizeTextPreview" valuePropName="checked">
          <Checkbox>由供应商自动优化并生成试听文本（开启后可不填写验证文本）</Checkbox>
        </Form.Item>
      )}
      <Form.Item name="rightsConfirmed" valuePropName="checked" rules={[{
        validator: (_, value) => value === true ? Promise.resolve() : Promise.reject(new Error('必须确认合法使用授权'))
      }]}>
        <Checkbox>
          我确认拥有文本、音色及参考录音（如有）的合法使用、处理与复刻授权，并承担相应责任。
        </Checkbox>
      </Form.Item>
      {needsSample && (
        <Card size="small" title="参考声音样本" style={{ marginBottom: 14 }}>
          <Upload.Dragger {...sampleUploadProps}>
            <p><CloudUploadOutlined style={{ fontSize: 28, color: '#1677ff' }} /></p>
            <p>{rightsConfirmed ? '点击或拖入一个参考音频' : '请先勾选上方授权声明'}</p>
            <p style={{ color: '#94a3b8', fontSize: 12 }}>
              格式：{operationCapability.sampleLimit?.formats?.join('、') || '按服务端规则'}；
              大小：{operationCapability.sampleLimit?.maxBytes
                ? `≤ ${(operationCapability.sampleLimit.maxBytes / 1024 / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}MiB`
                : '按服务端规则'}；
              {operationCapability.sampleLimit?.maxEncodedBytes
                ? ` 编码后 ≤ ${(operationCapability.sampleLimit.maxEncodedBytes / 1024 / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}MiB；`
                : ''}
              时长：{operationCapability.sampleLimit?.minDurationMs != null
                ? operationCapability.sampleLimit.minDurationMs / 1000 : '-'}～{operationCapability.sampleLimit?.maxDurationMs != null
                ? operationCapability.sampleLimit.maxDurationMs / 1000 : '-'} 秒；
              采样率 ≥ {operationCapability.sampleLimit?.minSampleRate || '-'} Hz；
              声道 {operationCapability.sampleLimit?.channels || '-'}
            </p>
          </Upload.Dragger>
          {sampleUploading && <Progress percent={99} status="active" showInfo={false} style={{ marginTop: 8 }} />}
          {sample && (
            <Descriptions size="small" bordered column={3} style={{ marginTop: 10 }} items={[
              { key: 'verified', label: '服务端校验', children: sample.verified ? <Tag color="success">已通过</Tag> : <Tag color="error">未通过</Tag> },
              { key: 'name', label: '样本文件', children: sample.name || '-' },
              { key: 'duration', label: '可信时长', children: formatDuration(sample.durationMs) },
              { key: 'size', label: '文件大小', children: `${(sample.sizeBytes / 1024 / 1024).toFixed(2)} MiB` },
              { key: 'rate', label: '采样率', children: sample.sampleRate || '-' },
              { key: 'channel', label: '声道数', children: sample.channels || '-' },
              { key: 'mime', label: '格式', children: sample.mime || '-' }
            ]} />
          )}
        </Card>
      )}
      <Divider orientation="left">输出与语音参数</Divider>
      <Row gutter={16}>
        {(operationCapability.audioFormats?.length || supported('audioFormat')) && (
          <Col span={8}><Form.Item name="audioFormat" label="音频格式">
            <Select allowClear options={(operationCapability.audioFormats || []).map((value) => ({ value, label: value }))} />
          </Form.Item></Col>
        )}
        {(operationCapability.sampleRates?.length || supported('sampleRate')) && (
          <Col span={8}><Form.Item name="sampleRate" label="采样率">
            <Select allowClear options={(operationCapability.sampleRates || []).map((value) => ({ value, label: `${value} Hz` }))} />
          </Form.Item></Col>
        )}
        {supported('speechRate') && <Col span={8}><Form.Item name="speechRate" label="语速"><InputNumber min={-50} max={100} style={{ width: '100%' }} /></Form.Item></Col>}
        {supported('loudnessRate') && <Col span={8}><Form.Item name="loudnessRate" label="音量"><InputNumber min={-50} max={100} style={{ width: '100%' }} /></Form.Item></Col>}
        {supported('pitch') && <Col span={8}><Form.Item name="pitch" label="音调"><InputNumber min={-12} max={12} style={{ width: '100%' }} /></Form.Item></Col>}
        {supported('emotion') && <Col span={8}><Form.Item name="emotion" label="情感"><Input maxLength={64} /></Form.Item></Col>}
      </Row>
    </>
  );

  const quoteStep = quote ? (
    <>
      <Alert
        type={quote.pricingStatus === 'MISSING' ? 'error' : quote.pricingStatus === 'FREE' ? 'success' : 'info'}
        showIcon
        message={quote.pricingStatus === 'MISSING' ? '缺少费用配置，禁止提交'
          : quote.pricingStatus === 'FREE' ? '本次权威报价为免费' : `预计上游成本 ${formatMoney(quote.totalCost, quote.currency)}`}
        description="本操作由站长账户承担 TokenDance/供应商成本，不扣 C 端用户积分；最终成本以任务结算为准。"
      />
      <Table
        style={{ marginTop: 12 }}
        size="small"
        rowKey={(row: any, index) => `${row.label}-${index}`}
        dataSource={quote.breakdown || []}
        pagination={false}
        columns={[
          { title: '成本项', dataIndex: 'label' },
          { title: '数量', dataIndex: 'quantity', render: (value: any) => value ?? '-' },
          { title: '单位', dataIndex: 'unit', render: (value: any) => value || '-' },
          { title: '金额', dataIndex: 'amount', render: (value: any) => formatMoney(value, quote.currency) },
          { title: '性质', dataIndex: 'estimated', render: (value: any) => value ? <Tag color="warning">估算</Tag> : <Tag>确定</Tag> }
        ]}
      />
      {!!quote.warnings?.length && <Alert type="warning" showIcon style={{ marginTop: 12 }} message="报价提示"
        description={<ul style={{ margin: 0, paddingLeft: 20 }}>{quote.warnings.map((item) => <li key={item}>{item}</li>)}</ul>} />}
      <Descriptions bordered size="small" column={2} style={{ marginTop: 12 }} items={[
        { key: 'payer', label: '付款方', children: '站长账户' },
        { key: 'expire', label: '报价有效期', children: quote.expiresAt || '-' }
      ]} />
      <Checkbox checked={chargeConfirmed} disabled={quote.pricingStatus === 'MISSING'}
        onChange={(event) => setChargeConfirmed(event.target.checked)} style={{ marginTop: 14 }}>
        我已核对参数与报价，确认由站长账户承担本次上游费用。
      </Checkbox>
    </>
  ) : <Empty description="请重新获取权威报价" />;

  const resultAudioUrl = task?.result?.audioUrl;
  const publishTargetIds = new Set(operationCapability?.publishTargetModelIds || []);
  const publishTargetModels = modelOptions.filter((model) => publishTargetIds.has(Number(model.id)));
  const publishFormatSupported = !task || !['REFERENCE_CLONE', 'DESIGN'].includes(task.operation)
    || ['wav', 'mp3'].includes(String(selectedAudioFormat || '').toLowerCase());
  const publishTargetAvailable = publishTargetModels.length > 0;
  const taskStep = task ? (
    <>
      <Descriptions bordered size="small" column={3} items={[
        { key: 'id', label: '任务 ID', children: task.taskId },
        { key: 'op', label: '操作', children: OPERATION_META[task.operation]?.label || task.operation },
        { key: 'status', label: '状态', children: <Tag color={taskColor(task.status)}>{task.status}</Tag> },
        { key: 'quote', label: '预估成本', children: formatMoney(task.quotedCost, task.currency) },
        { key: 'final', label: '最终成本', children: formatMoney(task.finalCost, task.currency) },
        { key: 'voice', label: '结果音色编码', children: task.result?.voiceCode || '-' }
      ]} />
      {!TASK_TERMINAL.has(task.status) && <Progress percent={Math.max(0, Math.min(99, task.progress || 0))} status="active" style={{ marginTop: 14 }} />}
      {task.status === 'FAILED' && <Alert type="error" showIcon style={{ marginTop: 12 }} message="任务失败"
        description={<div>
          <div>{task.failureReason || '上游未返回失败原因'}</div>
          <div style={{ marginTop: 4 }}>
            任务可能已经提交上游并产生费用，系统不会自动重新生成。若需再次制作，请关闭工作台后重新获取报价并再次确认费用。
          </div>
        </div>} />}
      {task.status === 'SUCCEEDED' && (
        <>
          <Alert type="success" showIcon style={{ marginTop: 12 }} message="音色任务已完成" />
          {resultAudioUrl && <div style={{ marginTop: 12 }}><audio controls src={resultAudioUrl} style={{ width: '100%' }} /></div>}
          {operationCapability?.canPublish && (
            <Card size="small" title="保存到音色库" style={{ marginTop: 14 }}>
              {!publishFormatSupported && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                message="当前输出格式不能保存为可复用参考音色"
                description="参考样本克隆和声音设计发布到音色库时必须使用 WAV 或 MP3；请返回参数步骤改用可复用格式并重新报价、创建任务。" />}
              {!publishTargetAvailable && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                message="没有可绑定的配音模型"
                description="请先启用同供应商、同真实模型且协议匹配的配音模型，再保存到音色库。" />}
              <Form form={publishForm} layout="vertical" initialValues={{
                language: 'zh-CN', gender: 'female', ageRange: 'young',
                name: task.result?.voiceCode || ''
              }}>
                <Row gutter={16}>
                  <Col span={12}><Form.Item name="name" label="展示名称" rules={[{ required: true }, { max: 100 }]}><Input /></Form.Item></Col>
                  <Col span={12}><Form.Item name="targetModelId" label="发布绑定的配音模型"
                    rules={publishTargetModels.length > 1 ? [{ required: true, message: '存在多个匹配模型，请明确选择' }] : []}
                    tooltip="复刻协议模型不能直接用于后续配音；留空时由服务端唯一匹配同供应商的已启用 TTS 模型。">
                    <Select allowClear showSearch optionFilterProp="label" placeholder="留空由服务端唯一匹配"
                      options={publishTargetModels.map((model) => ({ value: model.id, label: `${model.modelName}（${model.modelCode}）` }))} />
                  </Form.Item></Col>
                  <Col span={6}><Form.Item name="language" label="语言" rules={[{ required: true }]}><Select options={LANGUAGE_OPTIONS.map((item) => ({ value: item.code, label: item.name }))} /></Form.Item></Col>
                  <Col span={6}><Form.Item name="gender" label="性别" rules={[{ required: true }]}><Select options={GENDER_OPTIONS.map((item) => ({ value: item.code, label: item.name }))} /></Form.Item></Col>
                  <Col span={6}><Form.Item name="ageRange" label="年龄段" rules={[{ required: true }]}><Select options={AGE_RANGE_OPTIONS.map((item) => ({ value: item.code, label: item.name }))} /></Form.Item></Col>
                </Row>
              </Form>
              {publishResult && <Alert type="success" showIcon style={{ marginBottom: 10 }}
                message={`音色库记录 ${publishResult.voiceId}：${publishResult.status === 'PUBLISHED' ? '已发布' : '草稿'}`} />}
              <Space>
                <Button loading={publishing} disabled={!publishFormatSupported || !publishTargetAvailable || publishResult?.status === 'PUBLISHED'} onClick={() => saveVoice('DRAFT')}>保存草稿</Button>
                <Button type="primary" icon={<CheckCircleOutlined />} loading={publishing}
                  disabled={!publishFormatSupported || !publishTargetAvailable || publishResult?.status === 'PUBLISHED'} onClick={() => saveVoice('PUBLISHED')}>正式发布</Button>
              </Space>
            </Card>
          )}
        </>
      )}
    </>
  ) : <Empty description="任务尚未创建" />;

  const footer = (
    <Space>
      {step > 0 && step < 3 && <Button onClick={() => setStep(step - 1)}>上一步</Button>}
      {step === 0 && <Button type="primary" disabled={!operationCapability?.supported} onClick={() => setStep(1)}>填写参数</Button>}
      {step === 1 && <Button type="primary" icon={<DollarOutlined />} loading={quoteLoading} onClick={requestQuote}>获取成本报价</Button>}
      {step === 2 && <Button type="primary" loading={taskCreating}
        disabled={!quote?.quoteRef || !['READY', 'FREE'].includes(quote.pricingStatus) || !chargeConfirmed}
        onClick={createTask}>确认费用并创建任务</Button>}
      {step === 3 && <Button onClick={onClose}>关闭</Button>}
    </Space>
  );

  return (
    <Modal
      open={open}
      title="站长音色工作台"
      width={1000}
      onCancel={onClose}
      footer={footer}
      destroyOnClose
      maskClosable={false}
    >
      <Alert type="warning" showIcon style={{ marginBottom: 14 }}
        message="本工作台的上游费用由站长承担，不扣除任何 C 端用户积分。" />
      <Steps current={step} size="small" style={{ marginBottom: 20 }} items={[
        { title: '模型与操作' }, { title: '参数与样本' }, { title: '成本确认' }, { title: '任务与发布' }
      ]} />
      <Form form={form} layout="vertical" onValuesChange={invalidateQuote}>
        {step === 0 && operationStep}
        {step === 1 && parameterStep}
      </Form>
      {step === 2 && quoteStep}
      {step === 3 && taskStep}
    </Modal>
  );
}
