import React, { useEffect, useMemo, useState } from 'react';
import { Card, Col, Row, Select, Tag, Tooltip } from 'antd';
import { InfoCircleOutlined, ThunderboltOutlined, RocketOutlined } from '@ant-design/icons';

import { listAgentByBizCategoryAdmin } from '@/api/aid/agent';
import { listModelByFunc } from '@/api/aid/aimanage';

interface AgentLite {
  agentCode: string;
  name?: string;
  bizCategoryCode?: string;
  /** AidAgent.status：1=启用 0=停用 */
  status?: number | string;
}

/** 模型能力（capability_json 反序列化） */
interface CapabilityLite {
  sizeOptions?: string[];
  defaultSize?: string;
  aspectRatioOptions?: string[];
  defaultAspectRatio?: string;
}

interface ModelLite {
  modelCode: string;
  modelName?: string;
  modelType?: string; // text / image / video
  generateMode?: string;
  providerName?: string;
  status?: number | string;
  capability?: CapabilityLite;
  /** 顶层兜底默认值（与 capability.defaultSize / defaultAspectRatio 同义） */
  defaultSizeCode?: string;
  defaultAspectRatio?: string;
}

interface ModeBlock {
  agentCode?: string;
  modelCode?: string;
  resolution?: string;
  aspectRatio?: string;
}

interface ConfigValue {
  economy?: ModeBlock;
  performance?: ModeBlock;
}

interface Props {
  /** sceneCode（= configName，= bizCategoryCode = funcCode） */
  name: string;
  /** 后端存的 JSON 字符串：{"economy":{...},"performance":{...}} */
  value: string;
  onChange: (v: string) => void;
}

/** 进程内缓存：agents 按 sceneCode，models 按 funcCode（= 智能体绑定的模型池） */
const AGENT_CACHE = new Map<string, AgentLite[]>();
const MODEL_CACHE = new Map<string, ModelLite[]>();

/**
 * 15 个场景 → 模型大类（决定智能体/模型类型语义）。
 * 与后端 ProjectGenConfigScene 枚举一一对应。
 */
const SCENE_MODEL_TYPE: Record<string, 'text' | 'image'> = {
  // 文字类（6）
  main_character_extract: 'text',
  main_scene_extract: 'text',
  main_prop_extract: 'text',
  main_character_form: 'text',
  main_scene_form: 'text',
  main_prop_form: 'text',
  // 图片类（4）
  main_character_image: 'image',
  main_scene_image: 'image',
  main_prop_image: 'image',
  main_character_card_image: 'image',
  // 分镜 LLM（4）
  main_storyboard_script: 'text',
  main_storyboard_stylist: 'text',
  main_storyboard_video_prompt: 'text',
  main_storyboard_video_prompt_image: 'text',
  // 分镜生图（1）
  main_storyboard_image: 'image'
};

/** 场景中文名（15 项） */
export const SCENE_NAMES: Record<string, string> = {
  main_character_extract: '角色提取',
  main_scene_extract: '场景提取',
  main_prop_extract: '道具提取',
  main_character_form: '角色形态提取',
  main_scene_form: '场景形态提取',
  main_prop_form: '道具形态提取',
  main_character_image: '生成角色图',
  main_scene_image: '生成场景图',
  main_prop_image: '生成道具图',
  main_character_card_image: '角色设定卡',
  main_storyboard_script: '分镜脚本提取',
  main_storyboard_stylist: '分镜图提示词',
  main_storyboard_video_prompt: '视频提示词-多参数版',
  main_storyboard_video_prompt_image: '视频提示词-图生视频',
  main_storyboard_image: '分镜生图'
};

/**
 * 图片场景：需要清晰度 + 比例（与后端枚举 needResolution / needAspectRatio 对齐，
 * 4 个图片类 + 分镜生图 = 5 个，全部 image 场景都要清晰度和比例）。
 */
function isImageScene(sceneCode: string): boolean {
  return SCENE_MODEL_TYPE[sceneCode] === 'image';
}

function parseValue(v: string): ConfigValue {
  if (!v || !String(v).trim()) return {};
  try {
    const obj = JSON.parse(v);
    if (obj && typeof obj === 'object') {
      return {
        economy: obj.economy && typeof obj.economy === 'object' ? obj.economy : undefined,
        performance:
          obj.performance && typeof obj.performance === 'object' ? obj.performance : undefined
      };
    }
  } catch {
    /* ignore */
  }
  return {};
}

