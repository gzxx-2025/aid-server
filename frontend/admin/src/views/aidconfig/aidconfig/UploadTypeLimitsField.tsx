import React, { useMemo } from 'react';
import { Alert, Button, Dropdown, Empty, Input, InputNumber, Select, Space, Table, Tooltip } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';

/**
 * oss.uploadTypeLimits 分类型上传限制编辑器（表单 → JSON，运营无需手写 JSON）。
 *
 * 值是一段 JSON 数组字符串，落库到 aid_config(category=oss, config_name=uploadTypeLimits)，
 * 与本地、OSS、COS、七牛云存储模式共用（公共字段）。每个元素：
 *   { "name": "图片", "maxSizeMb": 10, "extensions": ["jpg","jpeg","png"] }
 * 后端 OssConfigManager 解析后按文件后缀命中类型校验单文件大小，单位 MB。
 * 编辑后随「保存配置」一起入库，再点「同步配置」刷新 OSS 缓存即时生效。
 *
 * 注：残缺行（无名称/无扩展名/无大小）后端解析时会被跳过（不生效），因此页面对残缺/重复行做高亮与告警，
 * 避免出现"以为配置了但实际未生效"的情况。
 */

interface Props {
  value: string;
  onChange: (v: string) => void;
}

interface LimitRow {
  name: string;
  extensions: string[];
  maxSizeMb?: number;
}

