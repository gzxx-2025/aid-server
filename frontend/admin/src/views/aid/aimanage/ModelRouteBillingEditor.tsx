import React, { useRef, useState } from 'react';
import { Form, InputNumber, Segmented, Space, Switch, Table, Typography } from 'antd';
import { billingPreview } from '@/api/aid/aimanage';
import { METER_TYPE_OPTIONS } from '@/utils/enums';
import SkuEditor from './SkuEditor';
import { inferMeterType } from './constants';
import { buildBillingRuleJson, parseBillingRuleJson, parseCapabilityJsonToModel } from './helpers';
import type { Model, PreviewResult, Sku, SkuEditData } from './types';
import type { ModelProtocolBinding } from './modelDefinition';

const MATCH_LABELS: Record<string, string> = {
  size: '规格', resolution: '分辨率', duration: '时长（秒）', durationMin: '最短时长（秒）', durationMax: '最长时长（秒）',
  audio: '音画同出', generateAudio: '生成声音', audioMode: '音频模式', hasVideoInput: '包含视频输入', aspectRatio: '画面比例'
};

export default function ModelRouteBillingEditor({ route, modelType, onChange }: { route: ModelProtocolBinding; modelType: string; onChange: (patch: Partial<ModelProtocolBinding>) => void }) {
  const parsed = parseBillingRuleJson(JSON.stringify(route.billingRule || {}));
  const meterType = parsed.meterType || inferMeterType(modelType);
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
  const [busy, setBusy] = useState(false);
  const flight = useRef(false);
  const model = { modelType, meterType, billingMode: 'SKU', billingRuleJson: JSON.stringify(route.billingRule || {}) } as Model;
  const change = (data: SkuEditData, meter = meterType) => onChange({ billingRule: JSON.parse(buildBillingRuleJson({ ...model, meterType: meter }, data, meter === 'TOKEN')) });
  const updatePriceRow = (index: number, patch: Record<string, number | boolean | null>) => {
    const rule = route.billingRule;
    if (parsed.skuEditData.parseError || !rule || !Array.isArray(rule.skus)) return;
    // 价格表按原数组位置更新单条字段，保留其余 SKU、字段以及原始排列。
    onChange({ billingRule: { ...rule, skus: rule.skus.map((sku, i) => i === index && sku && typeof sku === 'object' && !Array.isArray(sku) ? { ...sku, ...patch } : sku) } });
  };
  const preview = async (inputTokens: number, outputTokens: number) => {
    if (flight.current) return;
    flight.current = true; setBusy(true);
    try {
      const testParams = meterType === 'TOKEN' ? { inputTokens, outputTokens } : { ...(parsed.skuEditData.skuList[0]?.match || {}) };
      const result = await billingPreview({ billingRuleJson: JSON.stringify(route.billingRule), testParams, billingMultiplier: 1 });
      setPreviewResult(result.data);
    } finally { flight.current = false; setBusy(false); }
  };
  return <>
    <Form.Item label="SKU 计费口径" extra="选中的口径决定每条 SKU 显示哪一组价格字段。">
      <Segmented block value={meterType} options={METER_TYPE_OPTIONS.map((item) => ({ label: item.label, value: item.value }))}
        onChange={(meter) => change(parsed.skuEditData, String(meter))} />
    </Form.Item>
    {parsed.skuEditData.skuList.length > 0 && <>
      <Typography.Title level={5}>SKU 价格表 · 可直接编辑</Typography.Title>
      <Table size="small" pagination={false} scroll={{ x: 760 }} style={{ marginBottom: 24 }}
        rowKey="rowIndex" dataSource={parsed.skuEditData.skuList.map((sku, rowIndex) => ({ ...sku, rowIndex }))}
        columns={[
          { title: 'SKU', width: 180, render: (_, sku) => <><strong>{sku.skuName || sku.skuCode || `SKU-${sku.rowIndex + 1}`}</strong><div>{sku.skuCode}</div></> },
          { title: '命中规格 / 条件', width: 240, render: (_, sku) => Object.entries(sku.match || {}).map(([key, value]) => `${MATCH_LABELS[key] || key}: ${typeof value === 'boolean' ? value ? '是' : '否' : typeof value === 'object' ? JSON.stringify(value) : String(value)}`).join('；') || '默认匹配' },
          { title: '官方原价', width: 260, render: (_, sku) => {
            const meter = (sku.meterType || meterType).toUpperCase();
            const fields: Array<{ field: keyof Sku; label: string }> = meter === 'TOKEN'
              ? [{ field: 'inputPricePerMillion', label: '输入 元/百万Token' }, { field: 'outputPricePerMillion', label: '输出 元/百万Token' }]
              : [{ field: meter === 'PER_SECOND' ? 'pricePerSecond' : meter === 'PER_CHAR' ? 'pricePerChar' : 'price',
                label: meter === 'PER_SECOND' ? '元/秒' : meter === 'PER_CHAR' ? '元/字符' : meter === 'PER_IMAGE' ? '元/张' : '元/次' }];
            return <Space direction="vertical">{fields.map(({ field, label }) => <label key={field}>
              <span>{label} </span><InputNumber aria-label={`${sku.skuCode} ${label}`} min={0} precision={8} style={{ width: 150 }}
                disabled={parsed.skuEditData.parseError} placeholder="未配置" value={sku[field] as number | null}
                onChange={(value) => updatePriceRow(sku.rowIndex, { [field]: value })} />
            </label>)}</Space>;
          } },
          { title: '启用状态', width: 100, render: (_, sku) => <Switch aria-label={`${sku.skuCode} 启用状态`}
            disabled={parsed.skuEditData.parseError} checked={sku.enabled} checkedChildren="启用" unCheckedChildren="停用"
            onChange={(enabled) => updatePriceRow(sku.rowIndex, { enabled })} /> }
        ]} />
      <Typography.Title level={5}>SKU 完整配置 · 条件、优先级与附加计费</Typography.Title>
    </>}
    <SkuEditor data={parsed.skuEditData} isTokenBilling={meterType === 'TOKEN'} meterType={meterType} modelType={modelType}
      protocol={route.protocol} capability={parseCapabilityJsonToModel(JSON.stringify(route.capability || {}))}
      onChange={change} previewResult={previewResult} previewLoading={busy} onPreview={preview} />
  </>;
}
