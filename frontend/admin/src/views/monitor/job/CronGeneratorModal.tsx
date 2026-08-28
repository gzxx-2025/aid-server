import React, { useEffect, useState } from 'react';
import { Checkbox, Col, InputNumber, Modal, Radio, Row, Tabs, Tag } from 'antd';

interface Props {
  open: boolean;
  value?: string;
  onOk: (expr: string) => void;
  onCancel: () => void;
}

type TabKey = 'second' | 'minute' | 'hour' | 'day' | 'month' | 'week' | 'year';

interface FieldState {
  type: 'every' | 'range' | 'step' | 'specify' | 'none';
  rangeStart: number;
  rangeEnd: number;
  stepStart: number;
  stepValue: number;
  specify: number[];
}

const FIELD_RANGES: Record<TabKey, [number, number]> = {
  second: [0, 59],
  minute: [0, 59],
  hour: [0, 23],
  day: [1, 31],
  month: [1, 12],
  week: [1, 7],
  year: [2024, 2099]
};

const WEEK_LABELS = ['', '周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function getDefaultField(key: TabKey): FieldState {
  const [min] = FIELD_RANGES[key];
  return {
    type: key === 'year' ? 'none' : 'every',
    rangeStart: min,
    rangeEnd: min + 1,
    stepStart: min,
    stepValue: 1,
    specify: []
  };
}

function fieldToExpr(field: FieldState, key: TabKey): string {
  switch (field.type) {
    case 'every': return '*';
    case 'none': return '';
    case 'range': return `${field.rangeStart}-${field.rangeEnd}`;
    case 'step': return `${field.stepStart}/${field.stepValue}`;
    case 'specify':
      if (field.specify.length === 0) return '*';
      return field.specify.sort((a, b) => a - b).join(',');
    default: return '*';
  }
}

export default function CronGeneratorModal({ open, value, onOk, onCancel }: Props) {
  const [fields, setFields] = useState<Record<TabKey, FieldState>>({
    second: getDefaultField('second'),
    minute: getDefaultField('minute'),
    hour: getDefaultField('hour'),
    day: getDefaultField('day'),
    month: getDefaultField('month'),
    week: getDefaultField('week'),
    year: getDefaultField('year')
  });

  useEffect(() => {
    if (open && value) {
      // 尝试解析已有的 cron 表达式（简单解析，不完美但够用）
      // 不做复杂解析，用户可以直接在界面上重新选择
    }
  }, [open, value]);

  const updateField = (key: TabKey, patch: Partial<FieldState>) => {
    setFields((prev) => ({ ...prev, [key]: { ...prev[key], ...patch } }));
  };

  // 日和周互斥：如果日指定了，周用 ?；如果周指定了，日用 ?
  const buildExpression = (): string => {
    const s = fieldToExpr(fields.second, 'second');
    const mi = fieldToExpr(fields.minute, 'minute');
    const h = fieldToExpr(fields.hour, 'hour');
    let d = fieldToExpr(fields.day, 'day');
    const mo = fieldToExpr(fields.month, 'month');
    let w = fieldToExpr(fields.week, 'week');
    const y = fieldToExpr(fields.year, 'year');

    // 日/周互斥
    if (fields.week.type !== 'every' && fields.week.type !== 'none') {
      d = '?';
    } else if (fields.day.type !== 'every') {
      w = '?';
    } else {
      w = '?';
    }

    const parts = [s, mi, h, d, mo, w];
    if (y) parts.push(y);
    return parts.join(' ');
  };

  const cronExpr = buildExpression();

  const renderFieldPanel = (key: TabKey) => {
    const field = fields[key];
    const [min, max] = FIELD_RANGES[key];
    const isWeek = key === 'week';
    const isYear = key === 'year';

    return (
      <div style={{ padding: '12px 0' }}>
        <Radio.Group
          value={field.type}
          onChange={(e) => updateField(key, { type: e.target.value })}
          style={{ display: 'flex', flexDirection: 'column', gap: 12 }}
        >
          {!isYear && <Radio value="every">每{getFieldLabel(key)}</Radio>}
          {isYear && <Radio value="none">不指定（非必填）</Radio>}
          {isYear && <Radio value="every">每年</Radio>}

          <Radio value="range">
            <span>范围：从 </span>
            <InputNumber
              size="small"
              min={min}
              max={max}
              value={field.rangeStart}
              onChange={(v) => updateField(key, { rangeStart: v ?? min })}
              style={{ width: 70 }}
            />
            <span> 到 </span>
            <InputNumber
              size="small"
              min={min}
              max={max}
              value={field.rangeEnd}
              onChange={(v) => updateField(key, { rangeEnd: v ?? min })}
              style={{ width: 70 }}
            />
          </Radio>

          <Radio value="step">
            <span>从 </span>
            <InputNumber
              size="small"
              min={min}
              max={max}
              value={field.stepStart}
              onChange={(v) => updateField(key, { stepStart: v ?? min })}
              style={{ width: 70 }}
            />
            <span> 开始，每 </span>
            <InputNumber
              size="small"
              min={1}
              max={max - min + 1}
              value={field.stepValue}
              onChange={(v) => updateField(key, { stepValue: v ?? 1 })}
              style={{ width: 70 }}
            />
            <span> {getFieldLabel(key)}执行一次</span>
          </Radio>

          <Radio value="specify">指定</Radio>
        </Radio.Group>

        {field.type === 'specify' && (
          <div style={{ marginTop: 12, paddingLeft: 24 }}>
            <Checkbox.Group
              value={field.specify}
              onChange={(vals) => updateField(key, { specify: vals as number[] })}
            >
              <Row gutter={[8, 8]}>
                {Array.from({ length: max - min + 1 }, (_, i) => min + i).map((n) => (
                  <Col key={n} span={isWeek ? 6 : 4}>
                    <Checkbox value={n}>
                      {isWeek ? WEEK_LABELS[n] || n : n}
                    </Checkbox>
                  </Col>
                ))}
              </Row>
            </Checkbox.Group>
          </div>
        )}
      </div>
    );
  };

  return (
    <Modal
      open={open}
      title="Cron 表达式生成器"
      width={680}
      onCancel={onCancel}
      onOk={() => onOk(cronExpr)}
      okText="确定"
      cancelText="取消"
      destroyOnClose
    >
      <div style={{ marginBottom: 16, padding: '10px 16px', background: '#f5f7fa', borderRadius: 8 }}>
        <span style={{ color: '#606266', marginRight: 8 }}>表达式：</span>
        <Tag color="blue" style={{ fontSize: 14, padding: '4px 12px' }}>{cronExpr}</Tag>
      </div>
      <Tabs
        size="small"
        items={[
          { key: 'second', label: '秒', children: renderFieldPanel('second') },
          { key: 'minute', label: '分', children: renderFieldPanel('minute') },
          { key: 'hour', label: '时', children: renderFieldPanel('hour') },
          { key: 'day', label: '日', children: renderFieldPanel('day') },
          { key: 'month', label: '月', children: renderFieldPanel('month') },
          { key: 'week', label: '周', children: renderFieldPanel('week') },
          { key: 'year', label: '年', children: renderFieldPanel('year') }
        ]}
      />
    </Modal>
  );
}

function getFieldLabel(key: TabKey): string {
  const map: Record<TabKey, string> = {
    second: '秒',
    minute: '分钟',
    hour: '小时',
    day: '天',
    month: '月',
    week: '周',
    year: '年'
  };
  return map[key] || '';
}
