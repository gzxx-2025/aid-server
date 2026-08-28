import React, { useMemo, useState } from 'react';
import { InputNumber, Tooltip } from 'antd';
import {
  BulbOutlined,
  InfoCircleOutlined,
  QuestionCircleOutlined,
  WarningOutlined
} from '@ant-design/icons';

interface Props {
  value: string;
  onChange: (v: string) => void;
}

/** 镜头数下限锚点配置结构（与后端 storyboard / shot_density_floor 对齐） */
interface FloorConfig {
  /** 精简模式每镜字数（底线密度锚点） */
  charsPerShot: number | null;
  /** 标准模式相对精简的上浮比例 */
  standardRatio: number | null;
  /** 细拆模式相对精简的上浮比例 */
  detailedRatio: number | null;
  /** 单次调用下限封顶 */
  maxFloor: number | null;
}

/** 后端代码默认值（配置缺失/非法时后端自动回退这些值） */
const DEFAULTS: FloorConfig = {
  charsPerShot: 300,
  standardRatio: 1.5,
  detailedRatio: 2.0,
  maxFloor: 40
};

/** 把外部 JSON 字符串解析为表单结构；解析失败回默认值 */
function parseConfig(value: string): FloorConfig {
  if (!value || !String(value).trim()) return { ...DEFAULTS };
  try {
    const obj = JSON.parse(value);
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return { ...DEFAULTS };
    const num = (v: any): number | null =>
      v === '' || v === null || v === undefined || isNaN(Number(v)) ? null : Number(v);
    return {
      charsPerShot: num(obj.charsPerShot) ?? DEFAULTS.charsPerShot,
      standardRatio: num(obj.standardRatio) ?? DEFAULTS.standardRatio,
      detailedRatio: num(obj.detailedRatio) ?? DEFAULTS.detailedRatio,
      maxFloor: num(obj.maxFloor) ?? DEFAULTS.maxFloor
    };
  } catch {
    return { ...DEFAULTS };
  }
}

/** 序列化回后端需要的紧凑 JSON 字符串 */
function serialize(cfg: FloorConfig): string {
  return JSON.stringify({
    charsPerShot: cfg.charsPerShot ?? DEFAULTS.charsPerShot,
    standardRatio: cfg.standardRatio ?? DEFAULTS.standardRatio,
    detailedRatio: cfg.detailedRatio ?? DEFAULTS.detailedRatio,
    maxFloor: cfg.maxFloor ?? DEFAULTS.maxFloor
  });
}

/** 按后端同款公式计算某档位下限：base = ⌈字数 ÷ 每镜字数⌉，档位 = ⌈base × 比例⌉，封顶 maxFloor */
function calcFloor(chars: number, cfg: FloorConfig, ratio: number): number {
  const cps = cfg.charsPerShot && cfg.charsPerShot > 0 ? cfg.charsPerShot : DEFAULTS.charsPerShot!;
  const cap = cfg.maxFloor && cfg.maxFloor > 0 ? cfg.maxFloor : DEFAULTS.maxFloor!;
  const base = Math.max(1, Math.ceil(chars / cps));
  return Math.min(cap, Math.max(1, Math.ceil(base * ratio)));
}

/** 参数项定义（标签 + 帮助说明 + 单位） */
interface ParamMeta {
  field: keyof FloorConfig;
  label: string;
  tip: string;
  addon?: string;
  step: number;
  precision: number;
}

