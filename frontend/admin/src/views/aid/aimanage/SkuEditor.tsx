import React, { useState } from 'react';
import { Alert, Button, Input, InputNumber, Modal, Select, Space, Switch, Form, message } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { InputPricing, Sku, SkuEditData, PreviewResult } from './types';
import { makeEmptySku } from './helpers';
import { METER_TYPE_OPTIONS } from '@/utils/enums';

interface Props {
  data: SkuEditData;
  isTokenBilling: boolean;
  /** 计费口径（TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE / PER_CHAR），决定价格列与提示文案 */
  meterType?: string;
  onChange: (d: SkuEditData) => void;
  previewResult: PreviewResult | null;
  previewLoading: boolean;
  onPreview: (inputTokens: number, outputTokens: number) => void;
}

/** 带表头标签的字段容器：输入框上方显示灰色小标签，运营一眼知道每个框是什么 */
function Field({ label, width, children }: { label: string; width?: number | string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, width }}>
      <span style={{ fontSize: 11, color: '#94a3b8', lineHeight: '14px' }}>{label}</span>
      {children}
    </div>
  );
}

/** 常用匹配条件字段说明（添加条件弹窗提示用） */
const MATCH_KEY_HINTS: Record<string, string> = {
  resolution: '分辨率档位，如 480P / 720P / 1080P / 1K / 2K',
  generateMode: '生成模式，如 TEXT_TO_IMAGE / IMAGE_EDIT / TEXT_TO_VIDEO / IMAGE_TO_VIDEO',
  durationMin: '时长下限（秒），与 durationMax 成对构成区间',
  durationMax: '时长上限（秒）',
  audio: '是否音画同出（true / false），仅支持音画的视频模型使用'
};

