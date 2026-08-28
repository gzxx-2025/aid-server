/**
 * 音色库 新增/编辑 弹窗
 *
 * 需求背景（v2.39.x）：
 * - 后端 VoiceLibraryBusinessServiceImpl.rejectIfSyncOnlyProvider 会拒绝对 MiniMax 服务商的音色做手工 add/update，
 *   因为 MiniMax 音色必须走"同步音色"按钮（否则会被同步覆盖/软删）。
 *   所以前端在弹窗打开时就做服务商判断，提示运营走同步入口，而不是等保存再报错。
 * - avatarUrl / sampleUrl 必须 http(s):// 开头（Vue 版已做拦截；React 版早期漏掉）。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Col, DatePicker, Form, Input, InputNumber, Modal, Radio, Row, Select, Switch } from 'antd';
import dayjs from 'dayjs';
import SectionTitle from '@/components/SectionTitle';
import { LANGUAGE_OPTIONS, GENDER_OPTIONS, AGE_RANGE_OPTIONS, isNeverOffline, parseModelEmotions, resolveEmotionLabel } from './constants';

interface Props {
  open: boolean;
  title: string;
  data?: any;
  providerOpts: any[];
  modelOpts: any[];
  tagDict: any;
  onCancel: () => void;
  onOk: (values: any) => Promise<void>;
}

/** URL 校验：允许空（服务端有自己的非空校验），非空必须是 http(s):// */
const urlValidator = (_: any, value: string) => {
  if (!value) return Promise.resolve();
  if (/^https?:\/\//i.test(value)) return Promise.resolve();
  return Promise.reject(new Error('必须以 http:// 或 https:// 开头'));
};

export default function VoiceFormDialog({ open, title, data, providerOpts, modelOpts, tagDict, onCancel, onOk }: Props) {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [providerFilter, setProviderFilter] = useState<number | null>(null);

  const filteredModels = useMemo(() => {
    if (!providerFilter) return modelOpts;
    return modelOpts.filter((m: any) => m.providerId === providerFilter);
  }, [modelOpts, providerFilter]);

  // 擅长情感候选：以供应商声明为唯一标准——跟随所选模型的 capabilityJson.emotions，
  // 模型未声明情感能力时禁用该选择框（后端也会拒绝"模型不支持情感"的标签录入）
  const selectedModelId = Form.useWatch('modelId', form);
  const emotionOptions = useMemo(() => {
    const model = modelOpts.find((m: any) => m.id === selectedModelId);
    return parseModelEmotions(model?.capabilityJson).map((code) => ({ label: resolveEmotionLabel(code), value: code }));
  }, [modelOpts, selectedModelId]);

  // 当前选中的服务商是否是"仅同步维护"的服务商（目前只 MiniMax 命中）
  // 与后端 VoiceLibraryBusinessServiceImpl.rejectIfSyncOnlyProvider 保持一致
  const isSyncOnlyProvider = useMemo(() => {
    if (!providerFilter) return false;
    const p = providerOpts.find((x: any) => x.id === providerFilter);
    return (p?.providerCode || '').trim().toLowerCase() === 'minimax';
  }, [providerFilter, providerOpts]);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (data) {
      const d = { ...data };
      if (isNeverOffline(d.offlineTime)) d.offlineTime = null;
      if (d.offlineTime) d.offlineTime = dayjs(d.offlineTime);
      // 兼容后端可能返回的 JSON 字符串 / 布尔 / 单值：规范化为字符串数组
      const toArr = (v: any): string[] => {
        if (Array.isArray(v)) return v.filter((x) => x !== null && x !== undefined && typeof x !== 'boolean').map((x) => String(x));
        if (v === null || v === undefined || v === '') return [];
        if (typeof v === 'string') {
          const s = v.trim();
          if (s.startsWith('[') && s.endsWith(']')) {
            try {
              const parsed = JSON.parse(s);
              return Array.isArray(parsed) ? parsed.filter((x) => typeof x !== 'boolean').map((x) => String(x)) : [];
            } catch { return []; }
          }
          return [s];
        }
        return [];
      };
      d.characterTypes = toArr(d.characterTypes);
      d.voiceStyles = toArr(d.voiceStyles);
      d.toneTags = toArr(d.toneTags);
      d.emotionTags = toArr(d.emotionTags);
      // 布尔能力字段：tinyint(1) 可能返回 0/1/true/false
      const toBool = (v: any) => v === true || v === 1 || v === '1';
      d.supportsEmotion = toBool(d.supportsEmotion);
      d.supportsSpeed = toBool(d.supportsSpeed);
      d.supportsPitch = toBool(d.supportsPitch);
      form.setFieldsValue(d);
      // 反查 providerId：优先用 data.providerId；否则从 modelOpts 中按 modelId 反查
      let pid: number | null = d.providerId || null;
      if (!pid && d.modelId) {
        const hit = modelOpts.find((m: any) => m.id === d.modelId);
        if (hit) pid = hit.providerId;
      }
      setProviderFilter(pid);
    } else {
      form.setFieldsValue({ language: 'zh-CN', gender: 'female', ageRange: 'young', status: '0', sortOrder: 0, defaultSpeed: 1.0, defaultPitch: 0 });
      setProviderFilter(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, data]);

  const handleOk = async () => {
    const values = await form.validateFields();
    if (values.offlineTime) values.offlineTime = dayjs.isDayjs(values.offlineTime) ? values.offlineTime.format('YYYY-MM-DD HH:mm:ss') : values.offlineTime;
    setLoading(true);
    try { await onOk({ ...data, ...values }); } finally { setLoading(false); }
  };

  return (
    <Modal
      open={open}
      title={title}
      onCancel={onCancel}
      onOk={handleOk}
      confirmLoading={loading}
      width={900}
      destroyOnClose
      maskClosable={false}
      okButtonProps={{ disabled: isSyncOnlyProvider }}
    >
      <Form form={form} layout="vertical" style={{ marginTop: 8, maxHeight: '65vh', overflowY: 'auto', paddingRight: 8 }}>
        {isSyncOnlyProvider && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message='MiniMax 音色仅支持通过"同步音色"维护'
            description='后端禁止手动新增/编辑 MiniMax 服务商下的音色，否则同步会覆盖你的改动。请前往 AI 管理 → 对应服务商 → 点击"同步音色"。'
          />
        )}

        {/* 1 归属 */}
        <SectionTitle title="归属信息" />
        <Row gutter={16}>
          <Col span={12}><Form.Item label="服务商"><Select allowClear placeholder="先选服务商以筛选模型" value={providerFilter} onChange={(v) => { setProviderFilter(v); form.setFieldValue('modelId', null); }} options={providerOpts.map((p: any) => ({ label: p.providerName, value: p.id }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="modelId" label="所属模型" rules={[{ required: true, message: '请选择模型' }]}><Select showSearch optionFilterProp="label" placeholder="请选择音频模型" options={filteredModels.map((m: any) => ({ label: m.modelName, value: m.id }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="voiceCode" label="音色编码" rules={[{ required: true }, { max: 128 }]}><Input placeholder="厂商侧 voice_id" maxLength={128} /></Form.Item></Col>
          <Col span={12}><Form.Item name="voiceName" label="展示名称" rules={[{ required: true }, { max: 100 }]}><Input placeholder="如：甜美少女音" maxLength={100} /></Form.Item></Col>
        </Row>

        {/* 2 展示 */}
        <SectionTitle title="展示资源" />
        <Row gutter={16}>
          <Col span={12}><Form.Item name="avatarUrl" label="头像URL" rules={[{ validator: urlValidator }]}><Input placeholder="http(s):// 开头" /></Form.Item></Col>
          <Col span={12}><Form.Item name="sampleUrl" label="试听URL" rules={[{ validator: urlValidator }]}><Input placeholder="http(s):// mp3" /></Form.Item></Col>
          <Col span={24}><Form.Item name="sampleText" label="试听文案"><Input.TextArea rows={2} maxLength={500} placeholder="展示用途" /></Form.Item></Col>
        </Row>

        {/* 3 基础属性 */}
        <SectionTitle title="基础属性" />
        <Row gutter={16}>
          <Col span={8}><Form.Item name="language" label="语言" rules={[{ required: true }]}><Select options={LANGUAGE_OPTIONS.map((o) => ({ label: o.name, value: o.code }))} /></Form.Item></Col>
          <Col span={8}><Form.Item name="gender" label="性别" rules={[{ required: true }]}><Select options={GENDER_OPTIONS.map((o) => ({ label: o.name, value: o.code }))} /></Form.Item></Col>
          <Col span={8}><Form.Item name="ageRange" label="年龄段" rules={[{ required: true }]}><Select options={AGE_RANGE_OPTIONS.map((o) => ({ label: o.name, value: o.code }))} /></Form.Item></Col>
        </Row>

        {/* 4 业务标签 */}
        <SectionTitle title="业务标签" />
        <Row gutter={16}>
          <Col span={12}><Form.Item name="characterTypes" label="角色类型"><Select mode="multiple" allowClear placeholder="可多选" options={(tagDict.characterTypes || []).map((t: any) => ({ label: t.tagName, value: t.tagCode }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="voiceStyles" label="使用场景"><Select mode="multiple" allowClear placeholder="可多选" options={(tagDict.voiceStyles || []).map((t: any) => ({ label: t.tagName, value: t.tagCode }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="toneTags" label="音调"><Select mode="multiple" allowClear placeholder="可多选" options={(tagDict.toneTags || []).map((t: any) => ({ label: t.tagName, value: t.tagCode }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="emotionTags" label="擅长情感" tooltip="候选随所选模型的供应商声明（capabilityJson.emotions）；模型未声明情感能力时不可选"><Select mode="multiple" allowClear placeholder={emotionOptions.length ? '可多选' : '所选模型未声明情感能力'} disabled={!emotionOptions.length} options={emotionOptions} /></Form.Item></Col>
        </Row>

        {/* 5 能力参数 */}
        <SectionTitle title="能力参数" />
        <Row gutter={16}>
          <Col span={8}><Form.Item name="supportsEmotion" label="支持情感" valuePropName="checked"><Switch /></Form.Item></Col>
          <Col span={8}><Form.Item name="supportsSpeed" label="支持语速" valuePropName="checked"><Switch /></Form.Item></Col>
          <Col span={8}><Form.Item name="supportsPitch" label="支持音调" valuePropName="checked"><Switch /></Form.Item></Col>
          <Col span={8}><Form.Item name="defaultSpeed" label="默认语速"><InputNumber min={0.5} max={2} step={0.1} precision={1} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={8}><Form.Item name="defaultPitch" label="默认音调"><InputNumber min={-12} max={12} step={1} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={8}><Form.Item name="sampleRate" label="采样率"><Select allowClear options={[{ label: '16000', value: 16000 }, { label: '24000', value: 24000 }, { label: '48000', value: 48000 }]} /></Form.Item></Col>
          <Col span={8}><Form.Item name="audioFormat" label="音频格式"><Select allowClear options={[{ label: 'mp3', value: 'mp3' }, { label: 'wav', value: 'wav' }, { label: 'pcm', value: 'pcm' }]} /></Form.Item></Col>
          <Col span={8}><Form.Item name="sortOrder" label="排序"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={8}><Form.Item name="status" label="状态"><Radio.Group><Radio.Button value="0">启用</Radio.Button><Radio.Button value="1">停用</Radio.Button></Radio.Group></Form.Item></Col>
          <Col span={12}><Form.Item name="offlineTime" label="下架时间"><DatePicker showTime style={{ width: '100%' }} placeholder="留空=永不下架" /></Form.Item></Col>
          <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea rows={2} maxLength={500} /></Form.Item></Col>
        </Row>
      </Form>
    </Modal>
  );
}