const PARAMS: ParamMeta[] = [
  {
    field: 'charsPerShot',
    label: '每镜字数（精简模式）',
    tip: '底线密度锚点：剧本片段每 N 字至少拆 1 镜，即精简模式的最低镜头数。数值越小，整体拆得越细。',
    addon: '字 / 镜',
    step: 10,
    precision: 0
  },
  {
    field: 'standardRatio',
    label: '标准模式上浮比例',
    tip: '标准模式最低镜头数 = 精简下限 × 该比例（向上取整）。须大于 1，与提示词「精简≈标准的60%-70%」换算一致，默认 1.5。',
    addon: '倍',
    step: 0.1,
    precision: 1
  },
  {
    field: 'detailedRatio',
    label: '细拆模式上浮比例',
    tip: '细拆模式最低镜头数 = 精简下限 × 该比例（向上取整）。须大于标准比例，与提示词「细拆≈标准的130%-150%」换算一致，默认 2.0。',
    addon: '倍',
    step: 0.1,
    precision: 1
  },
  {
    field: 'maxFloor',
    label: '单次调用下限封顶',
    tip: '单个场次算出的下限超过该值时，强制最低数按封顶执行（AI 实际仍可按内容拆更多，不受此限制），防止一次调用要求过多镜头导致 AI 输出截断。提交时会对超长场次返回提醒，建议拆分场次或改用专业版。默认 40。',
    addon: '镜',
    step: 5,
    precision: 0
  }
];

/** 三档预览的展示元数据 */
const MODE_PREVIEWS = [
  { name: '精简模式', desc: '节奏紧凑', color: '#64748b', bg: '#f1f5f9', border: '#e2e8f0' },
  { name: '标准模式', desc: '默认档位', color: '#2563eb', bg: 'rgba(37, 99, 235, 0.08)', border: 'rgba(37, 99, 235, 0.25)' },
  { name: '细拆模式', desc: '重点戏份', color: '#4f46e5', bg: 'rgba(79, 70, 229, 0.08)', border: 'rgba(79, 70, 229, 0.25)' }
];

/** 分镜镜头数下限锚点（storyboard / shot_density_floor）专属编辑器
 *  存储形态：JSON 对象 {"charsPerShot":300,"standardRatio":1.5,"detailedRatio":2.0,"maxFloor":40}
 *  运营只维护「每镜字数」一个锚点，标准/细拆按上浮比例自动推导；
 *  卡片内置拆分规则说明与三档实时预览，便于开源部署用户理解和配置。 */