function cleanBlock(b: ModeBlock | undefined): ModeBlock | undefined {
  if (!b) return undefined;
  const out: ModeBlock = {};
  if (b.agentCode) out.agentCode = b.agentCode;
  if (b.modelCode) out.modelCode = b.modelCode;
  if (b.resolution) out.resolution = b.resolution;
  if (b.aspectRatio) out.aspectRatio = b.aspectRatio;
  return Object.keys(out).length ? out : undefined;
}

function serialize(v: ConfigValue): string {
  const out: any = {};
  const eco = cleanBlock(v.economy);
  const per = cleanBlock(v.performance);
  if (eco) out.economy = eco;
  if (per) out.performance = per;
  return Object.keys(out).length ? JSON.stringify(out) : '';
}

/** 从后端响应里安全抽出数组（兼容 data / rows / 直接数组 三种返回形态） */
function extractArray<T = any>(res: any): T[] {
  if (!res) return [];
  if (Array.isArray(res)) return res;
  if (Array.isArray(res.data)) return res.data;
  if (Array.isArray(res.rows)) return res.rows;
  return [];
}

/** 仅保留启用 agent（status === 1） */
function isEnabledAgent(a: AgentLite): boolean {
  return Number(a?.status ?? 0) === 1;
}

/** 异步加载该 sceneCode 下的智能体列表（带缓存） */
async function loadAgents(sceneCode: string): Promise<AgentLite[]> {
  if (AGENT_CACHE.has(sceneCode)) return AGENT_CACHE.get(sceneCode)!;
  try {
    const res: any = await listAgentByBizCategoryAdmin(sceneCode);
    const arr = extractArray<AgentLite>(res).filter(isEnabledAgent);
    AGENT_CACHE.set(sceneCode, arr);
    return arr;
  } catch {
    AGENT_CACHE.set(sceneCode, []);
    return [];
  }
}

/** 异步加载某 funcCode 下的可选模型池（带 capability，已由后端过滤停用/无效，带缓存） */
async function loadModelsByFunc(funcCode: string): Promise<ModelLite[]> {
  if (!funcCode) return [];
  if (MODEL_CACHE.has(funcCode)) return MODEL_CACHE.get(funcCode)!;
  try {
    const res: any = await listModelByFunc(funcCode);
    const arr = extractArray<ModelLite>(res);
    MODEL_CACHE.set(funcCode, arr);
    return arr;
  } catch {
    MODEL_CACHE.set(funcCode, []);
    return [];
  }
}

/** 取某模型的清晰度候选 */
function sizeOptionsOf(m?: ModelLite): string[] {
  return m?.capability?.sizeOptions || [];
}
/** 取某模型的比例候选 */
function aspectOptionsOf(m?: ModelLite): string[] {
  return m?.capability?.aspectRatioOptions || [];
}
/** 取某模型默认清晰度 */
function defaultSizeOf(m?: ModelLite): string | undefined {
  return m?.capability?.defaultSize || m?.defaultSizeCode || undefined;
}
/** 取某模型默认比例 */
function defaultAspectOf(m?: ModelLite): string | undefined {
  return m?.capability?.defaultAspectRatio || m?.defaultAspectRatio || undefined;
}

