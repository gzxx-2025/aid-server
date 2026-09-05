import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Col,
  Collapse,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  message
} from 'antd';
import {
  CheckCircleOutlined,
  ExperimentOutlined,
  LinkOutlined,
  ReloadOutlined,
  SaveOutlined,
  WarningOutlined
} from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import SectionTitle from '@/components/SectionTitle';
import {
  getWechatNotifyConfig,
  getWechatNotifyStatus,
  getWechatNotifyTemplates,
  saveWechatNotifyConfig,
  testWechatNotifySend,
  type WechatNotifyConfig,
  type WechatNotifyStatus,
  type WechatNotifyTemplateConfig,
  type WechatTemplateSendResult
} from '@/api/aidconfig/aidconfig';
import './WechatNotifySection.less';

interface Props {
  onJumpToWxLogin?: () => void;
}

interface TemplateItem {
  templateId: string;
  title: string;
  content?: string;
  example?: string;
}

interface EventMeta {
  key: string;
  title: string;
  templateId: string;
  templateNo: string;
  scene: string;
  fields: Array<{ name: string; label: string; keyword: string; hint: string }>;
}

const EVENT_METAS: EventMeta[] = [
  {
    key: 'balance_insufficient',
    title: '余额核验异常提醒',
    templateId: 'uNI_iX6YcnDOLvsTB1trUeDOzaBK2rn9U3tpr89jzvU',
    templateNo: '72715',
    scene: '用户单次充值金额大于后台阈值后获得一次资格，余额低于阈值或预扣费不足时触发，成功后消耗资格。',
    fields: [
      { name: 'accountName', label: '账户名称', keyword: 'thing4', hint: 'thing4.DATA，最多20字' },
      { name: 'currentBalance', label: '当前余额', keyword: 'amount3', hint: 'amount3.DATA，金额格式' },
      { name: 'alarmTime', label: '告警时间', keyword: 'time5', hint: 'time5.DATA' }
    ]
  },
  {
    key: 'batch_started',
    title: '订单已开始通知',
    templateId: 'DgHb3Pb9B7H6jB5V3Ubm59sILsInKeIy7ewgV6wP6Yo',
    templateNo: '43122',
    scene: '仅全量批量生成或失败后继续的全量任务触发，单个/部分ID不推送。',
    fields: [
      { name: 'serviceProject', label: '服务项目', keyword: 'thing10', hint: 'thing10.DATA，最多20字' },
      { name: 'startTime', label: '开始时间', keyword: 'time4', hint: 'time4.DATA' }
    ]
  },
  {
    key: 'batch_succeeded',
    title: '订单完成通知',
    templateId: 'bAcI90AOnIoCUamhy5hWZFYISJdYWqkgSU4lEBDB-To',
    templateNo: '44703',
    scene: '全量批量任务完成时触发。官方标准模板只包含项目名称和完成时间，详细结果进入系统查看。',
    fields: [
      { name: 'projectName', label: '项目名称', keyword: 'thing19', hint: 'thing19.DATA，最多20字' },
      { name: 'finishTime', label: '完成时间', keyword: 'time18', hint: 'time18.DATA' }
    ]
  },
  {
    key: 'batch_failed',
    title: '交易失败通知',
    templateId: 'hLaniNK4118iL8hKO1itKOssx1n1xgtGPhX8H1zT4q0',
    templateNo: '68442',
    scene: '全量批量任务失败或部分失败时触发。官方标准模板只包含产品名称、订单金额和失败时间，失败原因进入系统查看。',
    fields: [
      { name: 'productName', label: '产品名称', keyword: 'thing2', hint: 'thing2.DATA，最多20字' },
      { name: 'orderAmount', label: '订单金额', keyword: 'amount3', hint: 'amount3.DATA，金额格式' },
      { name: 'failureTime', label: '失败时间', keyword: 'time4', hint: 'time4.DATA' }
    ]
  },
  {
    key: 'order_refund',
    title: '退款通知',
    templateId: 'XVkL-i8pVaz9LZT-fnmd-p9tRHJkLnUlQrx_GCImZPY',
    templateNo: '45802',
    scene: '支付订单全额退款成功并首次更新为已退款状态时触发。',
    fields: [
      { name: 'orderName', label: '订单名称', keyword: 'thing8', hint: 'thing8.DATA，最多20字' },
      { name: 'orderNo', label: '订单号', keyword: 'thing7', hint: 'thing7.DATA，最多20字' },
      { name: 'refundReason', label: '退款原因', keyword: 'thing6', hint: 'thing6.DATA，最多20字' },
      { name: 'refundAmount', label: '退款金额', keyword: 'amount2', hint: 'amount2.DATA，金额格式' },
      { name: 'refundUser', label: '退款用户', keyword: 'thing10', hint: 'thing10.DATA，最多20字' }
    ]
  }
];