export default function SkuEditor({
  data, isTokenBilling, meterType, onChange, previewResult, previewLoading, onPreview
}: Props) {
  const mt = (meterType || (isTokenBilling ? 'TOKEN' : '')).toUpperCase();
  const effectiveMeterType = (sku: Sku) => (sku.meterType || mt).toUpperCase();
  const hasTokenSku = isTokenBilling || data.skuList.some((sku) => effectiveMeterType(sku) === 'TOKEN');
  const [matchDlgOpen, setMatchDlgOpen] = useState(false);
  const [matchTargetIdx, setMatchTargetIdx] = useState<number | null>(null);
  const [matchNew, setMatchNew] = useState<{ key: string; value: string }>({ key: '', value: '' });

  const update = (patch: Partial<SkuEditData>) => onChange({ ...data, ...patch });
  const updateSku = (idx: number, patch: Partial<Sku>) => {
    const list = data.skuList.map((s, i) => (i === idx ? { ...s, ...patch } : s));
    update({ skuList: list });
  };
  /** 更新规则级输入媒体计费（image / video 两段按需合并） */
  const updateInputPricing = (patch: Partial<InputPricing>) => {
    update({ inputPricing: { ...(data.inputPricing || {}), ...patch } });
  };
  /** 更新某条 SKU 的输入媒体计费覆盖 */
  const updateSkuInputPricing = (idx: number, patch: Partial<InputPricing>) => {
    const sku = data.skuList[idx];
    updateSku(idx, { inputPricing: { ...(sku.inputPricing || {}), ...patch } });
  };
  const addSku = () => update({ skuList: [...data.skuList, makeEmptySku(isTokenBilling, data.skuList.length + 1)] });
  const removeSku = (idx: number) => update({ skuList: data.skuList.filter((_, i) => i !== idx) });

  const openAddMatch = (idx: number) => {
    setMatchTargetIdx(idx);
    setMatchNew({ key: '', value: '' });
    setMatchDlgOpen(true);
  };
  const confirmAddMatch = () => {
    if (!matchNew.key) { message.error('请输入字段名'); return; }
    if (matchTargetIdx === null) return;
    const sku = data.skuList[matchTargetIdx];
    updateSku(matchTargetIdx, { match: { ...sku.match, [matchNew.key]: matchNew.value } });
    setMatchDlgOpen(false);
  };
  const removeMatchKey = (idx: number, key: string) => {
    const sku = data.skuList[idx];
    const next: Record<string, any> = { ...sku.match };
    delete next[key];
    updateSku(idx, { match: next });
  };
  const updateMatchValue = (idx: number, key: string, value: string) => {
    const sku = data.skuList[idx];
    updateSku(idx, { match: { ...sku.match, [key]: value } });
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
        <span style={{ fontWeight: 500 }}>SKU 列表（{data.skuList.length}条）</span>
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={addSku}>添加SKU</Button>
      </div>
      {hasTokenSku && (
        <div style={{ marginBottom: 12 }}>
          <Space wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>字符Token比：</span>
            <InputNumber size="small" value={data.charToTokenRatio} min={1} max={10} onChange={(v) => update({ charToTokenRatio: v || 2 })} />
            <span style={{ fontSize: 12, color: '#64748b' }}>Usage 计费：</span>
            <Select size="small" style={{ width: 160 }} value={data.usagePricingMode || 'AGGREGATE'}
              onChange={(v) => update({ usagePricingMode: v })}
              options={[{ value: 'AGGREGATE', label: 'AGGREGATE 聚合' }, { value: 'BUCKETED', label: 'BUCKETED 分桶' }]} />
          </Space>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
            <span style={{ fontSize: 12, color: '#64748b' }}>允许超额补扣：</span>
            <Switch size="small" checked={data.allowExtraCharge === true}
              onChange={(checked) => update({ allowExtraCharge: checked })} />
            <span style={{ fontSize: 12, color: '#64748b' }}>
              开启后，实际用量超过预冻结时可补扣；关闭时按冻结上限结算。
            </span>
          </div>
          <Alert style={{ marginTop: 8 }} type="info" showIcon
            message={data.usagePricingMode === 'BUCKETED'
              ? '分桶模式：未缓存、缓存读、缓存写互斥计费；正文输出与思考输出互斥计费。'
              : '聚合模式：仍按总输入/总输出计费，缓存与思考明细只用于审计，不会重复加收。'} />
        </div>
      )}

      {/* 规则级输入媒体计费：参考图/输入视频附加费默认值（0 或不填 = 不计费；SKU 内可按档位覆盖视频输入价） */}
      <div style={{ border: '1px dashed #cbd5e1', borderRadius: 8, padding: 10, marginBottom: 12, background: '#f8fafc' }}>
        <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 6 }}>输入媒体计费（模型级默认）</div>
        <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 8 }}>
          参考图按张、输入视频按秒叠加到预扣金额；单价填 0 或留空 = 该类输入不计费。官方「首张免费、第 2 张起 X 元」等阶梯输入价统一拍平为固定单价配置。
        </div>
        <Space wrap align="end">
          <Field label="输入图片官方原价（元/张）" width={180}>
            <InputNumber size="small" style={{ width: '100%' }} min={0} precision={4}
              value={data.inputPricing?.image?.unitPrice ?? null}
              onChange={(v) => updateInputPricing({ image: { ...(data.inputPricing?.image || {}), unitPrice: v } })} />
          </Field>
          <Field label="图片计费张数上限（空=不限）" width={170}>
            <InputNumber size="small" style={{ width: '100%' }} min={0}
              value={data.inputPricing?.image?.maxCount ?? null}
              onChange={(v) => updateInputPricing({ image: { ...(data.inputPricing?.image || {}), maxCount: v } })} />
          </Field>
          <Field label="输入视频官方原价（元/秒）" width={180}>
            <InputNumber size="small" style={{ width: '100%' }} min={0} precision={4}
              value={data.inputPricing?.video?.unitPrice ?? null}
              onChange={(v) => updateInputPricing({ video: { ...(data.inputPricing?.video || {}), unitPrice: v } })} />
          </Field>
          <Field label="视频计费秒数上限（时长未知按此预扣）" width={220}>
            <InputNumber size="small" style={{ width: 120 }} min={0}
              value={data.inputPricing?.video?.maxSeconds ?? null}
              onChange={(v) => updateInputPricing({ video: { ...(data.inputPricing?.video || {}), maxSeconds: v } })} />
          </Field>
          <Field label="输入视频段数上限（空=不限）" width={180}>
            <InputNumber size="small" style={{ width: 120 }} min={0}
              value={data.inputPricing?.video?.maxCount ?? null}
              onChange={(v) => updateInputPricing({ video: { ...(data.inputPricing?.video || {}), maxCount: v } })} />
          </Field>
        </Space>
      </div>
      {data.skuList.length === 0 && (
        <div style={{ padding: 20, textAlign: 'center', color: '#94a3b8', background: '#fafbfc', borderRadius: 8 }}>
          暂无SKU，请点击"添加SKU"按钮
        </div>
      )}
      {data.skuList.map((sku, idx) => (
        <div key={idx} style={{ border: '1px solid #e5e7eb', borderRadius: 8, padding: 12, marginBottom: 10 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
            <Space>
              <span style={{ fontWeight: 500, fontSize: 13 }}>SKU-{idx + 1}</span>
              <Switch size="small" checked={sku.enabled} onChange={(v) => updateSku(idx, { enabled: v })} />
            </Space>
            <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={() => removeSku(idx)}>删除</Button>
          </div>
          <Space wrap style={{ marginBottom: 8 }} align="end">
            <Field label="SKU编码（唯一标识，结算快照记录用）" width={200}>
              <Input size="small" placeholder="如 AGNES_VIDEO_V20_PER_TASK" value={sku.skuCode} onChange={(e) => updateSku(idx, { skuCode: e.target.value })} />
            </Field>
            <Field label="SKU名称（C端计费详情展示）" width={200}>
              <Input size="small" placeholder="如 Agnes视频v2.0单次" value={sku.skuName} onChange={(e) => updateSku(idx, { skuName: e.target.value })} />
            </Field>
            <Field label="优先级（小的先匹配）" width={130}>
              <InputNumber size="small" style={{ width: '100%' }} placeholder="1" value={sku.priority} min={1} onChange={(v) => updateSku(idx, { priority: v || 1 })} />
            </Field>
            <Field label="本SKU计费口径（留空=继承模型）" width={210}>
              <Select
                size="small"
                allowClear
                placeholder={`继承：${mt}`}
                value={sku.meterType || undefined}
                options={METER_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))}
                onChange={(v) => updateSku(idx, { meterType: v || null })}
              />
            </Field>
            <Field label="备注（内部说明）" width={200}>
              <Input size="small" placeholder="如 官方$0.005/秒×700" value={sku.remark} onChange={(e) => updateSku(idx, { remark: e.target.value })} />
            </Field>
          </Space>

          {/* 匹配条件 */}
          <div style={{ background: '#fafbfc', padding: 10, borderRadius: 6, marginBottom: 8 }}>
            <div style={{ fontSize: 12, color: '#64748b', marginBottom: 6 }}>
              匹配条件（请求参数满足全部条件才命中本档；空条件 = 兜底全部命中）：
            </div>
            {effectiveMeterType(sku) === 'TOKEN' ? (
              <Space wrap align="end">
                <Field label="输入Token下限" width={130}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} value={sku.match.inputTokensMin} onChange={(v) => updateSku(idx, { match: { ...sku.match, inputTokensMin: v ?? 0 } })} />
                </Field>
                <span style={{ paddingBottom: 6 }}>~</span>
                <Field label="输入Token上限" width={130}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} value={sku.match.inputTokensMax} onChange={(v) => updateSku(idx, { match: { ...sku.match, inputTokensMax: v ?? 0 } })} />
                </Field>
              </Space>
            ) : (
              <Space wrap align="end">
                {Object.entries(sku.match || {}).map(([k, v]) => (
                  <Space key={k} size={4} align="end" style={{ background: '#fff', padding: '4px 6px', borderRadius: 4, border: '1px solid #e5e7eb' }}>
                    <Field label={MATCH_KEY_HINTS[k] ? `${k}（${MATCH_KEY_HINTS[k].split('，')[0]}）` : k} width={170}>
                      <Input size="small" value={String(v ?? '')} onChange={(e) => updateMatchValue(idx, k, e.target.value)} />
                    </Field>
                    <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => removeMatchKey(idx, k)} />
                  </Space>
                ))}
                <Button size="small" icon={<PlusOutlined />} onClick={() => openAddMatch(idx)}>添加条件</Button>
                {Object.keys(sku.match || {}).length === 0 && <span style={{ color: '#94a3b8', fontSize: 12 }}>暂无条件，本档为兜底价（如需按分辨率/时长/模式分档请添加，如 resolution: 720P、durationMax: 5）</span>}
              </Space>
            )}
          </div>

          {/* 价格（按计费口径显示对应单价列） */}
          <div>
            {effectiveMeterType(sku) === 'TOKEN' ? (
              <Space wrap align="end">
                <Field label="输入官方原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={6} value={sku.inputPricePerMillion} onChange={(v) => updateSku(idx, { inputPricePerMillion: v })} />
                </Field>
                <Field label="输出官方原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={6} value={sku.outputPricePerMillion} onChange={(v) => updateSku(idx, { outputPricePerMillion: v })} />
                </Field>
                <Field label="缓存读取原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={6} value={sku.cachedInputPricePerMillion} onChange={(v) => updateSku(idx, { cachedInputPricePerMillion: v })} />
                </Field>
                <Field label="缓存写入原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={6} value={sku.cacheWritePricePerMillion} onChange={(v) => updateSku(idx, { cacheWritePricePerMillion: v })} />
                </Field>
                <Field label="思考输出原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={6} value={sku.reasoningPricePerMillion} onChange={(v) => updateSku(idx, { reasoningPricePerMillion: v })} />
                </Field>
              </Space>
            ) : effectiveMeterType(sku) === 'PER_SECOND' ? (
              <Space wrap align="end">
                <Field label="每秒官方原价（元/秒）" width={190}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={4} value={sku.pricePerSecond} onChange={(v) => updateSku(idx, { pricePerSecond: v })} />
                </Field>
                <Field label="整包官方原价（仅旧继承规则兼容；新配置须填每秒价）" width={360}>
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={6} value={sku.price} onChange={(v) => updateSku(idx, { price: v })} />
                </Field>
                <Field label="输入视频官方原价（元/秒，选填；覆盖模型级输入计费）" width={330}>
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={4}
                    value={sku.inputPricing?.video?.unitPrice ?? null}
                    onChange={(v) => updateSkuInputPricing(idx, { video: { ...(sku.inputPricing?.video || {}), unitPrice: v } })} />
                </Field>
              </Space>
            ) : effectiveMeterType(sku) === 'PER_CHAR' ? (
              <Space wrap align="end">
                <Field label="每字符官方原价（元/字符）" width={220}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={6} value={sku.pricePerChar} onChange={(v) => updateSku(idx, { pricePerChar: v })} />
                </Field>
                <Field label="单次官方原价（仅旧继承规则兼容；新配置须填字符价）" width={350}>
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={6} value={sku.price} onChange={(v) => updateSku(idx, { price: v })} />
                </Field>
              </Space>
            ) : (
              <Space wrap align="end">
                <Field
                  label={effectiveMeterType(sku) === 'PER_IMAGE' ? '每张官方原价（元/张）'
                    : effectiveMeterType(sku) === 'SKU_PACKAGE' ? '整包官方原价（元/次）'
                    : '固定官方原价（元/次）'}
                  width={260}
                >
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={6} value={sku.price} onChange={(v) => updateSku(idx, { price: v })} />
                </Field>
                {effectiveMeterType(sku) === 'PER_IMAGE' && (
                  <Field label="输入图片官方原价（元/张，选填；覆盖模型级输入计费）" width={330}>
                    <InputNumber size="small" style={{ width: 130 }} min={0} precision={4}
                      value={sku.inputPricing?.image?.unitPrice ?? null}
                      onChange={(v) => updateSkuInputPricing(idx, { image: { ...(sku.inputPricing?.image || {}), unitPrice: v } })} />
                  </Field>
                )}
                {effectiveMeterType(sku) === 'SKU_PACKAGE' && (
                  <Field label="输入视频官方原价（元/秒，选填；覆盖模型级输入计费）" width={330}>
                    <InputNumber size="small" style={{ width: 130 }} min={0} precision={4}
                      value={sku.inputPricing?.video?.unitPrice ?? null}
                      onChange={(v) => updateSkuInputPricing(idx, { video: { ...(sku.inputPricing?.video || {}), unitPrice: v } })} />
                  </Field>
                )}
              </Space>
            )}
          </div>
        </div>
      ))}

      {/* 试算 */}
      {data.skuList.length > 0 && (
        <TrialCalc isTokenBilling={hasTokenSku} loading={previewLoading} result={previewResult} onCalc={onPreview} />
      )}

      {/* 添加匹配条件弹窗 */}
      <Modal title="添加匹配条件" open={matchDlgOpen} onCancel={() => setMatchDlgOpen(false)} onOk={confirmAddMatch} width={460} destroyOnClose>
        <Form layout="vertical">
          <Form.Item label="字段名" required help="Min/Max 后缀成对构成数值区间（如 durationMin + durationMax），其余为等值匹配（忽略大小写）">
            <Input placeholder="如 resolution / generateMode / durationMax / audio" value={matchNew.key} onChange={(e) => setMatchNew({ ...matchNew, key: e.target.value })} />
          </Form.Item>
          <Form.Item label="字段值">
            <Input placeholder="如 720P / TEXT_TO_VIDEO / 5 / true" value={matchNew.value} onChange={(e) => setMatchNew({ ...matchNew, value: e.target.value })} />
          </Form.Item>
          <div style={{ fontSize: 12, color: '#94a3b8', lineHeight: '20px' }}>
            常用字段：
            {Object.entries(MATCH_KEY_HINTS).map(([k, hint]) => (
              <div key={k}>
                <code style={{ color: '#2563eb' }}>{k}</code>：{hint}
              </div>
            ))}
          </div>
        </Form>
      </Modal>
    </div>
  );
}

