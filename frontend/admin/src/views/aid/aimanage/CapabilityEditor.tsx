import React, { useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Collapse, Input, InputNumber, Select, Space, Switch, Tooltip, message } from 'antd';
import { PlusOutlined, RedoOutlined } from '@ant-design/icons';
import type { CapabilityModel, Model } from './types';
import { PRESET_SIZE, PRESET_ASPECT, PRESET_DURATION, makeEmptyCapabilityModel } from './constants';
import { buildCapabilityJsonObject } from './helpers';

interface Props {
  modelType: string;
  form: Model;
  cap: CapabilityModel;
  onCapChange: (c: CapabilityModel) => void;
  onFormChange: (patch: Partial<Model>) => void;
}

/** 分组小标题：统一 13px 中黑，右侧可带键名提示 */
function GroupLabel({ text, keyName }: { text: string; keyName?: string }) {
  return (
    <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 6, color: '#334155' }}>
      {text}
      {keyName && <code className="code-text" style={{ marginLeft: 6 }}>{keyName}</code>}
    </div>
  );
}

export default function CapabilityEditor({ modelType, form, cap, onCapChange, onFormChange }: Props) {
  const [newSize, setNewSize] = useState('');
  const [newAspect, setNewAspect] = useState('');
  const [newDuration, setNewDuration] = useState(5);
  const defaultAudioEnabled = cap.supportsAudio && cap.preservedCapability?.defaultAudio !== false;
  const configurableAsyncVideo = form.protocol === 'configurable-async-video';

  const presetSizes = PRESET_SIZE[modelType] || [];
  const presetAspects = PRESET_ASPECT[modelType] || [];
  const sizeChoices = useMemo(() => {
    const all = [...presetSizes];
    cap.sizeOptions.forEach((v) => { if (!all.includes(v)) all.push(v); });
    return all;
  }, [cap.sizeOptions, presetSizes]);
  const aspectChoices = useMemo(() => {
    const all = [...presetAspects];
    cap.aspectRatioOptions.forEach((v) => { if (!all.includes(v)) all.push(v); });
    return all;
  }, [cap.aspectRatioOptions, presetAspects]);
  const durationChoices = useMemo(() => {
    const all = [...PRESET_DURATION];
    cap.durationOptions.forEach((v) => { if (!all.includes(v)) all.push(v); });
    return all.sort((a, b) => a - b);
  }, [cap.durationOptions]);

  const updateCap = (patch: Partial<CapabilityModel>) => onCapChange({ ...cap, ...patch });

  const updateTextModalities = (values: string[]) => {
    const inputModalities = Array.from(new Set(['TEXT', ...values]));
    const supportsImageInput = inputModalities.includes('IMAGE');
    const maxInputImages = supportsImageInput ? cap.maxInputImages || 10 : 0;
    updateCap({ inputModalities, maxInputImages,
      maxInputVideos: inputModalities.includes('VIDEO') ? cap.maxInputVideos || 10 : 0,
      maxInputAudios: inputModalities.includes('AUDIO') ? cap.maxInputAudios || 10 : 0,
      maxInputDocuments: inputModalities.includes('DOCUMENT') ? cap.maxInputDocuments || 10 : 0 });
    onFormChange({ supportsImageInput,
      supportsMultiImageInput: supportsImageInput && (maxInputImages === -1 || maxInputImages > 1) });
  };

  const resolutionMappingValue = (source: string) => {
    const mapping = cap.upstreamResolutionMap || {};
    const key = Object.keys(mapping).find((item) => item.toLowerCase() === source.toLowerCase());
    return key ? mapping[key] : '';
  };

  const updateResolutionMapping = (source: string, target: string) => {
    const next = { ...(cap.upstreamResolutionMap || {}) };
    Object.keys(next).forEach((key) => {
      if (key.toLowerCase() === source.toLowerCase()) delete next[key];
    });
    if (target) next[source] = target;
    updateCap({ upstreamResolutionMap: next });
  };

  if (modelType === 'text') {
    return <div>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="这里声明模型能力；是否流式和是否思考由每次业务调用决定，供应商真实 usage 决定结算。" />
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="输入模态" keyName="inputModalities" />
        <Checkbox.Group
          options={['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT']}
          value={cap.inputModalities || ['TEXT']}
          onChange={(values) => updateTextModalities(values as string[])}
        />
      </div>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        {[
          ['IMAGE', '图片上限', 'maxInputImages'],
          ['VIDEO', '视频上限', 'maxInputVideos'],
          ['AUDIO', '音频上限', 'maxInputAudios'],
          ['DOCUMENT', '文档上限', 'maxInputDocuments']
        ].map(([modality, label, field]) => (
          <div key={field}>
            <GroupLabel text={label} keyName={field} />
            <InputNumber min={-1} precision={0} style={{ width: 120 }}
              disabled={!(cap.inputModalities || []).includes(modality)}
              value={(cap as any)[field] ?? null}
              placeholder="未公布填10"
              onChange={(value) => {
                updateCap({ [field]: value } as Partial<CapabilityModel>);
                if (field === 'maxInputImages') {
                  onFormChange({ supportsMultiImageInput: value === -1 || Number(value) > 1 });
                }
              }} />
          </div>
        ))}
      </Space>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        <div><GroupLabel text="上下文Token" keyName="contextWindowTokens" /><InputNumber min={1} precision={0} value={cap.contextWindowTokens ?? null} onChange={(v) => updateCap({ contextWindowTokens: v })} /></div>
        <div><GroupLabel text="最大输出Token" keyName="maxOutputTokens" /><InputNumber min={1} precision={0} value={cap.maxOutputTokens ?? null} onChange={(v) => updateCap({ maxOutputTokens: v })} /></div>
        <div><GroupLabel text="文档最大页数" keyName="maxInputDocumentPages" /><InputNumber min={1} precision={0} disabled={!(cap.inputModalities || []).includes('DOCUMENT')} value={cap.maxInputDocumentPages ?? null} onChange={(v) => updateCap({ maxInputDocumentPages: v })} /></div>
        <div><GroupLabel text="视频最长秒数" keyName="maxInputVideoDurationSeconds" /><InputNumber min={1} precision={0} disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.maxInputVideoDurationSeconds ?? null} onChange={(v) => updateCap({ maxInputVideoDurationSeconds: v })} /></div>
        <div><GroupLabel text="音频最长秒数" keyName="maxInputAudioDurationSeconds" /><InputNumber min={1} precision={0} disabled={!(cap.inputModalities || []).includes('AUDIO')} value={cap.maxInputAudioDurationSeconds ?? null} onChange={(v) => updateCap({ maxInputAudioDurationSeconds: v })} /></div>
      </Space>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        {[
          ['IMAGE', '单张图片 MB', 'maxInputImageFileSizeMb'],
          ['VIDEO', '单个视频 MB', 'maxInputVideoFileSizeMb'],
          ['AUDIO', '单个音频 MB', 'maxInputAudioFileSizeMb'],
          ['DOCUMENT', '单个文档 MB', 'maxInputDocumentFileSizeMb']
        ].map(([modality, label, field]) => (
          <div key={field}>
            <GroupLabel text={label} keyName={field} />
            <InputNumber min={1} precision={0} style={{ width: 150 }}
              disabled={!(cap.inputModalities || []).includes(modality)}
              value={(cap as any)[field] ?? null}
              onChange={(value) => updateCap({ [field]: value } as Partial<CapabilityModel>)} />
          </div>
        ))}
      </Space>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        {[
          ['IMAGE', '图片格式', 'inputImageFormats'],
          ['VIDEO', '视频格式', 'inputVideoFormats'],
          ['AUDIO', '音频格式', 'inputAudioFormats'],
          ['DOCUMENT', '文档格式', 'inputDocumentFormats']
        ].map(([modality, label, field]) => (
          <div key={field}><GroupLabel text={label} keyName={field} /><Select mode="tags" style={{ width: 210 }}
            disabled={!(cap.inputModalities || []).includes(modality)} value={(cap as any)[field] || []}
            onChange={(value) => updateCap({ [field]: value } as Partial<CapabilityModel>)} /></div>
        ))}
      </Space>
      <Space wrap align="start">
        <div><GroupLabel text="支持思考" keyName="supportsReasoning" /><Switch checked={cap.supportsReasoning === true} onChange={(v) => updateCap(v ? { supportsReasoning: true } : { supportsReasoning: false, supportsReasoningDisable: false, returnsReasoningContent: false, supportsReasoningBudget: false, defaultReasoningEnabled: false, reasoningApiStyle: undefined, outputTokenApiField: undefined, allowedReasoningLevels: [] })} /></div>
        <div><GroupLabel text="允许关闭" keyName="supportsReasoningDisable" /><Switch disabled={!cap.supportsReasoning} checked={cap.supportsReasoningDisable === true} onChange={(v) => updateCap({ supportsReasoningDisable: v })} /></div>
        <div><GroupLabel text="返回思考内容" keyName="returnsReasoningContent" /><Switch disabled={!cap.supportsReasoning} checked={cap.returnsReasoningContent === true} onChange={(v) => updateCap({ returnsReasoningContent: v })} /></div>
        <div><GroupLabel text="支持Token预算" keyName="supportsReasoningBudget" /><Switch disabled={!cap.supportsReasoning} checked={cap.supportsReasoningBudget === true} onChange={(v) => updateCap(v ? { supportsReasoningBudget: true } : { supportsReasoningBudget: false, defaultReasoningBudgetTokens: null, maxReasoningBudgetTokens: null })} /></div>
        <div><GroupLabel text="默认开启" keyName="defaultReasoningEnabled" /><Switch disabled={!cap.supportsReasoning} checked={cap.defaultReasoningEnabled === true} onChange={(v) => updateCap({ defaultReasoningEnabled: v })} /></div>
      </Space>
      <Space wrap align="end" style={{ marginTop: 14 }}>
        <div><GroupLabel text="协议映射" keyName="reasoningApiStyle" /><Select allowClear style={{ width: 160 }} value={cap.reasoningApiStyle}
          onChange={(v) => updateCap({ reasoningApiStyle: v })}
          options={['OPENAI', 'QWEN', 'DEEPSEEK', 'GEMINI', 'AGNES'].map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="输出上限字段" keyName="outputTokenApiField" /><Select allowClear style={{ width: 210 }} value={cap.outputTokenApiField}
          onChange={(v) => updateCap({ outputTokenApiField: v })}
          options={['max_tokens', 'max_completion_tokens', 'maxOutputTokens'].map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="允许档位" keyName="allowedReasoningLevels" /><Select mode="tags" style={{ width: 300 }} value={cap.allowedReasoningLevels || []}
          onChange={(v) => updateCap({ allowedReasoningLevels: v })}
          options={['minimal', 'low', 'medium', 'high', 'xhigh', 'max'].map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="默认档位" keyName="defaultReasoningLevel" /><Select allowClear style={{ width: 160 }} disabled={!cap.supportsReasoning}
          value={cap.defaultReasoningLevel} onChange={(v) => updateCap({ defaultReasoningLevel: v })}
          options={(cap.allowedReasoningLevels || []).map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="默认思考预算" keyName="defaultReasoningBudgetTokens" /><InputNumber min={1} precision={0} style={{ width: 170 }}
          disabled={!cap.supportsReasoning || !cap.supportsReasoningBudget} value={cap.defaultReasoningBudgetTokens ?? null}
          onChange={(v) => updateCap({ defaultReasoningBudgetTokens: v })} /></div>
        <div><GroupLabel text="最大思考预算" keyName="maxReasoningBudgetTokens" /><InputNumber min={1} precision={0} style={{ width: 170 }}
          disabled={!cap.supportsReasoning || !cap.supportsReasoningBudget} value={cap.maxReasoningBudgetTokens ?? null}
          onChange={(v) => updateCap({ maxReasoningBudgetTokens: v })} /></div>
      </Space>
      <Collapse ghost style={{ marginTop: 12 }} items={[{
        key: 'preview', label: '查看 capabilityJson（只读预览）',
        children: <pre className="readonly-preview">{JSON.stringify(buildCapabilityJsonObject(form, cap), null, 2)}</pre>
      }]} />
    </div>;
  }
  const updateScene = (scene: string, patch: any) => {
    onCapChange({ ...cap, sceneRules: { ...cap.sceneRules, [scene]: { ...(cap.sceneRules as any)[scene], ...patch } } });
  };

  const resetDefaults = () => {
    const fresh = makeEmptyCapabilityModel();
    if (modelType === 'image') {
      fresh.sizeOptions = [...presetSizes];
      fresh.aspectRatioOptions = [...presetAspects];
    } else if (modelType === 'video') {
      fresh.sizeOptions = [...(PRESET_SIZE.video || [])];
      fresh.aspectRatioOptions = [...(PRESET_ASPECT.video || [])];
      fresh.durationOptions = [...PRESET_DURATION];
    }
    onCapChange(fresh);
    message.success('已重置为推荐默认');
  };

  const addCustomSize = () => {
    const v = newSize.trim();
    if (!v) return;
    if (!cap.sizeOptions.includes(v)) updateCap({ sizeOptions: [...cap.sizeOptions, v] });
    setNewSize('');
  };
  const addCustomAspect = () => {
    const v = newAspect.trim();
    if (!v || !/^\d+\s*:\s*\d+$/.test(v)) { message.error('格式应为 宽:高'); return; }
    const norm = v.replace(/\s+/g, '');
    if (!cap.aspectRatioOptions.includes(norm)) updateCap({ aspectRatioOptions: [...cap.aspectRatioOptions, norm] });
    setNewAspect('');
  };
  const addCustomDuration = () => {
    if (!newDuration || newDuration <= 0) return;
    if (!cap.durationOptions.includes(newDuration)) {
      updateCap({ durationOptions: [...cap.durationOptions, newDuration].sort((a, b) => a - b) });
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <Button size="small" icon={<RedoOutlined />} onClick={resetDefaults}>重置为推荐默认</Button>
      </div>

      {/* 规格 */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="规格选项" keyName="sizeOptions" />
        <Checkbox.Group value={cap.sizeOptions} onChange={(v) => updateCap({ sizeOptions: v as string[] })}>
          {sizeChoices.map((o) => <Checkbox key={o} value={o}>{o}</Checkbox>)}
        </Checkbox.Group>
        <Space size={4} style={{ marginTop: 6 }}>
          <Input size="small" style={{ width: 120 }} placeholder="自定义规格" value={newSize} onChange={(e) => setNewSize(e.target.value)} onPressEnter={addCustomSize} />
          <Button size="small" icon={<PlusOutlined />} onClick={addCustomSize}>新增</Button>
        </Space>
      </div>

      {configurableAsyncVideo && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="上游分辨率映射" keyName="upstreamResolutionMap" />
          {cap.sizeOptions.length === 0 ? (
            <div className="help-text">请先配置规格选项，再填写对应的上游参数值。</div>
          ) : (
            <Space direction="vertical" size={6}>
              {cap.sizeOptions.map((source) => (
                <Space key={source} size={8} align="center">
                  <code className="code-text" style={{ minWidth: 70, display: 'inline-block' }}>{source}</code>
                  <span style={{ color: '#94a3b8' }}>→</span>
                  <Input
                    size="small"
                    style={{ width: 220 }}
                    value={resolutionMappingValue(source)}
                    placeholder="留空使用系统默认换算"
                    onChange={(event) => updateResolutionMapping(source, event.target.value)}
                  />
                </Space>
              ))}
            </Space>
          )}
          <div className="help-text" style={{ marginTop: 6, maxWidth: 620 }}>
            按上游接口实际枚举逐项填写，例如业务规格 <code>2K</code> 可映射为 <code>2k</code> 或 <code>1440p</code>。
            保存后自动写入能力配置，不需要手工编辑 JSON；留空继续使用协议默认换算。
          </div>
        </div>
      )}

      {/* 比例 */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="比例选项" keyName="aspectRatioOptions" />
        <Checkbox.Group value={cap.aspectRatioOptions} onChange={(v) => updateCap({ aspectRatioOptions: v as string[] })}>
          {aspectChoices.map((o) => <Checkbox key={o} value={o}>{o}</Checkbox>)}
        </Checkbox.Group>
        <Space size={4} style={{ marginTop: 6 }}>
          <Input size="small" style={{ width: 120 }} placeholder="如 5:4" value={newAspect} onChange={(e) => setNewAspect(e.target.value)} onPressEnter={addCustomAspect} />
          <Button size="small" icon={<PlusOutlined />} onClick={addCustomAspect}>新增</Button>
        </Space>
      </div>

      {/* 时长（仅 video） */}
      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="时长选项" keyName="durationOptions" />
          <Checkbox.Group value={cap.durationOptions} onChange={(v) => updateCap({ durationOptions: (v as number[]).sort((a, b) => a - b) })}>
            {durationChoices.map((o) => <Checkbox key={o} value={o}>{o} 秒</Checkbox>)}
          </Checkbox.Group>
          <Space size={4} style={{ marginTop: 6 }}>
            <InputNumber size="small" style={{ width: 80 }} min={1} max={600} value={newDuration} onChange={(v) => setNewDuration(v || 5)} />
            <Button size="small" icon={<PlusOutlined />} onClick={addCustomDuration}>新增</Button>
          </Space>
        </div>
      )}

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="参考音频输入" keyName="supportsReferenceAudio" />
          <Space size={12} align="center" wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              支持传入参考音频
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                checked={cap.supportsReferenceAudio === true}
                onChange={(v) => updateCap(v ? {
                  supportsReferenceAudio: true,
                  referenceAudioRequiresGeneratedAudio: cap.referenceAudioRequiresGeneratedAudio !== false,
                  maxReferenceAudios: cap.maxReferenceAudios ?? 3,
                  referenceAudioMinDurationSeconds: cap.referenceAudioMinDurationSeconds ?? 2,
                  referenceAudioMaxDurationSeconds: cap.referenceAudioMaxDurationSeconds ?? 15,
                  referenceAudioMaxTotalDurationSeconds: cap.referenceAudioMaxTotalDurationSeconds ?? 15,
                  referenceAudioFormats: cap.referenceAudioFormats.length ? cap.referenceAudioFormats : ['wav', 'mp3']
                } : { supportsReferenceAudio: false })}
              />
            </span>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              依赖音画同出
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                disabled={!cap.supportsReferenceAudio}
                checked={cap.referenceAudioRequiresGeneratedAudio !== false}
                onChange={(v) => updateCap({ referenceAudioRequiresGeneratedAudio: v })}
              />
            </span>
          </Space>
          <div style={{ marginTop: 6, color: '#94a3b8', fontSize: 12 }}>
            仅当上游要求参考音频与音画同出开关同时开启时勾选。
          </div>
          <div style={{ marginTop: 8 }}>
            <Space size={8} wrap>
              <span>最多数量</span>
              <InputNumber size="small" min={-1} max={64} precision={0} disabled={!cap.supportsReferenceAudio}
                value={cap.maxReferenceAudios ?? undefined}
                onChange={(v) => updateCap({ maxReferenceAudios: v == null ? null : Math.trunc(Number(v)) })} />
              <span>单段最短</span>
              <InputNumber size="small" min={0} max={600} precision={0} addonAfter="秒" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMinDurationSeconds ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMinDurationSeconds: v == null ? null : Math.trunc(Number(v)) })} />
              <span>单段最长</span>
              <InputNumber size="small" min={0} max={600} precision={0} addonAfter="秒" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMaxDurationSeconds ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMaxDurationSeconds: v == null ? null : Math.trunc(Number(v)) })} />
              <span>总时长上限</span>
              <InputNumber size="small" min={0} max={1800} precision={0} addonAfter="秒" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMaxTotalDurationSeconds ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMaxTotalDurationSeconds: v == null ? null : Math.trunc(Number(v)) })} />
            </Space>
          </div>
          <div style={{ marginTop: 8 }}>
            <span style={{ marginRight: 8 }}>支持格式</span>
            <Checkbox.Group
              disabled={!cap.supportsReferenceAudio}
              options={[
                { label: '不限格式（*）', value: '*' },
                { label: 'WAV', value: 'wav' },
                { label: 'MP3', value: 'mp3' }
              ]}
              value={cap.referenceAudioFormats}
              onChange={(v) => {
                const values = v as string[];
                const hadWildcard = cap.referenceAudioFormats.includes('*');
                const next = values.includes('*')
                  ? (hadWildcard ? values.filter((item) => item !== '*') : ['*'])
                  : values;
                updateCap({ referenceAudioFormats: next });
              }}
            />
          </div>
          <div className="help-text" style={{ marginTop: 4, maxWidth: 660 }}>
            {cap.referenceAudioRequiresGeneratedAudio !== false
              ? '当前配置要求提交参考音频时同时开启生成声音。'
              : '当前配置允许独立提交参考音频，不要求开启生成声音。'}
            数量 -1 表示厂商未公布上限；时长 0 表示对应限制未公布；不限格式（*）只能单独选择。已知限制必须按官方文档填写。
          </div>
        </div>
      )}

      {/* 单次最多参考图张数 maxReferenceImages（image / video 通用，四态语义） */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="单次最多参考图张数" keyName="maxReferenceImages" />
        <Space size={8} align="center">
          <InputNumber
            size="small"
            style={{ width: 120 }}
            min={-1}
            max={64}
            precision={0}
            placeholder="留空=默认"
            value={cap.maxReferenceImages ?? undefined}
            onChange={(v) => {
              if (v == null) {
                updateCap({ maxReferenceImages: null });
                return;
              }
              const n = Math.trunc(Number(v));
              updateCap({ maxReferenceImages: Number.isFinite(n) && n >= -1 ? n : null });
            }}
          />
          <span className="help-text" style={{ maxWidth: 520 }}>
            四态：<b>留空</b>=未配置，运行时回退厂商默认；<b>-1</b>=无限；<b>0</b>=禁止参考图（该模型暂不支持图生图，将来开放改为 N 即可）；<b>N</b>=上限 N 张。
            各厂商官方上限参考：即梦4.0=10 / 4.6=4 / ultra=1 / Vidu=7 / Agnes图=1 / Agnes视频=2。超量时系统保留前 N 张并记 warn 日志，不报错。
          </span>
        </Space>
      </div>

      {/* 最少参考图张数 minReferenceImages（image / video 通用，缺图前置拦截） */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="最少参考图张数" keyName="minReferenceImages" />
        <Space size={8} align="center">
          <InputNumber
            size="small"
            style={{ width: 120 }}
            min={0}
            max={64}
            precision={0}
            placeholder="留空=不要求"
            value={cap.minReferenceImages ?? undefined}
            onChange={(v) => {
              if (v == null) {
                updateCap({ minReferenceImages: null });
                return;
              }
              const n = Math.trunc(Number(v));
              updateCap({ minReferenceImages: Number.isFinite(n) && n >= 0 ? n : null });
            }}
          />
          <span className="help-text" style={{ maxWidth: 520 }}>
            <b>留空 / 0</b>=不要求带图（纯文生可用）；<b>N≥1</b>=必须至少带 N 张输入图，缺图请求在建任务/扣费前被拦截（提示「至少传N张图」），避免到上游才失败空转一轮冻结-退款。
            配置参考：图生图 / 图生视频 / 参考图生视频=1；首尾帧=2（首帧+尾帧）；多帧=2（首帧+至少1个关键帧）。
          </span>
        </Space>
      </div>

      {/* 音画同出：仅 video；运营按官方文档勾选后，C 端才展示「生成声音」开关 */}
      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="音画同出" keyName="supportsAudio" />
          <Space size={12} align="center" wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              支持用户选择生成声音
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                checked={cap.supportsAudio === true}
                onChange={(v) => updateCap({ supportsAudio: v })}
              />
            </span>
            {configurableAsyncVideo && (
              <>
                <span style={{ fontSize: 12, color: '#64748b' }}>上游音频开关</span>
                <Select
                  size="small"
                  style={{ width: 220 }}
                  disabled={cap.supportsAudio !== true}
                  value={cap.upstreamAudioField ?? 'generate_audio'}
                  onChange={(v: CapabilityModel['upstreamAudioField']) => updateCap({ upstreamAudioField: v })}
                  options={[
                    { value: 'generate_audio', label: 'generate_audio' },
                    { value: 'audio', label: 'audio' },
                    { value: 'none', label: '不下发（上游隐式处理）' }
                  ]}
                />
              </>
            )}
          </Space>
          <div className="help-text" style={{ marginTop: 4, maxWidth: 560 }}>
            按官方文档配置：Seedance 2.0 / Fast / Mini（<code>generate_audio</code>）、Vidu Q3 系列（<code>audio</code>）等可开。
            开启后写入 <code>capability.supportsAudio=true</code>，表示允许用户选择「生成声音」，并不决定默认是否开启。
            当前默认值为<strong>{defaultAudioEnabled ? '开启' : '关闭'}</strong>
            （<code>defaultAudio={String(cap.preservedCapability?.defaultAudio ?? '未配置，兼容默认开启')}</code>）；
            C 端接口会据此返回 <code>capability.defaultGenerateAudio</code>。关闭或不支持时禁止选择，后端也会拒绝 <code>generateAudio=true</code>。
            {configurableAsyncVideo && <>
              只接受参考音频并自动完成音画同步的渠道模型选择“<strong>不下发</strong>”，页面仍展示音画同步能力，但请求不会携带 <code>audio</code> 或 <code>generate_audio</code>。
            </>}
            <br />
            固定无声、或文档未声明音频能力的模型请保持关闭，不要猜测开启。
          </div>
        </div>
      )}

      {/* Base64 传图：凡涉及图片传入的模型（图生图 / 图生视频 / 首尾帧 / 参考生视频）均可配置 */}
      {(modelType === 'image' || modelType === 'video') && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="Base64 传图" />
          <Space size={12} align="center" wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              官方支持 Base64 传图
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                checked={cap.supportsBase64Image === true}
                onChange={(v) => updateCap({ supportsBase64Image: v, base64ImageEnabled: v ? cap.base64ImageEnabled : false })}
              />
            </span>
            <Tooltip title={cap.supportsBase64Image ? '' : '该接口只允许 URL 传图（先按官方文档确认支持后，打开左侧「官方支持」再启用）'}>
              <span style={{ fontSize: 12, color: cap.supportsBase64Image ? '#64748b' : '#cbd5e1' }}>
                启用 Base64 传图
                <Switch
                  size="small"
                  style={{ marginLeft: 6 }}
                  disabled={cap.supportsBase64Image !== true}
                  checked={cap.base64ImageEnabled === true}
                  onChange={(v) => updateCap({ base64ImageEnabled: v })}
                />
              </span>
            </Tooltip>
          </Space>
          <div className="help-text" style={{ marginTop: 4, maxWidth: 560 }}>
            两个开关都关 = 该模型走 URL 传图（默认）。<b>官方支持</b>按模型文档勾选（这是接口事实，不是随意开）：支持时右侧「启用」才可点；不支持时「启用」灰置并提示「只允许 URL 传图」。
            <br />
            打开<b>启用</b>后，系统把参考图下载转 Base64 内联下发，用于上游网关无法回源业务 CDN（如 gpt-image-2 拉不到内网图 404）的场景。当前仅 gpt-image-2、Agnes 图片系已接入 Base64 内联。
          </div>
        </div>
      )}

      {/* 场景规则 */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="场景规则" keyName="sceneRules" />
        {modelType === 'image' && (
          <>
            <div style={{ marginBottom: 4 }}><span style={{ fontSize: 12, color: '#64748b' }}>文生图 textToImage：</span>
              <Checkbox checked={cap.sceneRules.textToImage.supportsAspectRatio} onChange={(e) => updateScene('textToImage', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.textToImage.supportsSizePreset} onChange={(e) => updateScene('textToImage', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
            </div>
            <div><span style={{ fontSize: 12, color: '#64748b' }}>图生图 imageToImage：</span>
              <Checkbox checked={cap.sceneRules.imageToImage.supportsAspectRatio} onChange={(e) => updateScene('imageToImage', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToImage.supportsSizePreset} onChange={(e) => updateScene('imageToImage', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToImage.aspectRatioFollowInput} onChange={(e) => updateScene('imageToImage', { aspectRatioFollowInput: e.target.checked })}>比例跟随输入</Checkbox>
            </div>
          </>
        )}
        {modelType === 'video' && (
          <>
            <div style={{ marginBottom: 4 }}><span style={{ fontSize: 12, color: '#64748b' }}>文生视频 textToVideo：</span>
              <Checkbox checked={cap.sceneRules.textToVideo.supportsAspectRatio} onChange={(e) => updateScene('textToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.textToVideo.supportsSizePreset} onChange={(e) => updateScene('textToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.textToVideo.supportsDuration} onChange={(e) => updateScene('textToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
            </div>
            <div><span style={{ fontSize: 12, color: '#64748b' }}>图生视频 imageToVideo：</span>
              <Checkbox checked={cap.sceneRules.imageToVideo.supportsAspectRatio} onChange={(e) => updateScene('imageToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToVideo.supportsSizePreset} onChange={(e) => updateScene('imageToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToVideo.supportsDuration} onChange={(e) => updateScene('imageToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToVideo.aspectRatioFollowInput} onChange={(e) => updateScene('imageToVideo', { aspectRatioFollowInput: e.target.checked })}>比例跟随输入</Checkbox>
            </div>
          </>
        )}
      </div>

      {/* JSON 预览：只读，默认折叠，避免干扰可视化配置 */}
      <Collapse
        ghost
        items={[{
          key: 'preview',
          label: <span style={{ fontSize: 12, color: '#94a3b8' }}>查看生成的 capabilityJson（只读预览）</span>,
          children: (
            <pre className="readonly-preview">
              {JSON.stringify(buildCapabilityJsonObject(form, cap), null, 2)}
            </pre>
          )
        }]}
      />
    </div>
  );
}