const DEFAULT_FIELDS: Record<string, Record<string, string>> = EVENT_METAS.reduce(
  (acc, meta) => {
    acc[meta.key] = meta.fields.reduce((fieldAcc, field) => {
      fieldAcc[field.name] = field.keyword;
      return fieldAcc;
    }, {} as Record<string, string>);
    return acc;
  },
  {} as Record<string, Record<string, string>>
);

function toBool(v: unknown): boolean {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') return v.trim().toLowerCase() === 'true';
  return Boolean(v);
}

function toNum(v: unknown, fallback: number): number {
  if (v === undefined || v === null || v === '') return fallback;
  const n = Number(v);
  return Number.isNaN(n) ? fallback : n;
}

function normalizeTemplate(meta: EventMeta): WechatNotifyTemplateConfig {
  return {
    enabled: true,
    title: meta.title,
    templateId: meta.templateId,
    fields: DEFAULT_FIELDS[meta.key]
  };
}

function normalizeConfig(input?: Partial<WechatNotifyConfig> | null): WechatNotifyConfig {
  const templates: Record<string, WechatNotifyTemplateConfig> = {};
  EVENT_METAS.forEach((meta) => {
    templates[meta.key] = normalizeTemplate(meta);
  });
  return {
    enabled: toBool(input?.enabled),
    jumpUrlBase: input?.jumpUrlBase || '',
    dailyUserLimit: toNum(input?.dailyUserLimit, 20),
    minuteUserLimit: toNum(input?.minuteUserLimit, 3),
    balanceReminderThreshold: toNum(input?.balanceReminderThreshold, 0),
    templates
  };
}

function templateValue(raw: any, camel: string, snake: string): string {
  return String(raw?.[camel] || raw?.[snake] || '');
}

function parseTemplates(raw: any): TemplateItem[] {
  const source = raw?.templateList || raw?.template_list || raw?.data?.templateList || raw?.data?.template_list || raw;
  const list = Array.isArray(source) ? source : [];
  return list
    .map((item) => ({
      templateId: templateValue(item, 'templateId', 'template_id'),
      title: templateValue(item, 'title', 'title'),
      content: templateValue(item, 'content', 'content'),
      example: templateValue(item, 'example', 'example')
    }))
    .filter((item) => item.templateId);
}

function statusTag(ok?: boolean) {
  return ok ? <Tag color="green">已完成</Tag> : <Tag color="red">待配置</Tag>;
}

