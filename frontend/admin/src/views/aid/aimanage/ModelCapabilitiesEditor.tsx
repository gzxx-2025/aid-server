import ModelFieldMappingEditor from './ModelFieldMappingEditor';
import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Collapse, Form, Input, InputNumber, Radio, Row, Select, Space, Switch, Tabs, Tag, Typography } from 'antd';
import { AppstoreOutlined, DeleteOutlined, DollarOutlined, PlusOutlined } from '@ant-design/icons';
import { request } from '@/utils/request';
import { sharedReadRequest } from '@/utils/sharedReadRequest';
import ModelParameterEditor from './ModelParameterEditor';
import ModelRouteCapabilityEditor from './ModelRouteCapabilityEditor';
import ModelRouteBillingEditor from './ModelRouteBillingEditor';
import ModelRuleEditor from './ModelRuleEditor';
import ModelParameterPreview from './ModelParameterPreview';
import StructuredValueEditor, { type ConfigValue } from './StructuredValueEditor';
import { CAPABILITY_OPTIONS, definitionErrors, parameterPaths, type ModelCapabilityDefinition, type ModelProtocolBinding } from './modelDefinition';
import { buildBillingRuleJson, makeEmptySku, parseBillingRuleJson } from './helpers';
import { inferMeterType } from './constants';
import type { Model, SkuEditData } from './types';
import { summarizeCapabilityBilling, summarizeRouteBilling } from './billingSummary';

const loadProtocols = (type: string) => sharedReadRequest('/aid/aidmodel/protocol-options', { modelType: type })
  .then((response) => response.data || []);

const newBinding = (index: number): ModelProtocolBinding => ({ code: `route_${index}`, protocol: '', enabled: true, defaultBinding: index === 1, billingMode: 'FIXED', capability: {}, fixedParameters: {}, mappings: [] });

