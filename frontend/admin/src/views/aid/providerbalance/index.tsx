import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import dayjs, { Dayjs } from 'dayjs'
import {
  Alert, Button, Card, Col, DatePicker, Descriptions, Drawer, Empty, Form, Image, Input,
  InputNumber, Modal, Popconfirm, Row, Select, Space, Statistic, Switch, Table, Tabs, Tag,
  Typography, message
} from 'antd'
import {
  BellOutlined, ClockCircleOutlined, DeleteOutlined, EditOutlined,
  ExclamationCircleOutlined, HistoryOutlined, MailOutlined, MobileOutlined, PlusOutlined,
  ReloadOutlined, SafetyCertificateOutlined, SendOutlined, SettingOutlined, ThunderboltOutlined,
  WalletOutlined, WechatOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import Auth from '@/components/Auth'
import {
  acknowledgeBalanceIncident, addBalanceAdjustment, addBalanceRecipient, addBalanceRule, BalanceOverview,
  BalanceProvider, BalanceRecipient, BalanceSettings, checkBalanceProvider,
  createWechatRecipientQr, deleteBalanceRecipient, deleteBalanceRule, getBalanceOverview,
  getWechatRecipientQrStatus, listBalanceDeliveries, listBalanceIncidents,
  listBalanceProviders, listBalanceRecipients, listBalanceRules, saveBalanceProvider, saveBalanceSettings,
  testBalanceRecipient, toggleBalanceRule, updateBalanceRecipient, updateBalanceRule
} from '@/api/aid/providerBalance'
import type { ProviderErrorRule } from '@/api/aid/errorrule'
import './style.less'

const { Text, Title } = Typography
const CHANNEL_META: Record<string, { label: string; icon: React.ReactNode; color: string }> = {
  EMAIL: { label: '邮箱', icon: <MailOutlined />, color: '#165dff' },
  SMS: { label: '短信', icon: <MobileOutlined />, color: '#00b42a' },
  WECHAT: { label: '微信公众号', icon: <WechatOutlined />, color: '#07c160' }
}

function numberText(value?: number, unit?: string) {
  if (value === undefined || value === null) return '不可查询'
  return `${Number(value).toLocaleString(undefined, { maximumFractionDigits: 8 })}${unit ? ` ${unit}` : ''}`
}

function statusTag(status?: string) {
  if (status === 'CRITICAL') return <Tag color="error">严重不足</Tag>
  if (status === 'WARNING') return <Tag color="warning">余额预警</Tag>
  return <Tag color="success">正常</Tag>
}

export default function ProviderBalancePage() {
  const [overview, setOverview] = useState<BalanceOverview>()
  const [providers, setProviders] = useState<BalanceProvider[]>([])
  const [recipients, setRecipients] = useState<BalanceRecipient[]>([])
  const [loading, setLoading] = useState(false)
  const [activeTab, setActiveTab] = useState('providers')
  const refreshRef = useRef<Promise<void> | null>(null)

  const refreshAll = useCallback((force = false) => {
    if (refreshRef.current) return refreshRef.current
    const run = Promise.all([
      getBalanceOverview(force), listBalanceProviders(force), listBalanceRecipients(force)
    ]).then(([o, p, r]: any[]) => {
      setOverview(o?.data)
      setProviders(p?.data || [])
      setRecipients(r?.data || [])
    }).finally(() => {
      refreshRef.current = null
      setLoading(false)
    })
    setLoading(true)
    refreshRef.current = run
    return run
  }, [])

  useEffect(() => { refreshAll() }, [refreshAll])

  const enabledProviders = useMemo(() => providers.filter((item) => item.enabled), [providers])

  return (
    <div className="provider-balance-page">
      <PageHeader
        title={<><WalletOutlined />供应商余额监控</>}
        desc="按供应商统一管理官方余额、理论模拟余额和多渠道低余额提醒"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => refreshAll(true)}>刷新状态</Button>}
      />

      {!overview?.settings?.enabled && (
        <Alert
          className="balance-master-alert"
          type="info"
          showIcon
          message="余额监控当前已关闭"
          description="供应商查询、错误触发、实时提醒和余额日报均不会运行；已有配置与理论成本台账会保留。"
        />
      )}

      <Row gutter={[14, 14]} className="balance-stats">
        <Col xs={12} lg={6}><Card bordered={false}><Statistic title="监控供应商" value={overview?.monitoredCount || 0} suffix={`/ ${overview?.providerCount || 0}`} prefix={<WalletOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card bordered={false}><Statistic title="余额风险" value={(overview?.warningCount || 0) + (overview?.criticalCount || 0)} prefix={<ExclamationCircleOutlined />} valueStyle={{ color: (overview?.criticalCount || 0) > 0 ? '#f53f3f' : '#ff7d00' }} /></Card></Col>
        <Col xs={12} lg={6}><Card bordered={false}><Statistic title="未恢复事件" value={overview?.openIncidentCount || 0} prefix={<BellOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card bordered={false}><Statistic title="有效提醒人" value={overview?.recipientCount || 0} prefix={<SafetyCertificateOutlined />} /></Card></Col>
      </Row>

      <Card bordered={false} className="balance-main-card">
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          tabBarExtraContent={overview && <Auth permission="aid:providerbalance:edit"><GlobalSettingsButton overview={overview} onSaved={() => refreshAll(true)} /></Auth>}
          items={[
            {
              key: 'providers', label: <span><WalletOutlined />供应商监控</span>,
              children: <ProviderPanel providers={providers} loading={loading} moduleEnabled={!!overview?.settings?.enabled} onChanged={() => refreshAll(true)} />
            },
            {
              key: 'recipients', label: <span><BellOutlined />提醒人与渠道</span>,
              children: <RecipientPanel providers={enabledProviders} recipients={recipients} overview={overview} onChanged={() => refreshAll(true)} />
            },
            {
              key: 'incidents', label: <span><HistoryOutlined />告警与发送记录</span>,
              children: <IncidentPanel providers={providers} />
            },
            {
              key: 'rules', label: <span><ThunderboltOutlined />余额错误规则</span>,
              children: <BalanceRulePanel providers={providers} />
            }
          ]}
        />
      </Card>
    </div>
  )
}

function GlobalSettingsButton({ overview, onSaved }: { overview: BalanceOverview; onSaved: () => void }) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<BalanceSettings>()
  const settings = overview.settings

  const show = () => {
    form.setFieldsValue(settings)
    setOpen(true)
  }
  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await saveBalanceSettings(values)
      message.success(values.enabled ? '余额监控已启用' : '配置已保存，余额监控保持关闭')
      setOpen(false)
      onSaved()
    } finally { setSaving(false) }
  }

  return (
    <>
      <Space>
        <Tag color={settings.enabled ? 'success' : 'default'}>{settings.enabled ? '监控运行中' : '监控已关闭'}</Tag>
        <Button type="primary" icon={<SettingOutlined />} onClick={show}>全局设置</Button>
      </Space>
      <Modal title="供应商余额监控 · 全局设置" open={open} width={760} confirmLoading={saving} onOk={save} onCancel={() => setOpen(false)} okText="保存配置">
        <Form form={form} layout="vertical" initialValues={settings}>
          <Alert type="warning" showIcon message="总开关关闭时不会查询余额、匹配请求错误、发送提醒或日报。" className="form-alert" />
          <Row gutter={18}>
            <Col span={12}><Form.Item name="enabled" label="模块总开关" valuePropName="checked"><Switch checkedChildren="开启" unCheckedChildren="关闭" /></Form.Item></Col>
            <Col span={12}><Form.Item name="defaultRepeatIntervalMinutes" label="默认重复提醒间隔（分钟）" rules={[{ required: true }]}><InputNumber min={5} max={10080} style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={12}><Form.Item name="failureRetryMinutes" label="全部发送失败后重试（分钟）" rules={[{ required: true }]}><InputNumber min={1} max={1440} style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={12}><Form.Item name="snapshotRetentionDays" label="余额快照保留（天）" rules={[{ required: true }]}><InputNumber min={7} max={3650} style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={12}><Form.Item name="deliveryRetentionDays" label="发送记录保留（天）" rules={[{ required: true }]}><InputNumber min={7} max={3650} style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Card size="small" title={<><MailOutlined /> 邮件余额日报</>} className="settings-section">
            <Row gutter={18}>
              <Col span={12}><Form.Item name="dailyReportEnabled" label="发送每日余额详情" valuePropName="checked"><Switch disabled={!overview.channels.EMAIL.enabled} /></Form.Item></Col>
              <Col span={12}><Form.Item name="dailyReportTime" label="每日发送时间" rules={[{ required: true }, { pattern: /^([01]\d|2[0-3]):[0-5]\d$/, message: '格式应为 HH:mm' }]}><Input placeholder="09:00" /></Form.Item></Col>
            </Row>
            {!overview.channels.EMAIL.enabled && <Text type="secondary">邮箱服务未开启，不能启用余额详情日报。</Text>}
          </Card>
          <Card size="small" title={<><MobileOutlined /> 短信余额提醒</>} className="settings-section">
            <Form.Item name="smsTemplateId" label="余额提醒模板 ID / 短信宝内容模板" extra="阿里云、腾讯云填写审核通过的模板 ID；短信宝填写含 {provider}、{balance}、{status} 占位符的完整内容。">
              <Input disabled={!overview.channels.SMS.enabled} placeholder="例如 SMS_123456；短信宝可填：【AID】{provider}余额{balance}" />
            </Form.Item>
          </Card>
          <Card size="small" title={<><WechatOutlined /> 微信模板消息</>} className="settings-section">
            <Row gutter={18}>
              <Col span={12}><Form.Item name="wechatTemplateId" label="模板 ID"><Input disabled={!overview.channels.WECHAT.enabled} placeholder="稍后可补充" /></Form.Item></Col>
              <Col span={12}><Form.Item name="wechatJumpUrl" label="点击跳转地址"><Input disabled={!overview.channels.WECHAT.enabled} placeholder="https://...（可选）" /></Form.Item></Col>
              <Col span={6}><Form.Item name="wechatProviderField" label="供应商字段"><Input /></Form.Item></Col>
              <Col span={6}><Form.Item name="wechatBalanceField" label="余额字段"><Input /></Form.Item></Col>
              <Col span={6}><Form.Item name="wechatStatusField" label="状态字段"><Input /></Form.Item></Col>
              <Col span={6}><Form.Item name="wechatTimeField" label="时间字段"><Input /></Form.Item></Col>
            </Row>
          </Card>
        </Form>
      </Modal>
    </>
  )
}