export default function ShotDensityField({ value, onChange }: Props) {
  const cfg = useMemo(() => parseConfig(value), [value]);
  // 预览用示例字数（仅前端试算，不写入配置）
  const [previewChars, setPreviewChars] = useState<number>(3000);

  const update = (patch: Partial<FloorConfig>) => {
    onChange(serialize({ ...cfg, ...patch }));
  };

  const invalid: Record<keyof FloorConfig, boolean> = {
    charsPerShot: !cfg.charsPerShot || cfg.charsPerShot <= 0,
    standardRatio: !cfg.standardRatio || cfg.standardRatio <= 1,
    detailedRatio:
      !cfg.detailedRatio ||
      cfg.detailedRatio <= 1 ||
      (cfg.standardRatio != null && cfg.detailedRatio <= cfg.standardRatio),
    maxFloor: !cfg.maxFloor || cfg.maxFloor <= 0
  };
  const hasInvalid = Object.values(invalid).some(Boolean);

  const ratios = [
    1.0,
    invalid.standardRatio ? DEFAULTS.standardRatio! : cfg.standardRatio!,
    invalid.detailedRatio ? DEFAULTS.detailedRatio! : cfg.detailedRatio!
  ];
  const floors = ratios.map((r) => calcFloor(previewChars, cfg, r));
  const effectiveCps =
    cfg.charsPerShot && cfg.charsPerShot > 0 ? cfg.charsPerShot : DEFAULTS.charsPerShot!;

  return (
    <div
      style={{
        width: '100%',
        border: '1px solid #e8ecf0',
        borderRadius: 10,
        background: '#fff',
        overflow: 'hidden'
      }}
    >
      {/* 规则说明卡：告诉运营这是什么、怎么算、管到哪 */}
      <div
        style={{
          padding: '14px 18px',
          background: 'linear-gradient(135deg, rgba(37, 99, 235, 0.06) 0%, rgba(99, 102, 241, 0.04) 100%)',
          borderBottom: '1px solid #eef0f6'
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontSize: 13,
            fontWeight: 600,
            color: '#303133',
            marginBottom: 8
          }}
        >
          <BulbOutlined style={{ color: '#2563eb' }} />
          拆分规则说明
        </div>
        <div style={{ fontSize: 12, color: '#606266', lineHeight: '20px' }}>
          <div>
            1. 生成分镜脚本时，系统按 <b>片段字数 ÷ 每镜字数</b>（向上取整）得到精简模式的
            <b>最低镜头数</b>，标准 / 细拆模式在此基础上按比例上浮，保证三档下限依次递增。
          </div>
          <div>
            2. 下限只约束「<b>不得少于</b>」：实际镜头数由 AI 按台词与动作节拍在下限之上自行决定，
            不设上限，长剧情不会因档位而丢内容。
          </div>
          <div>
            3. 仅对 <b>标准版 / 轻量版</b> 分镜编剧生效；解说版、专业版自带完整拆分保障，不受本配置影响。
            数值非法时系统自动回退默认值（{DEFAULTS.charsPerShot} / {DEFAULTS.standardRatio} /{' '}
            {DEFAULTS.detailedRatio} / {DEFAULTS.maxFloor}）。
          </div>
        </div>
      </div>

      {/* 参数区：2×2 网格 */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
          gap: '14px 24px',
          padding: '16px 18px'
        }}
      >
        {PARAMS.map((p) => (
          <div key={p.field}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                fontSize: 13,
                color: '#606266',
                marginBottom: 6
              }}
            >
              {p.label}
              <Tooltip title={p.tip}>
                <QuestionCircleOutlined style={{ color: '#c0c4cc', fontSize: 12, cursor: 'help' }} />
              </Tooltip>
            </div>
            <InputNumber
              value={cfg[p.field] ?? undefined}
              status={invalid[p.field] ? 'error' : ''}
              min={0}
              step={p.step}
              precision={p.precision}
              controls={false}
              onChange={(v) => update({ [p.field]: v == null ? null : Number(v) } as Partial<FloorConfig>)}
              addonAfter={p.addon}
              style={{ width: '100%', maxWidth: 220 }}
            />
          </div>
        ))}
      </div>

      {/* 非法值提示 */}
      {hasInvalid && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            margin: '0 18px 12px',
            padding: '8px 12px',
            background: '#fffbeb',
            border: '1px solid #fde68a',
            borderRadius: 8,
            fontSize: 12,
            color: '#b45309'
          }}
        >
          <WarningOutlined style={{ color: '#f59e0b' }} />
          存在非法值：每镜字数与封顶须大于 0，比例须大于 1 且细拆大于标准；保存后后端将按默认值执行。
        </div>
      )}

      {/* 三档实时预览 */}
      <div style={{ padding: '12px 18px 16px', background: '#fafbfc', borderTop: '1px solid #f0f2f5' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 8,
            fontSize: 12,
            color: '#909399',
            marginBottom: 10
          }}
        >
          <span>效果预览：假设某场次剧本片段为</span>
          <InputNumber
            value={previewChars}
            min={1}
            step={500}
            controls={false}
            onChange={(v) => setPreviewChars(v == null || Number(v) <= 0 ? 3000 : Number(v))}
            addonAfter="字"
            size="small"
            style={{ width: 110 }}
          />
          <span>（按每 {effectiveCps} 字 1 镜起算）</span>
          <Tooltip title="预览与后端计算公式完全一致；「≥」表示只可多不可少，实际镜头数由 AI 在此之上按剧情节拍决定">
            <InfoCircleOutlined style={{ color: '#c0c4cc', cursor: 'help' }} />
          </Tooltip>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 10 }}>
          {MODE_PREVIEWS.map((m, i) => (
            <div
              key={m.name}
              style={{
                padding: '10px 14px',
                background: m.bg,
                border: `1px solid ${m.border}`,
                borderRadius: 8
              }}
            >
              <div style={{ fontSize: 12, color: m.color, fontWeight: 600, marginBottom: 2 }}>
                {m.name}
                <span style={{ fontWeight: 400, marginLeft: 6, opacity: 0.75 }}>{m.desc}</span>
              </div>
              <div style={{ fontSize: 18, fontWeight: 700, color: m.color, lineHeight: '26px' }}>
                ≥ {floors[i]} <span style={{ fontSize: 12, fontWeight: 400 }}>镜</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