export default function ModelCapabilitiesEditor({ value, modelType, upstreamModel, onChange: onDefinitionsChange }: { value: ModelCapabilityDefinition[]; modelType: string; upstreamModel?: string; onChange: (value: ModelCapabilityDefinition[]) => void }) {
  const valueRef = useRef(value);
  valueRef.current = value;
  const onChange = (next: ModelCapabilityDefinition[]) => { valueRef.current = next; onDefinitionsChange(next); };
  const [selected, setSelected] = useState(0);
  const [protocols, setProtocols] = useState<Array<{ value: string; label: string }>>([]);
  const [loadError, setLoadError] = useState('');
  const [preview, setPreview] = useState<Record<string, ConfigValue>>({});
  const [previewResult, setPreviewResult] = useState('');
  const [previewErrors, setPreviewErrors] = useState<Record<string, string>>({});
  const [previewBusy, setPreviewBusy] = useState(false);
  const previewFlight = useRef(false);
  useEffect(() => {
    let active = true;
    if (!modelType) { setProtocols([]); return; }
    loadProtocols(modelType).then((options) => { if (active) { setProtocols(options); setLoadError(''); } })
      .catch(() => { if (active) setLoadError('协议列表读取失败，请稍后重试'); });
    return () => { active = false; };
  }, [modelType]);
  const index = Math.min(selected, Math.max(0, value.length - 1));
  const definition = value[index];
  const update = (patch: Partial<ModelCapabilityDefinition>) => onChange(valueRef.current.map((d, i) => i === index ? { ...d, ...patch } : d));
  const routeUpdate = (routeIndex: number, patch: Partial<ModelProtocolBinding>) => update({ bindings: valueRef.current[index].bindings.map((r, i) => i === routeIndex ? { ...r, ...patch } : r) });
  const errors = definitionErrors(value);
  const selectBillingMode = (routeIndex: number, route: ModelProtocolBinding, billingMode: 'FIXED' | 'SKU') => {
    if (billingMode === 'FIXED') {
      routeUpdate(routeIndex, { billingMode });
      return;
    }
    const parsed = parseBillingRuleJson(JSON.stringify(route.billingRule || {}));
    // 模式切换不得重排或重建既有 SKU；只有规则可安全解析且确实没有 SKU 时才补一条空白规则。
    if (parsed.skuEditData.parseError || parsed.skuEditData.skuList.length > 0) {
      routeUpdate(routeIndex, { billingMode });
      return;
    }
    const meterType = parsed.meterType || inferMeterType(modelType);
    const skuEditData: SkuEditData = {
      ...parsed.skuEditData,
      skuList: [makeEmptySku(meterType === 'TOKEN', 1)]
    };
    const billingRule = JSON.parse(buildBillingRuleJson({
      modelType,
      meterType,
      billingMode: 'SKU'
    } as Model, skuEditData, meterType === 'TOKEN'));
    routeUpdate(routeIndex, { billingMode, billingRule });
  };
  const renderBillingEditor = (route: ModelProtocolBinding, routeIndex: number) => {
    const billingMode = route.billingMode === 'SKU' ? 'SKU' : 'FIXED';
    const summary = summarizeRouteBilling(route, '', inferMeterType(modelType));
    return <div className="route-billing-editor">
      <div className="billing-mode-selector" role="radiogroup" aria-label="计费模式">
        <button type="button" role="radio" aria-checked={billingMode === 'FIXED'}
          className={billingMode === 'FIXED' ? 'billing-mode-selector__item active' : 'billing-mode-selector__item'}
          onClick={() => selectBillingMode(routeIndex, route, 'FIXED')}>
          <DollarOutlined aria-hidden />
          <span><strong>固定价格</strong><small>所有请求使用同一个官方原价</small></span>
        </button>
        <button type="button" role="radio" aria-checked={billingMode === 'SKU'}
          className={billingMode === 'SKU' ? 'billing-mode-selector__item active' : 'billing-mode-selector__item'}
          onClick={() => selectBillingMode(routeIndex, route, 'SKU')}>
          <AppstoreOutlined aria-hidden />
          <span><strong>SKU 规格计费</strong><small>按规格、时长或用量命中不同价格</small></span>
        </button>
      </div>
      <div className={`billing-mode-status billing-mode-status--${billingMode.toLowerCase()}`} role="status">
        <div>
          <strong>{billingMode === 'SKU' ? '当前已启用 SKU 计费' : '当前使用固定价格计费'}</strong>
          <span>{billingMode === 'SKU'
            ? `共 ${summary.skuCount} 条 SKU，${summary.enabledSkuCount} 条启用，${summary.pricedSkuCount} 条已填写价格`
            : summary.priceLabels[0] || '尚未填写官方原价'}</span>
        </div>
        <Tag color={summary.hasIssue ? 'error' : billingMode === 'SKU' ? 'purple' : 'blue'}>
          {summary.hasIssue ? '价格待完善' : billingMode === 'SKU' ? `SKU ${summary.enabledSkuCount}` : '固定价'}
        </Tag>
      </div>
      {billingMode === 'FIXED'
        ? <Form.Item label="官方原价（元/次）" extra="最终扣除积分还会乘以系统基础倍率和单模型倍率。">
            <InputNumber min={0} precision={8} value={route.costCredits} style={{ width: 240 }}
              onChange={(costCredits) => routeUpdate(routeIndex, { costCredits: costCredits ?? undefined })} />
          </Form.Item>
        : <ModelRouteBillingEditor route={route} modelType={modelType} onChange={(patch) => routeUpdate(routeIndex, patch)} />}
    </div>;
  };
  const testPreview = async () => {
    if (previewFlight.current || !definition) return;
    previewFlight.current = true; setPreviewBusy(true); setPreviewResult(''); setPreviewErrors({});
    const definitionSnapshot = JSON.stringify(definition);
    try {
      const result = await request({ url: '/aid/aidmodel/parameter-preview', method: 'post', data: { definition, parameters: preview } });
      if (JSON.stringify(valueRef.current[index]) !== definitionSnapshot) return;
      setPreview(result.data || {}); setPreviewResult('参数校验通过');
    } catch (error: any) {
      if (JSON.stringify(valueRef.current[index]) !== definitionSnapshot) return;
      const text = error?.message || '参数校验未通过，请检查条件和取值';
      setPreviewResult(text);
      const match = /^([^：]+)：(.+)$/.exec(text);
      if (match) setPreviewErrors({ [match[1]]: match[2] });
    }
    finally { previewFlight.current = false; setPreviewBusy(false); }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    {loadError && <Alert type="error" message={loadError} />}
    {errors.length > 0 && <Alert type="warning" message="保存前需要完善配置" description={<ul>{errors.map((error) => <li key={error}>{error}</li>)}</ul>} />}
    <div className="model-capability-picker" role="tablist" aria-label="模型能力">
      {value.map((item, itemIndex) => {
        const billing = summarizeCapabilityBilling(item, inferMeterType(modelType));
        const selectedItem = itemIndex === index;
        return <button key={`${item.code}-${itemIndex}`} type="button" role="tab" aria-selected={selectedItem}
          disabled={previewBusy} className={selectedItem ? 'model-capability-picker__item active' : 'model-capability-picker__item'}
          onClick={() => { setSelected(itemIndex); setPreview({}); setPreviewResult(''); setPreviewErrors({}); }}>
          <span className="model-capability-picker__title">{item.label || item.code}</span>
          <span className="model-capability-picker__meta">
            {item.enabled ? '已启用' : '已停用'}
            {billing.skuRouteCount > 0 ? ` · SKU ${billing.enabledSkuCount} 条` : billing.fixedRouteCount > 0 ? ' · 固定价' : ' · 未配置协议'}
            {billing.issueCount > 0 ? ' · 价格待完善' : ''}
          </span>
        </button>;
      })}
      <Button className="model-capability-picker__add" icon={<PlusOutlined />} onClick={() => { onChange([...value, { code: `capability_${value.length + 1}`, label: '新能力', generateMode: '', enabled: true, defaultCapability: value.length === 0, parameters: [], rules: [], presentation: {}, bindings: [{ ...newBinding(1), upstreamModel }] }]); setSelected(value.length); }}>添加能力</Button>
    </div>
    {definition && <div className="model-capability-picker__actions">
      <span>正在编辑：<strong>{definition.label || definition.code}</strong></span>
      <Button danger size="small" icon={<DeleteOutlined />} onClick={() => onChange(value.filter((_, i) => i !== index))}>删除当前能力</Button>
    </div>}
    {definition && <>
      <Space wrap>
        <label>能力可用 <Switch aria-label="能力可用" checkedChildren="启用" unCheckedChildren="停用" checked={definition.enabled} onChange={(enabled) => update({ enabled })} /></label>
        <Radio checked={definition.defaultCapability} onChange={() => onChange(value.map((d, i) => ({ ...d, defaultCapability: i === index })))}>默认能力</Radio>
      </Space>
      <Collapse items={[{ key: 'identity', label: '能力名称、编码与核验资料', children:
      <Row gutter={12}>
        <Col span={8}><Form.Item label="能力类型"><Select value={definition.generateMode || undefined} options={CAPABILITY_OPTIONS.filter((o) => modelType === 'video' ? o.value.includes('video') || o.value === 'lip_sync' : modelType === 'image' ? o.value.includes('image') : modelType === 'audio' ? ['audio', 'voice_clone'].includes(o.value) : o.value === 'text')} onChange={(generateMode) => update({ generateMode, code: generateMode, label: CAPABILITY_OPTIONS.find((o) => o.value === generateMode)?.label || generateMode })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="能力名称"><Input value={definition.label} onChange={(e) => update({ label: e.target.value })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="能力编码"><Input value={definition.code} onChange={(e) => update({ code: e.target.value })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="核验状态"><Select value={definition.evidenceStatus || 'LEGACY_PENDING_REVIEW'} options={[{ value: 'LEGACY_PENDING_REVIEW', label: '待核验' }, { value: 'VERIFIED', label: '已核验' }, { value: 'PARTIAL', label: '部分核验' }]} onChange={(evidenceStatus) => update({ evidenceStatus })} /></Form.Item></Col>
        <Col span={24}><Form.Item label="依据文档链接"><Select mode="tags" value={definition.sourceUrls || []} onChange={(sourceUrls) => update({ sourceUrls })} placeholder="输入官方文档链接后按回车添加" /></Form.Item></Col>
      </Row>
      }]} />
      <Tabs defaultActiveKey="capability" items={[
        { key: 'capability', label: '能力开关与规格', children: <Space direction="vertical" style={{ width: '100%' }}>
          {definition.bindings.map((route, ri) => <Card key={ri} size="small"
            title={<Space wrap><span>{route.protocol || `协议 ${ri + 1}`}</span><Tag>{route.code}</Tag><Tag color={route.enabled ? 'green' : 'default'}>{route.enabled ? '已启用' : '已停用'}</Tag></Space>}>
            <ModelRouteCapabilityEditor definition={definition} route={route} modelType={modelType} onChange={(patch) => routeUpdate(ri, patch)} />
          </Card>)}
          {!definition.bindings.length && <Alert type="info" message="请先在调用协议中添加协议，再配置能力与规格。" />}
        </Space> },
        { key: 'billing', label: 'SKU 与价格', children: <Space direction="vertical" style={{ width: '100%' }}>
          {definition.bindings.map((route, ri) => <Card key={ri} size="small"
            title={<Space wrap><span>{route.protocol || `协议 ${ri + 1}`}</span><Tag>{route.code}</Tag><Tag color={route.billingMode === 'SKU' ? 'purple' : 'blue'}>{route.billingMode === 'SKU' ? 'SKU 计费' : '固定价格'}</Tag></Space>}>
            {renderBillingEditor(route, ri)}
          </Card>)}
          {!definition.bindings.length && <Alert type="info" message="请先在调用协议中添加协议，再配置 SKU 与价格。" />}
        </Space> },
        { key: 'parameters', label: '参数表单', children: <ModelParameterEditor modelType={modelType} value={definition.parameters || []} onChange={(parameters) => update({ parameters })} /> },
        { key: 'rules', label: '条件规则', children: <ModelRuleEditor statistics={modelType === 'video'} value={definition.rules || []} fields={parameterPaths(definition.parameters || [])} onChange={(rules) => update({ rules })} /> },
        { key: 'routes', label: '调用协议', children: <Space direction="vertical" style={{ width: '100%' }}>
          {definition.bindings.map((route, ri) => {
            const billing = summarizeRouteBilling(route, '', inferMeterType(modelType));
            return <Card key={ri} size="small" title={<Space size={6} wrap>
              <span>{`协议 ${ri + 1}`}</span>
              <Tag color={billing.mode === 'SKU' ? 'purple' : 'blue'}>{billing.mode === 'SKU' ? `SKU ${billing.enabledSkuCount} 条` : '固定价'}</Tag>
              {billing.hasIssue && <Tag color="error">价格待完善</Tag>}
            </Space>} extra={<Button aria-label={`删除协议 ${ri + 1}`} icon={<DeleteOutlined />} onClick={() => update({ bindings: definition.bindings.filter((_, i) => i !== ri) })} />}>
            <Row gutter={12}>
              <Col span={12}><Form.Item label="协议"><Select showSearch value={route.protocol || undefined} options={protocols} onChange={(protocol) => routeUpdate(ri, { protocol })} /></Form.Item></Col>
              <Col span={12}><Form.Item label="绑定编码"><Input value={route.code} onChange={(e) => routeUpdate(ri, { code: e.target.value })} /></Form.Item></Col>
              <Col span={12}><Form.Item label="上游调用标识" tooltip="按渠道填写模型 ID 或 Endpoint ID；真实模型身份在基本信息中维护。"><Input value={route.upstreamModel} onChange={(e) => routeUpdate(ri, { upstreamModel: e.target.value })} /></Form.Item></Col>
              <Col span={12}><Form.Item label="接口版本"><Input value={route.apiVersion} onChange={(e) => routeUpdate(ri, { apiVersion: e.target.value })} /></Form.Item></Col>
              <Col span={12}><Form.Item label="提交接口路径"><Input value={route.apiSuffix} onChange={(e) => routeUpdate(ri, { apiSuffix: e.target.value })} placeholder="如 /v1/chat/completions" /></Form.Item></Col>
              <Col span={12}><Form.Item label="查询接口路径"><Input value={route.taskQuerySuffix} onChange={(e) => routeUpdate(ri, { taskQuerySuffix: e.target.value })} /></Form.Item></Col>
              <Col span={6}><Form.Item label="协议可用"><Switch checked={route.enabled} onChange={(enabled) => routeUpdate(ri, { enabled })} /></Form.Item></Col>
              <Col span={6}><Form.Item label="默认协议"><Radio checked={route.defaultBinding} onChange={() => update({ bindings: definition.bindings.map((r, i) => ({ ...r, defaultBinding: i === ri })) })}>设为默认</Radio></Form.Item></Col>
            </Row>
            <Tabs items={[
              { key: 'fixed', label: '固定参数', children: <StructuredValueEditor fixedType="object" value={route.fixedParameters || {}} onChange={(next) => routeUpdate(ri, { fixedParameters: next as Record<string, ConfigValue> })} /> },
              { key: 'mapping', label: '参数映射', children: <ModelFieldMappingEditor value={route.mappings || []} sources={parameterPaths(definition.parameters)} fixedParameters={route.fixedParameters} onChange={(mappings) => routeUpdate(ri, { mappings })} /> },
            ]} />
          </Card>;})}
          <Button icon={<PlusOutlined />} onClick={() => update({ bindings: [...definition.bindings, { ...newBinding(definition.bindings.length + 1), upstreamModel }] })}>添加调用协议</Button>
        </Space> },
        { key: 'preview', label: '表单预览', children: <><ModelParameterPreview disabled={previewBusy} fields={definition.parameters || []} rules={definition.rules || []} value={preview} errors={previewErrors} onChange={(next) => { setPreview(next); setPreviewErrors({}); setPreviewResult(''); }} /><Button loading={previewBusy} onClick={testPreview}>校验参数</Button>{previewResult && <Typography.Paragraph role="status">{previewResult}</Typography.Paragraph>}</> }
      ]} />
    </>}
  </Space>;
}
