import React, { useEffect, useRef, useState } from 'react';
import { Alert, Col, Form, Input, InputNumber, Modal, Row, Select, Space, Switch, Tabs, Tag, message } from 'antd';
import { MODEL_TYPE_OPTIONS, ENABLE_STATUS_OPTIONS } from '@/utils/enums';
import ImageUpload from '@/components/ImageUpload';
import { useAuth } from '@/hooks/useAuth';
import ModelCapabilitiesEditor from './ModelCapabilitiesEditor';
import ModelBusinessBindingEditor from './ModelBusinessBindingEditor';
import ModelTemplatePicker from './ModelTemplatePicker';
import { definitionErrors, type ModelCapabilityDefinition } from './modelDefinition';
import { mergeMaxConcurrency, parseMaxConcurrency } from './helpers';
import type { Model, Provider } from './types';
import { getModelBillingOverview } from './billingSummary';

interface Props {
  open: boolean;
  title: string;
  provider: Provider | null;
  data?: Partial<Model>;
  onCancel: () => void;
  onOk: (values: Model) => Promise<void>;
}

/** 模型层维护统一身份与运营设置，能力和调用参数只有一个编辑入口。 */
export default function ModelDialog({ open, title, provider, data, onCancel, onOk }: Props) {
  const [form] = Form.useForm();
  const { hasPermi } = useAuth();
  const canEditBindings = hasPermi('aid:funcconfig:edit');
  const [model, setModel] = useState<Model>({} as Model);
  const [definitions, setDefinitions] = useState<ModelCapabilityDefinition[]>([]);
  const [tab, setTab] = useState('basic');
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);

  useEffect(() => {
    if (!open) return;
    const next = { modelType: '', priority: 1, status: '0', billingMultiplier: 1, ...data, isFree: data?.isFree === true } as Model;
    setModel(next);
    setDefinitions(data?.capabilities || []);
    form.resetFields();
    form.setFieldsValue({ ...next, maxConcurrency: parseMaxConcurrency(next.scheduleStrategyJson) });
    setTab(data?.id ? 'capabilities' : 'basic');
  }, [open, data, form]);

  const save = async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    try {
      let values;
      try { values = await form.validateFields(); }
      catch (error: any) {
        const field = error?.errorFields?.[0]?.name?.[0];
        setTab(['billingMultiplier', 'maxConcurrency', 'priority', 'status', 'isFree'].includes(field) ? 'operations' : 'basic');
        return;
      }
      const errors = definitionErrors(definitions);
      if (errors.length) { message.error(errors[0]); setTab('capabilities'); return; }
      const invalidOutputRange = definitions.some((definition) => definition.bindings.some((binding) => {
        const capability = binding.capability || {};
        const invalid = (minimum: unknown, maximum: unknown) => typeof minimum === 'number'
          && typeof maximum === 'number' && minimum > maximum;
        return invalid(capability.minOutputPixels, capability.maxOutputPixels)
          || invalid(capability.minOutputAspectRatio, capability.maxOutputAspectRatio);
      }));
      if (invalidOutputRange) {
        message.error('输出画面最小值不能大于最大值');
        setTab('capabilities');
        return;
      }
      const capability = definitions.find((item) => item.enabled && item.defaultCapability)!;
      const route = capability.bindings.find((item) => item.enabled && item.defaultBinding)!;
      const result: Model = {
        ...model, ...values, providerId: provider?.id,
        ...capability.presentation, ...route.presentation,
        capabilities: definitions,
        generateMode: capability.generateMode,
        protocol: route.protocol, apiVersion: route.apiVersion, apiSuffix: route.apiSuffix,
        capabilityJson: JSON.stringify(route.capability || {}),
        paramMappingJson: JSON.stringify(route.parameterMapping || {}),
        extraBody: JSON.stringify(route.fixedParameters || {}),
        billingMode: route.billingMode || 'FIXED',
        billingRuleJson: JSON.stringify(route.billingRule || { mode: 'FIXED', preHold: true }),
        costCredits: route.billingMode === 'SKU' ? null : route.costCredits ?? null,
        scheduleStrategyJson: mergeMaxConcurrency(model.scheduleStrategyJson, values.maxConcurrency)
      };
      delete (result as any).maxConcurrency;
      if (!canEditBindings) delete result.businessBindings;
      await onOk(result);
    } finally { savingRef.current = false; setSaving(false); }
  };

  const bodyStyle: React.CSSProperties = { maxHeight: '72vh', overflowY: 'auto', paddingRight: 8, paddingTop: 8 };
  const billingOverview = getModelBillingOverview({ ...model, capabilities: definitions });
  const billingTabLabel = <Space size={4}>
    <span>能力、调用与计费</span>
    {billingOverview.skuRouteCount > 0
      ? <Tag color="purple">SKU {billingOverview.enabledSkuCount}</Tag>
      : billingOverview.fixedRouteCount > 0 ? <Tag color="blue">固定价</Tag> : null}
    {billingOverview.issueCount > 0 && <Tag color="error">待补价格</Tag>}
  </Space>;
  return <Modal open={open} title={title} width={1280} style={{ top: 24 }} destroyOnClose maskClosable={false}
    confirmLoading={saving} onOk={save} onCancel={() => { if (!savingRef.current) onCancel(); }}>
    <Form form={form} layout="vertical" onValuesChange={(changed) => setModel((current) => ({ ...current, ...changed }))}>
      <Tabs activeKey={tab} onChange={setTab} items={[
        { key: 'basic', label: '基本信息', forceRender: true, children: <div style={bodyStyle}>
          <Alert type="info" showIcon message="一个真实模型只维护一行，首尾帧、多参考等用法在能力中配置。不同供应商和实际版本保持独立。" style={{ marginBottom: 16 }} />
          <Row gutter={16}>
            <Col span={12}><Form.Item name="modelName" label="模型名称" rules={[{ required: true, whitespace: true }]}><Input placeholder="如：可灵 3.0 Omni" maxLength={100} /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelType" label="模型分类" rules={[{ required: true }]}><Select disabled={Boolean(data?.id)} options={MODEL_TYPE_OPTIONS.map((item) => ({ value: item.value, label: item.label }))} /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelCode" label="平台模型编码" rules={[{ required: true, whitespace: true }]} tooltip="系统内唯一的稳定引用，不按业务能力添加后缀。"><Input disabled={Boolean(data?.id)} maxLength={100} placeholder="如：kling-3-omni" /></Form.Item></Col>
            <Col span={12}><Form.Item name="realModelCode" label="真实模型标识" rules={[{ required: true, whitespace: true }]} tooltip="填写原厂真实模型及版本标识。渠道 Endpoint ID、req_key 等路由信息在调用配置中维护。"><Input maxLength={255} placeholder="如：kling-3.0-omni" /></Form.Item></Col>
            <Col span={24}><Form.Item name="logoUrl" label="模型图标"><ImageUpload maxCount={1} maxSize={5} accept="image/*" /></Form.Item></Col>
            <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item></Col>
          </Row>
        </div> },
        { key: 'capabilities', label: billingTabLabel, children: <div style={bodyStyle}><ModelTemplatePicker current={definitions} modelType={model.modelType || ''} upstreamModel={model.realModelCode || model.modelCode} onChange={setDefinitions} /><ModelCapabilitiesEditor value={definitions} modelType={model.modelType || ''} upstreamModel={model.realModelCode || model.modelCode} onChange={setDefinitions} /></div> },
        { key: 'operations', label: '统一运营配置', forceRender: true, children: <div style={bodyStyle}><Row gutter={16}>
          <Col span={12}><Form.Item name="status" label="模型状态"><Select options={ENABLE_STATUS_OPTIONS.map((item) => ({ value: item.value, label: item.label }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="priority" label="调度优先级"><InputNumber min={1} max={999} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={12}><Form.Item name="billingMultiplier" label="单模型倍率" rules={[{ required: true }]} tooltip="应用于这个模型的所有能力；具体规格原价在能力的 SKU 中维护。"><InputNumber min={0.01} precision={4} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={12}><Form.Item name="isFree" label="免费使用" valuePropName="checked"><Switch checkedChildren="免费" unCheckedChildren="正常收费" /></Form.Item></Col>
          <Col span={12}><Form.Item name="maxConcurrency" label="模型并发上限" tooltip="所有能力共享。留空时使用供应商与全局限制。"><InputNumber min={1} max={1000} style={{ width: '100%' }} /></Form.Item></Col>
        </Row></div> },
        ...(canEditBindings ? [{ key: 'business', label: '业务绑定', children: <div style={bodyStyle}><ModelBusinessBindingEditor value={model.businessBindings || []} capabilities={definitions} modelType={model.modelType || ''} onChange={(businessBindings) => setModel((current) => ({ ...current, businessBindings }))} /></div> }] : [])
      ]} />
    </Form>
  </Modal>;
}
