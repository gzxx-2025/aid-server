import React, { useEffect, useMemo, useState } from 'react';
import { InputNumber, Select, Space, Tag } from 'antd';

import {
  AGENT_MODEL_FIELD_REQUIRE_I2X,
  AGENT_MODEL_FIELD_TYPE
} from './maps';

interface Props {
  name: string;
  value: string;
  onChange: (v: string) => void;
  models: any[];
}

interface AgentModelValue {
  modelCode?: string;
  defaultParams?: {
    outputCount?: number;
    size?: string;
    [k: string]: any;
  };
}

function parseValue(v: string): AgentModelValue {
  if (!v) return {};
  try {
    const o = JSON.parse(v);
    return o && typeof o === 'object' ? o : {};
  } catch {
    return {};
  }
}

function serialize(v: AgentModelValue): string {
  const clean: AgentModelValue = { modelCode: v.modelCode };
  if (v.defaultParams) {
    const p: Record<string, any> = {};
    if (v.defaultParams.size) p.size = v.defaultParams.size;
    if (v.defaultParams.outputCount != null) p.outputCount = v.defaultParams.outputCount;
    if (Object.keys(p).length) clean.defaultParams = p;
  }
  return JSON.stringify(clean);
}

const SIZE_PRESETS = ['1K', '2K', '4K', '1024x1024', '1024x1792', '1792x1024', '720P', '1080P'];

export default function AgentModelField({ name, value, onChange, models }: Props) {
  const modelType = AGENT_MODEL_FIELD_TYPE[name];
  const requireI2X = AGENT_MODEL_FIELD_REQUIRE_I2X[name];

  const [state, setState] = useState<AgentModelValue>(() => parseValue(value));

  useEffect(() => {
    setState(parseValue(value));
  }, [value]);

  /** 按字段语义过滤模型 */
  const options = useMemo(() => {
    let list = (models || []).filter((m: any) => !modelType || m.modelType === modelType);
    if (requireI2X && modelType === 'image') {
      // 图生图：generateMode = image_to_image
      list = list.filter((m: any) => (m.generateMode || '') === 'image_to_image');
    }
    if (requireI2X && modelType === 'video') {
      // 图生视频：generateMode = image_to_video
      list = list.filter((m: any) => (m.generateMode || '') === 'image_to_video');
    }
    // 去重 + 启用
    const seen = new Set<string>();
    const out: any[] = [];
    list.forEach((m: any) => {
      const code = m.modelCode || m.code;
      if (!code || seen.has(code)) return;
      seen.add(code);
      if (m.status === '1') return;
      out.push(m);
    });
    return out;
  }, [models, modelType, requireI2X]);

  const currentHit = options.find((m: any) => (m.modelCode || m.code) === state.modelCode);

  const commit = (next: AgentModelValue) => {
    setState(next);
    if (!next.modelCode) {
      onChange('');
    } else {
      onChange(serialize(next));
    }
  };

  const showParams = modelType === 'image' || modelType === 'video';

  return (
    <div style={{ width: '100%' }}>
      <Select
        showSearch
        allowClear
        value={state.modelCode || undefined}
        placeholder={`请选择 ${modelType || ''} 模型`}
        style={{ width: '100%' }}
        optionFilterProp="label"
        options={options.map((m: any) => ({
          label: (
            <span>
              <span style={{ fontWeight: 500 }}>{m.modelName || m.modelCode}</span>
              <span style={{ color: '#94a3b8', marginLeft: 8, fontSize: 12 }}>
                {m.modelCode}
              </span>
              {m.providerName && (
                <Tag
                  bordered={false}
                  color="blue"
                  style={{ marginLeft: 6, fontSize: 11, lineHeight: '16px' }}
                >
                  {m.providerName}
                </Tag>
              )}
            </span>
          ),
          value: m.modelCode || m.code
        }))}
        onChange={(v) => commit({ ...state, modelCode: v })}
        notFoundContent={<span className="help-text">暂无可选模型</span>}
      />

      {currentHit && (
        <div style={{ marginTop: 6, fontSize: 12, color: '#64748b', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Tag color="default" bordered={false}>{modelType}</Tag>
          {currentHit.generateMode && <Tag color="purple" bordered={false}>{currentHit.generateMode}</Tag>}
          {currentHit.providerName && <Tag color="blue" bordered={false}>{currentHit.providerName}</Tag>}
          {currentHit.billingMode && <Tag color="gold" bordered={false}>计费 {currentHit.billingMode}</Tag>}
        </div>
      )}

      {showParams && state.modelCode && (
        <Space size={8} style={{ marginTop: 8, width: '100%' }} wrap>
          <Select
            allowClear
            placeholder="分辨率"
            size="small"
            value={state.defaultParams?.size}
            style={{ width: 140 }}
            options={SIZE_PRESETS.map((s) => ({ label: s, value: s }))}
            onChange={(v) => commit({ ...state, defaultParams: { ...state.defaultParams, size: v } })}
          />
          <InputNumber
            size="small"
            placeholder="输出数"
            min={1}
            max={10}
            value={state.defaultParams?.outputCount ?? null}
            onChange={(v) =>
              commit({
                ...state,
                defaultParams: { ...state.defaultParams, outputCount: v == null ? undefined : Number(v) }
              })
            }
            style={{ width: 120 }}
          />
        </Space>
      )}
    </div>
  );
}