/** 单个模式块编辑器 —— 模型池随选中智能体联动，清晰度/比例随选中模型 capability 联动 */
function ModeBlockEditor({
  title,
  icon,
  accent,
  sceneCode,
  block,
  agentOptions,
  onChange
}: {
  title: string;
  icon: React.ReactNode;
  accent: string;
  sceneCode: string;
  block: ModeBlock;
  agentOptions: AgentLite[];
  onChange: (b: ModeBlock) => void;
}) {
  const imageScene = isImageScene(sceneCode);

  /** 当前选中智能体绑定的模型池 funcCode（= agent.bizCategoryCode；未选回退 sceneCode） */
  const boundFuncCode = useMemo(() => {
    if (!block.agentCode) return sceneCode;
    const a = agentOptions.find((x) => x.agentCode === block.agentCode);
    return a?.bizCategoryCode || sceneCode;
  }, [block.agentCode, agentOptions, sceneCode]);

  const [modelOptions, setModelOptions] = useState<ModelLite[]>(
    () => MODEL_CACHE.get(boundFuncCode) || []
  );
  const [loadingModels, setLoadingModels] = useState(false);

  /** 智能体变化时按 bizCategoryCode 联动重拉模型池 */
  useEffect(() => {
    let cancelled = false;
    setLoadingModels(true);
    loadModelsByFunc(boundFuncCode).then((arr) => {
      if (!cancelled) {
        setModelOptions(arr);
        setLoadingModels(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [boundFuncCode]);

  /** 当前选中的模型对象（用于联动清晰度/比例选项） */
  const currentModel = useMemo(
    () => modelOptions.find((m) => m.modelCode === block.modelCode),
    [modelOptions, block.modelCode]
  );

  const sizeOpts = sizeOptionsOf(currentModel);
  const aspectOpts = aspectOptionsOf(currentModel);

  /** 切模型后，若旧 modelCode 不在新池里则清空，避免提交失效模型 */
  useEffect(() => {
    if (!block.modelCode || !modelOptions.length) return;
    const stillValid = modelOptions.some((m) => m.modelCode === block.modelCode);
    if (!stillValid) {
      onChange({ ...block, modelCode: undefined, resolution: undefined, aspectRatio: undefined });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelOptions, block.modelCode]);

  /** 选中模型变化时：清晰度/比例若不在新模型能力内则回退默认（或清空），避免后端打回 */
  useEffect(() => {
    if (!imageScene || !currentModel) return;
    const patch: Partial<ModeBlock> = {};
    if (block.resolution && sizeOpts.length && !sizeOpts.includes(block.resolution)) {
      patch.resolution = defaultSizeOf(currentModel) || undefined;
    }
    if (block.aspectRatio && aspectOpts.length && !aspectOpts.includes(block.aspectRatio)) {
      patch.aspectRatio = defaultAspectOf(currentModel) || undefined;
    }
    if (Object.keys(patch).length) {
      onChange({ ...block, ...patch });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentModel]);

  /** 选模型时，若该模式块还没设清晰度/比例，自动填模型默认值，减少手动操作 */
  const handleModelChange = (v: string | undefined) => {
    const next: ModeBlock = { ...block, modelCode: v || undefined };
    if (v && imageScene) {
      const m = modelOptions.find((x) => x.modelCode === v);
      if (m) {
        if (!next.resolution) next.resolution = defaultSizeOf(m);
        if (!next.aspectRatio) next.aspectRatio = defaultAspectOf(m);
      }
    }
    onChange(next);
  };

  return (
    <Card
      size="small"
      title={
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span style={{ color: accent, fontSize: 14 }}>{icon}</span>
          <span style={{ fontWeight: 600 }}>{title}</span>
        </span>
      }
      bordered
      style={{ borderColor: accent + '33' }}
      headStyle={{ background: accent + '0d', borderBottom: `1px solid ${accent}22` }}
    >
      <Row gutter={[12, 10]}>
        <Col span={24}>
          <div className="help-text" style={{ marginBottom: 4 }}>智能体</div>
          <Select
            showSearch
            allowClear
            value={block.agentCode || undefined}
            onChange={(v) => onChange({ ...block, agentCode: v || undefined })}
            placeholder="请选择智能体"
            style={{ width: '100%' }}
            optionFilterProp="label"
            options={agentOptions.map((a) => ({
              value: a.agentCode,
              label: `${a.name || a.agentCode}（${a.agentCode}）`
            }))}
            notFoundContent={<span className="help-text">该场景下暂无可选智能体</span>}
          />
        </Col>
        <Col span={24}>
          <div className="help-text" style={{ marginBottom: 4 }}>
            模型
            <span style={{ marginLeft: 6 }}>（按所选智能体绑定的模型池联动）</span>
            {loadingModels && (
              <Tag color="default" bordered={false} style={{ marginLeft: 6, fontSize: 11 }}>
                加载中…
              </Tag>
            )}
          </div>
          <Select
            showSearch
            allowClear
            value={block.modelCode || undefined}
            onChange={handleModelChange}
            placeholder={block.agentCode ? '请选择模型' : '请先选择智能体'}
            style={{ width: '100%' }}
            optionFilterProp="label"
            options={modelOptions.map((m) => ({
              value: m.modelCode,
              label: `${m.modelName || m.modelCode}（${m.modelCode}）`
            }))}
            notFoundContent={
              <span className="help-text">
                {block.agentCode ? '该智能体绑定的模型池为空' : '请先选择智能体'}
              </span>
            }
          />
        </Col>
        {imageScene && (
          <Col span={12}>
            <div className="help-text" style={{ marginBottom: 4 }}>清晰度</div>
            <Select
              allowClear
              value={block.resolution || undefined}
              onChange={(v) => onChange({ ...block, resolution: v || undefined })}
              placeholder={currentModel ? '请选择清晰度' : '请先选择模型'}
              disabled={!currentModel}
              style={{ width: '100%' }}
              options={sizeOpts.map((s) => ({ label: s, value: s }))}
              notFoundContent={<span className="help-text">该模型无可选清晰度</span>}
            />
          </Col>
        )}
        {imageScene && (
          <Col span={12}>
            <div className="help-text" style={{ marginBottom: 4 }}>画面比例</div>
            <Select
              allowClear
              value={block.aspectRatio || undefined}
              onChange={(v) => onChange({ ...block, aspectRatio: v || undefined })}
              placeholder={currentModel ? '请选择比例' : '请先选择模型'}
              disabled={!currentModel}
              style={{ width: '100%' }}
              options={aspectOpts.map((s) => ({ label: s, value: s }))}
              notFoundContent={<span className="help-text">该模型无可选比例</span>}
            />
          </Col>
        )}
      </Row>
    </Card>
  );
}

export default function ProjectGenConfigField({ name, value, onChange }: Props) {
  const [state, setState] = useState<ConfigValue>(() => parseValue(value));
  const [agentOptions, setAgentOptions] = useState<AgentLite[]>(() => AGENT_CACHE.get(name) || []);
  const [loadingAgents, setLoadingAgents] = useState(false);

  useEffect(() => {
    setState(parseValue(value));
  }, [value]);

  const sceneType = SCENE_MODEL_TYPE[name];

  /** 按 sceneCode 拉取该场景下的智能体（仅一次，命中缓存即用） */
  useEffect(() => {
    let cancelled = false;
    setLoadingAgents(true);
    loadAgents(name).then((arr) => {
      if (!cancelled) {
        setAgentOptions(arr);
        setLoadingAgents(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [name]);

  const commit = (next: ConfigValue) => {
    setState(next);
    onChange(serialize(next));
  };

  const updateMode = (mode: 'economy' | 'performance', block: ModeBlock) => {
    commit({ ...state, [mode]: block });
  };

  const copyToPerformance = () => {
    if (!state.economy) return;
    commit({ ...state, performance: { ...state.economy } });
  };

  return (
    <div style={{ width: '100%' }}>
      <div
        className="help-text"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 8
        }}
      >
        <Tag color="geekblue" bordered={false}>
          {SCENE_NAMES[name] || name}
        </Tag>
        <Tag color={sceneType === 'image' ? 'magenta' : 'cyan'} bordered={false}>
          {sceneType === 'image' ? '图片场景' : '文字场景'}
        </Tag>
        {loadingAgents && (
          <Tag color="default" bordered={false} style={{ fontSize: 11 }}>
            智能体加载中…
          </Tag>
        )}
        <Tooltip title="经济模式偏成本与速度，性能模式偏质量。项目按其 default_gen_mode 决定生效哪一套。模型下拉按所选智能体绑定的模型池联动；清晰度/比例按所选模型能力联动。">
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <InfoCircleOutlined />
            双模式默认
          </span>
        </Tooltip>
        <a
          style={{ marginLeft: 'auto', fontSize: 12 }}
          onClick={(e) => {
            e.preventDefault();
            copyToPerformance();
          }}
        >
          经济 → 性能
        </a>
      </div>

      <Row gutter={12}>
        <Col xs={24} md={12}>
          <ModeBlockEditor
            title="经济模式"
            icon={<ThunderboltOutlined />}
            accent="#10b981"
            sceneCode={name}
            block={state.economy || {}}
            agentOptions={agentOptions}
            onChange={(b) => updateMode('economy', b)}
          />
        </Col>
        <Col xs={24} md={12}>
          <ModeBlockEditor
            title="性能模式"
            icon={<RocketOutlined />}
            accent="#6366f1"
            sceneCode={name}
            block={state.performance || {}}
            agentOptions={agentOptions}
            onChange={(b) => updateMode('performance', b)}
          />
        </Col>
      </Row>
    </div>
  );
}