/** 常见类型预设（快速添加，避免手敲扩展名） */
const PRESETS: Array<{ key: string; name: string; extensions: string[]; maxSizeMb: number }> = [
  { key: 'image', name: '图片', extensions: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'], maxSizeMb: 10 },
  { key: 'video', name: '视频', extensions: ['mp4', 'mov', 'avi', 'mkv', 'webm'], maxSizeMb: 200 },
  { key: 'audio', name: '音频', extensions: ['mp3', 'wav', 'aac', 'm4a', 'flac', 'ogg'], maxSizeMb: 50 },
  { key: 'doc', name: '文档', extensions: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt'], maxSizeMb: 20 }
];

/** 规范化扩展名：去前导点、转小写、去空 */
function normExt(s: string): string {
  return s.trim().replace(/^\./, '').toLowerCase();
}

/** 解析 JSON 字符串为行数组（兼容 extensions 为数组或逗号串；解析失败返回空数组） */
function parseRows(value: string): LimitRow[] {
  if (!value || !value.trim()) return [];
  try {
    const arr = JSON.parse(value);
    if (!Array.isArray(arr)) return [];
    return arr.map((it: any) => {
      let exts: string[] = [];
      if (Array.isArray(it?.extensions)) {
        exts = it.extensions.map((e: any) => normExt(String(e))).filter(Boolean);
      } else if (typeof it?.extensions === 'string') {
        exts = it.extensions.split(/[,，\s]+/).map(normExt).filter(Boolean);
      }
      return {
        name: it?.name ? String(it.name) : '',
        extensions: Array.from(new Set(exts)),
        maxSizeMb: it?.maxSizeMb != null && !Number.isNaN(Number(it.maxSizeMb)) ? Number(it.maxSizeMb) : undefined
      };
    });
  } catch {
    return [];
  }
}

/** 行数组序列化为 JSON 字符串（空数组回写空串，便于后端走兜底逻辑） */
function serialize(rows: LimitRow[]): string {
  if (rows.length === 0) return '';
  return JSON.stringify(
    rows.map((r) => ({
      name: r.name.trim(),
      maxSizeMb: r.maxSizeMb,
      extensions: r.extensions
    }))
  );
}

export default function UploadTypeLimitsField({ value, onChange }: Props) {
  const rows = useMemo(() => parseRows(value), [value]);

  // 重名集合（trim 后非空且出现 >1 次）
  const dupNames = useMemo(() => {
    const count = new Map<string, number>();
    rows.forEach((r) => {
      const n = r.name.trim();
      if (n) count.set(n, (count.get(n) || 0) + 1);
    });
    return new Set(Array.from(count.entries()).filter(([, c]) => c > 1).map(([n]) => n));
  }, [rows]);

  // 跨类型重复的扩展名（同一后缀出现在多行，命中时只按第一行生效）
  const dupExts = useMemo(() => {
    const count = new Map<string, number>();
    rows.forEach((r) => r.extensions.forEach((e) => count.set(e, (count.get(e) || 0) + 1)));
    return new Set(Array.from(count.entries()).filter(([, c]) => c > 1).map(([e]) => e));
  }, [rows]);

  /** 收集所有告警信息 */
  const warnings = useMemo(() => {
    const list: string[] = [];
    rows.forEach((r, i) => {
      const label = r.name.trim() || `第${i + 1}行`;
      if (!r.name.trim()) list.push(`第${i + 1}行未填类型名称`);
      if (r.extensions.length === 0) list.push(`「${label}」未配置扩展名`);
      if (!r.maxSizeMb || r.maxSizeMb <= 0) list.push(`「${label}」未填写大小上限`);
    });
    if (dupNames.size > 0) list.push(`类型名称重复：${Array.from(dupNames).join('、')}`);
    if (dupExts.size > 0) list.push(`扩展名重复（仅第一处生效）：${Array.from(dupExts).join('、')}`);
    return list;
  }, [rows, dupNames, dupExts]);

  const commit = (next: LimitRow[]) => onChange(serialize(next));

  const update = (idx: number, patch: Partial<LimitRow>) => {
    commit(rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)));
  };

  const removeRow = (idx: number) => {
    commit(rows.filter((_, i) => i !== idx));
  };

  const addRow = (preset?: (typeof PRESETS)[number]) => {
    commit([
      ...rows,
      {
        name: preset?.name || '',
        extensions: preset ? [...preset.extensions] : [],
        maxSizeMb: preset?.maxSizeMb
      }
    ]);
  };

  const usedNames = useMemo(() => new Set(rows.map((r) => r.name.trim())), [rows]);

  const columns = [
    {
      title: '类型名称',
      dataIndex: 'name',
      width: 200,
      render: (_: any, _row: LimitRow, idx: number) => (
        <Input
          value={rows[idx].name}
          placeholder="如 图片 / 视频"
          maxLength={20}
          status={!rows[idx].name.trim() || dupNames.has(rows[idx].name.trim()) ? 'error' : ''}
          onChange={(e) => update(idx, { name: e.target.value })}
        />
      )
    },
    {
      title: (
        <span>
          允许的扩展名{' '}
          <Tooltip title="输入扩展名后回车添加，可添加多个；无需带点，大小写不敏感">
            <span className="help-text" style={{ cursor: 'help' }}>(?)</span>
          </Tooltip>
        </span>
      ),
      dataIndex: 'extensions',
      render: (_: any, _row: LimitRow, idx: number) => (
        <Select
          mode="tags"
          value={rows[idx].extensions}
          placeholder="输入扩展名回车添加，如 jpg、png、mp4"
          style={{ width: '100%' }}
          status={rows[idx].extensions.length === 0 ? 'error' : ''}
          tokenSeparators={[',', '，', ' ']}
          notFoundContent={null}
          onChange={(vals: string[]) =>
            update(idx, {
              extensions: Array.from(new Set(vals.map(normExt).filter(Boolean)))
            })
          }
        />
      )
    },
    {
      title: '单文件上限',
      dataIndex: 'maxSizeMb',
      width: 190,
      render: (_: any, _row: LimitRow, idx: number) => (
        <InputNumber
          min={1}
          max={10240}
          step={1}
          precision={0}
          controls={false}
          value={rows[idx].maxSizeMb}
          style={{ width: '100%' }}
          addonAfter="MB"
          placeholder="正整数"
          status={!rows[idx].maxSizeMb || rows[idx].maxSizeMb <= 0 ? 'error' : ''}
          // 仅允许整数：键盘拦截非数字键，parser 兜底剥离任何非数字字符（含粘贴）
          onKeyDown={(e) => {
            const allow = ['Backspace', 'Delete', 'ArrowLeft', 'ArrowRight', 'Tab', 'Home', 'End'];
            if (!allow.includes(e.key) && !/^\d$/.test(e.key) && !e.ctrlKey && !e.metaKey) {
              e.preventDefault();
            }
          }}
          parser={(v) => (v ? v.replace(/[^\d]/g, '') : '') as unknown as number}
          onChange={(v) => update(idx, { maxSizeMb: v ?? undefined })}
        />
      )
    },
    {
      title: '操作',
      dataIndex: 'op',
      width: 80,
      align: 'center' as const,
      render: (_: any, _row: LimitRow, idx: number) => (
        <Button type="text" danger icon={<DeleteOutlined />} onClick={() => removeRow(idx)} />
      )
    }
  ];

  return (
    <div
      style={{
        width: '100%',
        border: '1px solid #ebedf0',
        borderRadius: 8,
        padding: 16,
        background: '#fff'
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 12,
          flexWrap: 'wrap',
          gap: 8
        }}
      >
        <span className="help-text">
          按文件后缀命中类型后校验大小，本地 / OSS / COS 三种模式共用；未配置则回退全局限制。
        </span>
        <Space>
          <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={() => addRow()}>
            新增类型
          </Button>
          <Dropdown
            menu={{
              items: PRESETS.map((p) => ({
                key: p.key,
                label: `${p.name}（${p.extensions.join(', ')}）`,
                disabled: usedNames.has(p.name),
                onClick: () => addRow(p)
              }))
            }}
          >
            <Button size="small" icon={<PlusOutlined />}>
              快速添加常见类型
            </Button>
          </Dropdown>
        </Space>
      </div>

      {warnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="以下问题项保存后不会生效，请补全或去重"
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          }
        />
      )}

      <Table<LimitRow>
        rowKey={(_, idx) => String(idx)}
        columns={columns as any}
        dataSource={rows}
        pagination={false}
        size="middle"
        bordered
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="暂无类型，点「新增类型」或「快速添加常见类型」；不配置则回退全局大小/类型限制"
            />
          )
        }}
      />
      <div className="help-text" style={{ marginTop: 10 }}>
        修改后点页面右上角「保存配置」，再点「同步配置」即时生效。
      </div>
    </div>
  );
}