function ProviderPanel({ providers, loading, moduleEnabled, onChanged }: { providers: BalanceProvider[]; loading: boolean; moduleEnabled: boolean; onChanged: () => void }) {
  const [editing, setEditing] = useState<BalanceProvider>()
  const [checking, setChecking] = useState<number>()
  const [adjusting, setAdjusting] = useState<BalanceProvider>()
  const [saving, setSaving] = useState(false)
  const [adjustSaving, setAdjustSaving] = useState(false)
  const [form] = Form.useForm()
  const [adjustForm] = Form.useForm()

  const openEditor = (row: BalanceProvider) => {
    setEditing(row)
    form.setFieldsValue({
      ...row,
      initialTime: row.initialTime ? dayjs(row.initialTime) : undefined
    })
  }
  const save = async () => {
    const values = await form.validateFields()
    const payload = {
      ...values,
      enabled: values.enabled ? 1 : 0,
      apiEnabled: values.apiEnabled ? 1 : 0,
      simulatedEnabled: values.simulatedEnabled ? 1 : 0,
      errorRuleEnabled: values.errorRuleEnabled ? 1 : 0,
      forecastEnabled: values.forecastEnabled ? 1 : 0,
      initialTime: values.initialTime ? (values.initialTime as Dayjs).format('YYYY-MM-DD HH:mm:ss') : null
    }
    setSaving(true)
    try {
      await saveBalanceProvider(editing!.providerId, payload)
      message.success('供应商监控配置已保存')
      setEditing(undefined)
      onChanged()
    } finally { setSaving(false) }
  }
  const check = async (row: BalanceProvider) => {
    setChecking(row.providerId)
    try {
      await checkBalanceProvider(row.providerId)
      message.success(`${row.providerName} 余额检查完成`)
      onChanged()
    } finally { setChecking(undefined) }
  }

  const columns: any[] = [
    {
      title: '供应商', width: 220, fixed: 'left',
      render: (_: any, row: BalanceProvider) => <Space><Image width={30} height={30} preview={false} src={row.logoUrl} fallback="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" /><div><div className="provider-name">{row.providerName}</div><Text type="secondary">{row.providerCode}</Text></div></Space>
    },
    { title: '监控', width: 90, render: (_: any, row: BalanceProvider) => <Tag color={row.enabled ? 'blue' : 'default'}>{row.enabled ? '已选择' : '未选择'}</Tag> },
    { title: '当前状态', width: 105, render: (_: any, row: BalanceProvider) => row.enabled ? statusTag(row.currentStatus) : <Text type="secondary">不参与</Text> },
    {
      title: '余额', width: 180,
      render: (_: any, row: BalanceProvider) => row.enabled ? <div><strong>{numberText(row.currentBalance, row.currency)}</strong><div><Text type="secondary">{row.currentSource || '尚未检查'}</Text></div></div> : '-'
    },
    {
      title: '检测来源', width: 250,
      render: (_: any, row: BalanceProvider) => <Space wrap size={4}>
        <Tag color={row.apiEnabled ? 'cyan' : 'default'}>{row.apiSupported ? '官方 API' : '无官方 API'}</Tag>
        <Tag color={row.simulatedEnabled ? 'purple' : 'default'}>模拟余额</Tag>
        <Tag color={row.errorRuleEnabled ? 'gold' : 'default'}>错误规则</Tag>
      </Space>
    },
    { title: '阈值（严重 / 预警）', width: 180, render: (_: any, row: BalanceProvider) => row.enabled ? `${numberText(row.criticalThreshold)} / ${numberText(row.warningThreshold)}` : '-' },
    { title: '预计可用', width: 120, render: (_: any, row: BalanceProvider) => row.runwayDays == null ? '-' : `${row.runwayDays} 天` },
    { title: '最后检查', dataIndex: 'lastCheckTime', width: 170, render: (v: string) => v || '-' },
    {
      title: '操作', width: 190, fixed: 'right',
      render: (_: any, row: BalanceProvider) => <Space size={2}>
        <Auth permission="aid:providerbalance:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEditor(row)}>配置</Button></Auth>
        <Auth permission="aid:providerbalance:query"><Button type="link" size="small" disabled={!moduleEnabled || !row.enabled} loading={checking === row.providerId} onClick={() => check(row)}>检查</Button></Auth>
        <Auth permission="aid:providerbalance:edit"><Button type="link" size="small" disabled={!row.simulatedEnabled} onClick={() => { setAdjusting(row); adjustForm.resetFields() }}>入账</Button></Auth>
      </Space>
    }
  ]

  return (
    <>
      <div className="panel-intro"><div><Title level={5}>选择需要监控的供应商</Title><Text type="secondary">未选择的供应商不会发起余额查询、不会匹配请求错误，也不会出现在日报中。</Text></div></div>
      <Table rowKey="providerId" dataSource={providers} columns={columns} loading={loading} scroll={{ x: 1450 }} pagination={false} />
      <Drawer title={editing ? `配置 · ${editing.providerName}` : '供应商配置'} width={640} open={!!editing} onClose={() => setEditing(undefined)} extra={<Button type="primary" loading={saving} onClick={save}>保存</Button>}>
        <Form form={form} layout="vertical">
          <Alert type="info" showIcon message="检测来源可组合开启；官方 API 失败时会自动使用模拟余额。接口查询异常只进入日报，不触发短信或微信。" className="form-alert" />
          <Row gutter={18}>
            <Col span={12}><Form.Item name="enabled" label="选择此供应商进行监控" valuePropName="checked"><Switch /></Form.Item></Col>
            <Col span={12}><Form.Item name="currency" label="余额单位" rules={[{ required: true }]}><Input placeholder="CNY / USD / CREDITS" /></Form.Item></Col>
            <Col span={8}><Form.Item name="apiEnabled" label="官方余额 API" valuePropName="checked"><Switch disabled={!editing?.apiSupported} /></Form.Item></Col>
            <Col span={8}><Form.Item name="simulatedEnabled" label="模拟余额" valuePropName="checked"><Switch /></Form.Item></Col>
            <Col span={8}><Form.Item name="errorRuleEnabled" label="错误规则触发" valuePropName="checked"><Switch /></Form.Item></Col>
          </Row>
          {!editing?.apiSupported && <Alert type="warning" showIcon message="该供应商没有已接入的官方余额接口，可使用模拟余额和明确的余额不足错误规则。" className="form-alert" />}
          <Card size="small" title="模拟余额基线" className="settings-section">
            <Row gutter={18}>
              <Col span={12}><Form.Item name="initialAmount" label="初始余额"><InputNumber min={0} precision={8} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={12}><Form.Item name="initialTime" label="初始余额生效时间"><DatePicker showTime style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={12}><Form.Item name="costUnitMultiplier" label="官方基础价 → 余额单位倍率" extra="CNY 余额填 1；若余额以积分计，填 1 元对应的积分数。"><InputNumber min={0.00000001} precision={8} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={12}><Form.Item name="forecastEnabled" label="预测余额可用天数" valuePropName="checked"><Switch /></Form.Item></Col>
              <Col span={12}><Form.Item name="forecastDays" label="预计可用天数阈值"><InputNumber min={1} max={90} style={{ width: '100%' }} /></Form.Item></Col>
            </Row>
          </Card>
          <Card size="small" title="阈值与抑制策略" className="settings-section">
            <Row gutter={18}>
              <Col span={8}><Form.Item name="criticalThreshold" label="严重阈值" rules={[{ required: true }]}><InputNumber min={0} precision={8} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="warningThreshold" label="预警阈值" rules={[{ required: true }]}><InputNumber min={0} precision={8} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="recoveryThreshold" label="恢复阈值" rules={[{ required: true }]}><InputNumber min={0} precision={8} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="confirmCount" label="连续命中次数"><InputNumber min={1} max={10} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="queryIntervalMinutes" label="查询间隔（分钟）"><InputNumber min={1} max={1440} style={{ width: '100%' }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="repeatIntervalMinutes" label="重复提醒（分钟）"><InputNumber min={5} max={10080} style={{ width: '100%' }} /></Form.Item></Col>
            </Row>
          </Card>
        </Form>
      </Drawer>
      <Modal title={`模拟余额入账 · ${adjusting?.providerName || ''}`} open={!!adjusting} confirmLoading={adjustSaving} onCancel={() => setAdjusting(undefined)} onOk={async () => {
        const values = await adjustForm.validateFields()
        setAdjustSaving(true)
        try {
          await addBalanceAdjustment(adjusting!.providerId, values)
          message.success('模拟余额调整已记入不可变台账')
          setAdjusting(undefined)
          onChanged()
        } finally { setAdjustSaving(false) }
      }}>
        <Form form={adjustForm} layout="vertical" initialValues={{ type: 'TOPUP' }}>
          <Form.Item name="type" label="入账类型"><Select options={[{ value: 'TOPUP', label: '充值/新增余额' }, { value: 'ADJUSTMENT', label: '人工调整（可填负数）' }]} /></Form.Item>
          <Form.Item name="amount" label="余额变化" rules={[{ required: true }]}><InputNumber precision={8} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="remark" label="原因"><Input.TextArea maxLength={200} showCount /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}

function RecipientPanel({ providers, recipients, overview, onChanged }: { providers: BalanceProvider[]; recipients: BalanceRecipient[]; overview?: BalanceOverview; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<BalanceRecipient>()
  const [qr, setQr] = useState<{ sceneStr: string; qrCodeUrl: string; expireSeconds: number }>()
  const [qrOpen, setQrOpen] = useState(false)
  const [qrBusy, setQrBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState<number>()
  const [form] = Form.useForm()
  const [qrForm] = Form.useForm()
  const channels = overview?.channels

  useEffect(() => {
    if (!qrOpen || !qr?.sceneStr) return
    let stopped = false
    const poll = async () => {
      try {
        const res: any = await getWechatRecipientQrStatus(qr.sceneStr)
        const status = res?.data?.status
        if (!stopped && typeof res?.data?.expireSeconds === 'number') {
          setQr((current) => current ? { ...current, expireSeconds: res.data.expireSeconds } : current)
        }
        if (!stopped && status === 'SUCCESS') {
          message.success('微信提醒人添加成功')
          setQrOpen(false)
          setQr(undefined)
          onChanged()
        } else if (!stopped && (status === 'FAIL' || status === 'EXPIRED')) {
          message.warning(res?.data?.message || '二维码已失效，请重新生成')
          setQr(undefined)
        }
      } catch { /* 轮询失败等待下一次，不重复弹窗 */ }
    }
    poll()
    const timer = window.setInterval(poll, 2000)
    return () => { stopped = true; window.clearInterval(timer) }
  }, [qrOpen, qr?.sceneStr, onChanged])

  const providerOptions = providers.map((p) => ({ value: p.providerId, label: p.providerName }))
  const openAdd = () => { setEditing(undefined); form.resetFields(); form.setFieldsValue({ enabled: true, dailyReportEnabled: false }); setOpen(true) }
  const openEdit = (row: BalanceRecipient) => {
    setEditing(row)
    form.setFieldsValue({ ...row, targetValue: '' })
    setOpen(true)
  }
  const save = async () => {
    const values = await form.validateFields()
    const payload = { ...values, id: editing?.id, enabled: values.enabled ? 1 : 0, dailyReportEnabled: values.dailyReportEnabled ? 1 : 0 }
    setSaving(true)
    try {
      if (editing) await updateBalanceRecipient(payload); else await addBalanceRecipient(payload)
      message.success('提醒人已保存')
      setOpen(false)
      onChanged()
    } finally { setSaving(false) }
  }
  const createQr = async () => {
    const values = await qrForm.validateFields()
    setQrBusy(true)
    try {
      const res: any = await createWechatRecipientQr(values)
      setQr(res?.data)
    } finally { setQrBusy(false) }
  }

  const columns: any[] = [
    { title: '提醒人', dataIndex: 'recipientName', width: 160, render: (v: string, row: BalanceRecipient) => <div><strong>{v}</strong>{row.wechatNickname && <div><Text type="secondary">微信昵称：{row.wechatNickname}</Text></div>}</div> },
    { title: '渠道', dataIndex: 'channel', width: 130, render: (v: string) => <Tag color={CHANNEL_META[v]?.color} icon={CHANNEL_META[v]?.icon}>{CHANNEL_META[v]?.label || v}</Tag> },
    { title: '接收地址', dataIndex: 'displayValue', width: 210 },
    { title: '供应商', dataIndex: 'providerIds', render: (ids: number[]) => <Space wrap>{ids.map((id) => <Tag key={id}>{providers.find((p) => p.providerId === id)?.providerName || `#${id}`}</Tag>)}</Space> },
    { title: '余额日报', width: 100, render: (_: any, row: BalanceRecipient) => row.channel === 'EMAIL' ? <Tag color={row.dailyReportEnabled ? 'blue' : 'default'}>{row.dailyReportEnabled ? '接收' : '不接收'}</Tag> : '-' },
    { title: '状态', width: 90, render: (_: any, row: BalanceRecipient) => <Tag color={row.enabled ? 'success' : 'default'}>{row.enabled ? '有效' : '停用'}</Tag> },
    {
      title: '操作', width: 180, fixed: 'right', render: (_: any, row: BalanceRecipient) => <Space size={2}>
        {row.channel !== 'WECHAT' && <Auth permission="aid:providerbalance:edit"><Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button></Auth>}
        <Auth permission="aid:providerbalance:test"><Button size="small" type="link" icon={<SendOutlined />} loading={testing === row.id} disabled={testing != null && testing !== row.id} onClick={async () => {
          setTesting(row.id)
          try { await testBalanceRecipient(row.id); message.success('测试消息发送成功') } finally { setTesting(undefined) }
        }}>测试</Button></Auth>
        <Auth permission="aid:providerbalance:edit"><Popconfirm title="确认删除该提醒人？" onConfirm={async () => { await deleteBalanceRecipient(row.id); message.success('已删除'); onChanged() }}><Button size="small" type="link" danger icon={<DeleteOutlined />} /></Popconfirm></Auth>
      </Space>
    }
  ]

  return (
    <>
      <div className="channel-grid">
        {Object.entries(CHANNEL_META).map(([key, meta]) => {
          const cap = channels?.[key as 'EMAIL' | 'SMS' | 'WECHAT']
          return <div className={`channel-card ${cap?.enabled && cap.templateReady ? 'is-ready' : 'is-disabled'}`} key={key}><span className="channel-icon" style={{ color: meta.color }}>{meta.icon}</span><div><strong>{meta.label}</strong><div><Text type={cap?.enabled && cap.templateReady ? 'success' : 'secondary'}>{cap?.enabled ? (cap.templateReady ? '通道与模板已就绪' : '通道已开启，模板待配置') : cap?.disabledReason || '未开启'}</Text></div></div></div>
        })}
      </div>
      <div className="panel-intro">
        <div><Title level={5}>提醒人按渠道独立配置</Title><Text type="secondary">同一供应商可配置多个提醒人；组合渠道会逐个发送，单个失败不会影响其他渠道。</Text></div>
        <Auth permission="aid:providerbalance:edit"><Space><Button icon={<WechatOutlined />} disabled={!channels?.WECHAT.enabled || providers.length === 0} onClick={() => { setQrOpen(true); setQr(undefined); qrForm.resetFields() }}>微信扫码添加</Button><Button type="primary" icon={<PlusOutlined />} disabled={providers.length === 0 || (!channels?.EMAIL.enabled && !channels?.SMS.enabled)} onClick={openAdd}>添加邮箱/手机</Button></Space></Auth>
      </div>
      <Table rowKey="id" dataSource={recipients} columns={columns} scroll={{ x: 1150 }} pagination={false} locale={{ emptyText: <Empty description="还没有提醒人" /> }} />
      <Modal title={editing ? '编辑提醒人' : '添加提醒人'} open={open} confirmLoading={saving} onCancel={() => setOpen(false)} onOk={save}>
        <Form form={form} layout="vertical">
          <Form.Item name="recipientName" label="提醒人名称" rules={[{ required: true }]}><Input maxLength={64} /></Form.Item>
          <Form.Item name="channel" label="通知方式" rules={[{ required: true }]}><Select disabled={!!editing} options={[
            { value: 'EMAIL', label: '邮箱', disabled: !channels?.EMAIL.enabled },
            { value: 'SMS', label: '短信', disabled: !channels?.SMS.enabled }
          ]} /></Form.Item>
          <Form.Item noStyle shouldUpdate={(a, b) => a.channel !== b.channel}>{({ getFieldValue }) => <Form.Item name="targetValue" label={getFieldValue('channel') === 'SMS' ? '手机号' : '邮箱地址'} extra={editing ? '留空表示保留原接收地址' : undefined} rules={editing ? [] : [{ required: true }]}><Input /></Form.Item>}</Form.Item>
          <Form.Item name="providerIds" label="接收哪些供应商的提醒" rules={[{ required: true, type: 'array', min: 1 }]}><Select mode="multiple" options={providerOptions} /></Form.Item>
          <Row gutter={18}><Col span={12}><Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item></Col><Col span={12}><Form.Item noStyle shouldUpdate={(a, b) => a.channel !== b.channel}>{({ getFieldValue }) => getFieldValue('channel') === 'EMAIL' && <Form.Item name="dailyReportEnabled" label="接收每日余额详情" valuePropName="checked"><Switch /></Form.Item>}</Form.Item></Col></Row>
        </Form>
      </Modal>
      <Modal title="微信扫码添加提醒人" open={qrOpen} footer={null} onCancel={() => { setQrOpen(false); setQr(undefined) }}>
        {!qr ? <Form form={qrForm} layout="vertical"><Alert type="info" showIcon message="二维码 5 分钟有效。扫码只会添加后台提醒人，不会创建或绑定前台用户账号。" className="form-alert" /><Form.Item name="recipientName" label="提醒人名称"><Input placeholder="例如：财务值班" /></Form.Item><Form.Item name="providerIds" label="接收哪些供应商的提醒" rules={[{ required: true, type: 'array', min: 1 }]}><Select mode="multiple" options={providerOptions} /></Form.Item><Button block type="primary" loading={qrBusy} onClick={createQr}>生成二维码</Button></Form>
          : <div className="wechat-qr"><Image width={240} preview={false} src={qr.qrCodeUrl} /><Title level={5}>请使用微信扫码</Title><Text type="secondary"><ClockCircleOutlined /> 二维码约 {qr.expireSeconds} 秒后失效，扫码后本页会自动完成。</Text></div>}
      </Modal>
    </>
  )
}

function IncidentPanel({ providers }: { providers: BalanceProvider[] }) {
  const [incidents, setIncidents] = useState<any[]>([])
  const [deliveries, setDeliveries] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [subTab, setSubTab] = useState('incidents')
  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [i, d]: any[] = await Promise.all([
        listBalanceIncidents({ pageNum: 1, pageSize: 100 }),
        listBalanceDeliveries({ pageNum: 1, pageSize: 100 })
      ])
      setIncidents(i?.data?.items || [])
      setDeliveries(d?.data?.items || [])
    } finally { setLoading(false) }
  }, [])
  useEffect(() => { load() }, [load])
  const providerName = (id: number) => providers.find((p) => p.providerId === id)?.providerName || `#${id}`
  return <Tabs activeKey={subTab} onChange={setSubTab} tabBarExtraContent={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>} items={[
    { key: 'incidents', label: '告警事件', children: <Table rowKey="id" loading={loading} dataSource={incidents} pagination={false} columns={[
      { title: '供应商', dataIndex: 'providerId', render: providerName },
      { title: '级别', dataIndex: 'severity', render: (v: string) => statusTag(v) },
      { title: '触发来源', dataIndex: 'triggerSource' },
      { title: '余额 / 阈值', render: (_: any, row: any) => `${row.balance ?? '错误规则'} / ${row.thresholdAmount ?? '-'}` },
      { title: '状态', dataIndex: 'status', render: (v: string) => <Tag color={v === 'RESOLVED' ? 'success' : v === 'ACKED' ? 'processing' : 'error'}>{v}</Tag> },
      { title: '首次发生', dataIndex: 'openedAt', width: 170 },
      { title: '下次提醒', dataIndex: 'nextNotifyAt', width: 170, render: (v: string) => v || '-' },
      { title: '操作', width: 120, render: (_: any, row: any) => row.status !== 'RESOLVED' && <Auth permission="aid:providerbalance:edit"><Button type="link" size="small" onClick={() => Modal.confirm({ title: '确认并静默告警', content: '确认后静默 60 分钟，余额恢复后事件仍会自动关闭。', onOk: async () => { await acknowledgeBalanceIncident(row.id, 60); message.success('已确认并静默 60 分钟'); load() } })}>确认/静默</Button></Auth> }
    ] as any} /> },
    { key: 'deliveries', label: '发送记录', children: <Table rowKey="id" loading={loading} dataSource={deliveries} pagination={false} columns={[
      { title: '供应商', dataIndex: 'providerId', render: (v: number) => v ? providerName(v) : '余额日报/测试' },
      { title: '类型', dataIndex: 'deliveryType' },
      { title: '渠道', dataIndex: 'channel', render: (v: string) => <Tag>{CHANNEL_META[v]?.label || v}</Tag> },
      { title: '提醒人 ID', dataIndex: 'recipientId' },
      { title: '结果', dataIndex: 'status', render: (v: string) => <Tag color={v === 'SUCCESS' ? 'success' : 'error'}>{v === 'SUCCESS' ? '成功' : '失败'}</Tag> },
      { title: '失败原因', dataIndex: 'errorMessage', ellipsis: true },
      { title: '发送时间', dataIndex: 'attemptedAt', width: 170 }
    ] as any} /> }
  ]} />
}

function BalanceRulePanel({ providers }: { providers: BalanceProvider[] }) {
  const [rules, setRules] = useState<ProviderErrorRule[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProviderErrorRule>()
  const [saving, setSaving] = useState(false)
  const [toggling, setToggling] = useState<number>()
  const [form] = Form.useForm()
  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res: any = await listBalanceRules({ pageNum: 1, pageSize: 200 })
      setRules(res?.data?.items || [])
    } finally { setLoading(false) }
  }, [])
  useEffect(() => { load() }, [load])
  const edit = (row?: ProviderErrorRule) => {
    setEditing(row)
    form.setFieldsValue(row || { matchType: 'KEYWORD', priority: 20, enabled: 1, caseSensitive: 0 })
    setOpen(true)
  }
  const save = async () => {
    const values = await form.validateFields()
    const payload: ProviderErrorRule = { ...editing, ...values, errorCode: 'PROVIDER_BALANCE_INSUFFICIENT', isBuiltin: editing?.isBuiltin || 0 }
    setSaving(true)
    try {
      if (editing?.id) await updateBalanceRule(payload); else await addBalanceRule(payload)
      message.success('余额不足规则已保存')
      setOpen(false)
      load()
    } finally { setSaving(false) }
  }
  return <>
    <Alert type="info" showIcon message="只有映射到 PROVIDER_BALANCE_INSUFFICIENT 的明确余额不足错误才触发提醒。限流、并发额度和通用 quota 错误不会误报。" className="form-alert" />
    <div className="panel-intro"><div><Title level={5}>供应商余额不足错误匹配</Title><Text type="secondary">不同供应商的错误码与文案可在这里独立维护。</Text></div><Auth permission="aid:providerbalance:edit"><Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>新增余额规则</Button></Auth></div>
    <Table rowKey="id" loading={loading} dataSource={rules} pagination={false} columns={[
      { title: '规则名称', dataIndex: 'ruleName' },
      { title: '供应商', dataIndex: 'providerCode', render: (v: string) => providers.find((p) => p.providerCode === v)?.providerName || v || '全局' },
      { title: '匹配方式', dataIndex: 'matchType', width: 130 },
      { title: '匹配内容', dataIndex: 'matchPattern', ellipsis: true },
      { title: '优先级', dataIndex: 'priority', width: 90 },
      { title: '启用', dataIndex: 'enabled', width: 80, render: (v: number, row: ProviderErrorRule) => <Auth permission="aid:providerbalance:edit"><Switch size="small" checked={v === 1} loading={toggling === row.id} disabled={toggling != null && toggling !== row.id} onChange={async (checked) => { setToggling(row.id); try { await toggleBalanceRule(row.id!, checked ? 1 : 0); load() } finally { setToggling(undefined) } }} /></Auth> },
      { title: '操作', width: 140, render: (_: any, row: ProviderErrorRule) => <Auth permission="aid:providerbalance:edit"><Space><Button type="link" size="small" onClick={() => edit(row)}>编辑</Button><Popconfirm title="确认删除？" disabled={row.isBuiltin === 1} onConfirm={async () => { await deleteBalanceRule(row.id!); load() }}><Button type="link" size="small" danger disabled={row.isBuiltin === 1}>删除</Button></Popconfirm></Space></Auth> }
    ] as any} />
    <Modal title={editing?.id ? '编辑余额不足规则' : '新增余额不足规则'} open={open} confirmLoading={saving} onCancel={() => setOpen(false)} onOk={save}>
      <Form form={form} layout="vertical">
        <Form.Item name="ruleName" label="规则名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="providerCode" label="供应商" rules={[{ required: true }]}><Select showSearch options={providers.map((p) => ({ value: p.providerCode, label: `${p.providerName}（${p.providerCode}）` }))} /></Form.Item>
        <Row gutter={18}><Col span={10}><Form.Item name="matchType" label="匹配方式" rules={[{ required: true }]}><Select options={['HTTP_STATUS', 'CODE', 'KEYWORD', 'REGEX', 'JSON_PATH'].map((v) => ({ value: v, label: v }))} /></Form.Item></Col><Col span={14}><Form.Item name="matchPattern" label="匹配内容" rules={[{ required: true }]}><Input placeholder="多个关键字可用逗号分隔" /></Form.Item></Col></Row>
        <Form.Item noStyle shouldUpdate={(a, b) => a.matchType !== b.matchType}>{({ getFieldValue }) => getFieldValue('matchType') === 'JSON_PATH' && <Form.Item name="matchField" label="JSON 路径" rules={[{ required: true }]}><Input placeholder="$.error.code" /></Form.Item>}</Form.Item>
        <Row gutter={18}><Col span={12}><Form.Item name="priority" label="优先级"><InputNumber min={1} max={9999} style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><Form.Item name="enabled" label="启用"><Select options={[{ value: 1, label: '启用' }, { value: 0, label: '停用' }]} /></Form.Item></Col></Row>
        <Form.Item name="remark" label="备注"><Input.TextArea maxLength={500} showCount /></Form.Item>
      </Form>
    </Modal>
  </>
}