function TrialCalc({ isTokenBilling, loading, result, onCalc }: { isTokenBilling: boolean; loading: boolean; result: PreviewResult | null; onCalc: (i: number, o: number) => void }) {
  const [inputT, setInputT] = useState<number>(1000);
  const [outputT, setOutputT] = useState<number>(500);
  const MAX_TOKENS = 10_000_000; // 限制试算规模
  const snapshot = result?.snapshot;
  const calculatedInputTokens = Number(snapshot?.requestParams?.inputTokens ?? inputT);
  const calculatedOutputTokens = Number(snapshot?.requestParams?.outputTokens ?? outputT);
  const formatAmount = (value?: number) => {
    if (value == null || Number.isNaN(Number(value))) return '0';
    return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 8, useGrouping: false });
  };
  return (
    <div style={{ marginTop: 12, padding: 12, background: 'rgba(37, 99, 235, 0.04)', borderRadius: 8, border: '1px dashed rgba(37, 99, 235, 0.3)' }}>
      <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 8, color: '#2563eb' }}>💡 试算测试</div>
      <Space wrap align="center">
        {isTokenBilling ? (
          <>
            <span style={{ fontSize: 12 }}>输入Token:</span>
            <InputNumber size="small" min={0} max={MAX_TOKENS} style={{ width: 120 }} value={inputT} onChange={(v) => setInputT(v ?? 0)} />
            <span style={{ fontSize: 12 }}>输出Token:</span>
            <InputNumber size="small" min={0} max={MAX_TOKENS} style={{ width: 120 }} value={outputT} onChange={(v) => setOutputT(v ?? 0)} />
          </>
        ) : (
          <span style={{ fontSize: 12, color: '#64748b' }}>将使用第一个 SKU 的匹配条件进行试算</span>
        )}
        <Button size="small" type="primary" loading={loading} onClick={() => onCalc(Math.min(inputT, MAX_TOKENS), Math.min(outputT, MAX_TOKENS))}>试算</Button>
      </Space>
      {result && (
        <div style={{ marginTop: 10, padding: '10px 12px', background: '#fff', borderRadius: 6, fontSize: 13, lineHeight: '22px' }}>
          <div>
            {result.skuCode && <span style={{ color: '#10b981', marginRight: 12 }}>✓ 命中：{result.skuCode}</span>}
            {result.amount != null && <span style={{ fontWeight: 600, color: '#dc2626' }}>预扣：{formatAmount(result.amount)} 积分</span>}
            {result.matched === false && <span style={{ color: '#dc2626' }}>{result.errorMessage || '未命中计费规则'}</span>}
          </div>
          {snapshot && result.amount != null && (
            <>
              {isTokenBilling && snapshot.inputPricePerMillion != null && snapshot.outputPricePerMillion != null && (
                <div style={{ color: '#64748b' }}>
                  官方原价：({formatAmount(calculatedInputTokens)} × {formatAmount(snapshot.inputPricePerMillion)} ÷ 1,000,000)
                  {' + '}({formatAmount(calculatedOutputTokens)} × {formatAmount(snapshot.outputPricePerMillion)} ÷ 1,000,000)
                  {' = '}{formatAmount(snapshot.baseAmount)} 元
                </div>
              )}
              <div style={{ color: '#334155', fontWeight: 500 }}>
                计费公式：{formatAmount(snapshot.baseAmount)} 元 × {formatAmount(snapshot.globalBillingMultiplier)} 积分/元
                {' × '}{formatAmount(snapshot.modelBillingMultiplier)} = {formatAmount(result.amount)} 积分
              </div>
              <div style={{ color: '#94a3b8', fontSize: 12 }}>
                官方原价 × 模型基础倍率 × 单模型倍率 = 最终预扣积分
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