export default function WechatNotifySection({ onJumpToWxLogin }: Props) {
  const [form] = Form.useForm<WechatNotifyConfig>();
  const [testForm] = Form.useForm<{ openid: string; eventType: string }>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [templateLoading, setTemplateLoading] = useState(false);
  const [testing, setTesting] = useState(false);
  const [status, setStatus] = useState<WechatNotifyStatus | null>(null);
  const [templateList, setTemplateList] = useState<TemplateItem[]>([]);
  const [testResult, setTestResult] = useState<WechatTemplateSendResult | null>(null);

  const loadStatus = async () => {
    const res: any = await getWechatNotifyStatus();
    setStatus(res.data || {});
  };

  const loadTemplates = async () => {
    setTemplateLoading(true);
    try {
      const res: any = await getWechatNotifyTemplates();
      setTemplateList(parseTemplates(res.data || res));
    } catch {
      setTemplateList([]);
    } finally {
      setTemplateLoading(false);
    }
  };

  const load = async () => {
    setLoading(true);
    try {
      const [configRes] = await Promise.all([getWechatNotifyConfig(), loadStatus()]);
      form.setFieldsValue(normalizeConfig((configRes as any).data));
      testForm.setFieldsValue({ eventType: 'batch_started' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleSave = async () => {
    const values = await form.validateFields();
    const payload = normalizeConfig(values);
    setSaving(true);
    try {
      await saveWechatNotifyConfig(payload);
      message.success('保存成功');
      await load();
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    const values = await testForm.validateFields();
    setTesting(true);
    setTestResult(null);
    try {
      const res: any = await testWechatNotifySend(values);
      const data = res.data || {};
      setTestResult(data);
      if (data.errcode === 0) {
        message.success('模板消息发送成功');
      } else {
        message.warning(data.errmsg || '微信返回失败');
      }
    } finally {
      setTesting(false);
    }
  };

  const checklist = [
    { label: '微信公众号登录已开启', ok: status?.wxLoginEnabled },
    { label: 'AppId 已配置', ok: status?.appIdConfigured },
    { label: 'AppSecret 已配置', ok: status?.secretConfigured },
    { label: 'Token 已配置', ok: status?.tokenConfigured },
    { label: 'EncodingAESKey 已配置', ok: status?.encodingAesKeyConfigured },
    { label: '五个官方标准模板已启用', ok: status?.templateConfigured }
  ];

  return (
    <div className="wechat-notify-section">
      <Spin spinning={loading}>
        <PageCard
          title="微信公众号推送"
          extra={
            <Space wrap>
              <Button icon={<LinkOutlined />} onClick={onJumpToWxLogin}>
                微信公众号配置
              </Button>
              <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
                刷新状态
              </Button>
              <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
                保存配置
              </Button>
            </Space>
          }
        >
          <Alert
            type={status?.ready ? 'success' : 'warning'}
            showIcon
            icon={status?.ready ? <CheckCircleOutlined /> : <WarningOutlined />}
            message={status?.ready ? '推送配置可用' : '推送配置未就绪'}
            description={
              status?.missingItems?.length
                ? status.missingItems.join('；')
                : '后台总开关关闭时不会发送任何微信推送。开启前请先完成微信公众号配置和模板配置。'
            }
            style={{ marginBottom: 16 }}
          />

          <Row gutter={[16, 16]} style={{ marginBottom: 12 }}>
            {checklist.map((item) => (
              <Col span={8} key={item.label}>
                <div className="wechat-notify-section__check-item">
                  {statusTag(item.ok)}
                  <span>{item.label}</span>
                </div>
              </Col>
            ))}
          </Row>

          <SectionTitle title="基础设置" />
          <Form
            form={form}
            layout="horizontal"
            labelCol={{ flex: '150px' }}
            wrapperCol={{ flex: 'auto' }}
            labelAlign="right"
            style={{ maxWidth: 920 }}
          >
            <Form.Item label="推送总开关" name="enabled" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>
            <Form.Item label="跳转基础地址" name="jumpUrlBase" extra="模板消息点击后的 H5 地址，可留空不跳转">
              <Input placeholder="https://your-domain/app/tasks" allowClear />
            </Form.Item>
            <Form.Item label="单用户每日上限" name="dailyUserLimit" extra="应用侧限流，防止频繁打扰用户">
              <InputNumber min={1} max={1000} style={{ width: 180 }} addonAfter="条/日" />
            </Form.Item>
            <Form.Item label="单用户每分钟上限" name="minuteUserLimit">
              <InputNumber min={1} max={20} style={{ width: 180 }} addonAfter="条/分钟" />
            </Form.Item>
            <Form.Item
              label="余额提醒阈值"
              name="balanceReminderThreshold"
              extra="单次充值金额必须大于该值，才会获得一次余额不足提醒资格"
            >
              <InputNumber min={0} precision={2} style={{ width: 180 }} addonAfter="积分" />
            </Form.Item>

            <SectionTitle title="模板配置" />
            <div style={{ marginBottom: 12 }}>
              <Space wrap>
                <Button icon={<ReloadOutlined />} loading={templateLoading} onClick={loadTemplates}>
                  刷新已选模板
                </Button>
                <span className="help-text">
                  当前固定使用微信官方标准模板，模板 ID 与关键词映射由系统维护。
                </span>
              </Space>
            </div>

            <Collapse
              defaultActiveKey={EVENT_METAS.map((meta) => meta.key)}
              items={EVENT_METAS.map((meta) => ({
                key: meta.key,
                label: (
                  <Space>
                    <span>{meta.title}</span>
                    <Tag bordered={false}>{meta.key}</Tag>
                    <Tag bordered={false}>编号 {meta.templateNo}</Tag>
                  </Space>
                ),
                children: (
                  <div>
                    <div className="help-text wechat-notify-section__scene">{meta.scene}</div>
                    <Descriptions
                      size="small"
                      bordered
                      column={2}
                      items={[
                        {
                          key: 'templateId',
                          label: '模板ID',
                          children: <span className="code-text">{meta.templateId}</span>
                        },
                        ...meta.fields.map((field) => ({
                          key: field.name,
                          label: field.label,
                          children: (
                            <>
                              <span className="code-text">{field.keyword}</span>
                              <div className="help-text">{field.hint}</div>
                            </>
                          )
                        }))
                      ]}
                    />
                  </div>
                )
              }))}
            />
          </Form>
        </PageCard>
      </Spin>

      <PageCard
        title="推送规则"
        extra={
          <Button icon={<ReloadOutlined />} loading={templateLoading} onClick={loadTemplates}>
            已选模板列表
          </Button>
        }
      >
        <ul className="wechat-notify-section__rules">
          {(status?.rules || []).map((rule) => (
            <li key={rule}>{rule}</li>
          ))}
        </ul>
        {templateList.length > 0 && (
          <Collapse
            ghost
            style={{ marginTop: 8 }}
            items={[
              {
                key: 'templates',
                label: `已读取 ${templateList.length} 个微信模板`,
                children: (
                  <div className="wechat-notify-section__template-list">
                    {templateList.map((item) => (
                      <div key={item.templateId} className="wechat-notify-section__template-item">
                        <Space wrap>
                          <strong>{item.title || '未命名模板'}</strong>
                          <Tag>{item.templateId}</Tag>
                        </Space>
                        {item.content && (
                          <div className="help-text wechat-notify-section__template-content">
                            {item.content}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )
              }
            ]}
          />
        )}
      </PageCard>

      <PageCard
        title="测试发送"
        extra={
          <Button type="primary" ghost icon={<ExperimentOutlined />} onClick={handleTest} loading={testing}>
            发送测试
          </Button>
        }
      >
        <Form form={testForm} layout="inline" initialValues={{ eventType: 'batch_started' }}>
          <Form.Item
            label="OpenID"
            name="openid"
            rules={[{ required: true, message: '请输入接收人的 OpenID' }]}
          >
            <Input style={{ width: 320 }} placeholder="接收测试消息的公众号 OpenID" allowClear />
          </Form.Item>
          <Form.Item label="模板类型" name="eventType">
            <Select
              style={{ width: 220 }}
              options={EVENT_METAS.map((meta) => ({ label: meta.title, value: meta.key }))}
            />
          </Form.Item>
        </Form>
        {testResult && (
          <div className="wechat-notify-section__test-result">
            <Space wrap>
              <span>errcode：<Tag color={testResult.errcode === 0 ? 'green' : 'red'}>{testResult.errcode}</Tag></span>
              <span>errmsg：{testResult.errmsg || '-'}</span>
              {testResult.msgid ? <span>msgid：{testResult.msgid}</span> : null}
            </Space>
          </div>
        )}
      </PageCard>
    </div>
  );
}
