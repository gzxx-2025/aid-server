import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert, Badge, Button, Card, Checkbox, Col, Descriptions, Drawer, Empty, Form, Input,
  InputNumber, List, Modal, Popconfirm, Row, Select, Space, Statistic, Switch, Table,
  Tabs, Tag, Tooltip, Typography, message
} from 'antd'
import {
  ApiOutlined, CheckCircleOutlined, ClockCircleOutlined, CloudUploadOutlined, CopyOutlined,
  DeleteOutlined, DownloadOutlined, EditOutlined, ExclamationCircleOutlined, ExportOutlined,
  FileSearchOutlined, FileTextOutlined, GlobalOutlined, HistoryOutlined, LinkOutlined,
  PlusOutlined, ReloadOutlined, RobotOutlined, SafetyCertificateOutlined, SaveOutlined,
  SearchOutlined, SettingOutlined, SyncOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import Auth from '@/components/Auth'
import {
  addSeoPage, archiveSeoPage, confirmManualSeoPages, getSeoOverview, getSeoSettings,
  listSeoLogs, listSeoPages, saveSeoSettings, scanSeoPages, SeoLog, SeoOverview, SeoPage,
  SeoPageQuery, SeoPageSave, SeoSettings, SeoSettingsSave, submitSeoPages, updateSeoPage
} from '@/api/aid/seo'
import './style.less'

const { Link, Paragraph, Text, Title } = Typography
const BAIDU_MANUAL_URL = 'https://ziyuan.baidu.com/linksubmit/url'
const BAIDU_SITEMAP_URL = 'https://ziyuan.baidu.com/linksubmit/index'

const STATUS_META: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待提交', color: 'processing' },
  PROCESSING: { label: '提交中', color: 'processing' },
  ACCEPTED: { label: '接口已接收', color: 'success' },
  RETRY: { label: '等待重试', color: 'warning' },
  INVALID: { label: '链接无效', color: 'error' },
  BLOCKED: { label: '配置阻断', color: 'error' }
}

function resultData<T>(response: any, fallback: T): T {
  return response?.data ?? fallback
}

function statusTag(status?: string) {
  if (!status) return <Tag>未提交</Tag>
  const meta = STATUS_META[status] || { label: status, color: 'default' }
  return <Tag color={meta.color}>{meta.label}</Tag>
}

async function copyText(value?: string, success = '已复制') {
  if (!value) return
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(value)
  } else {
    const input = document.createElement('textarea')
    input.value = value
    input.style.position = 'fixed'
    input.style.opacity = '0'
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    input.remove()
  }
  message.success(success)
}

function openExternal(url?: string) {
  if (url) window.open(url, '_blank', 'noopener,noreferrer')
}

function downloadUrls(urls: string[]) {
  const blob = new Blob([`${urls.join('\n')}\n`], { type: 'text/plain;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = 'urls.txt'
  anchor.click()
  URL.revokeObjectURL(href)
}

export default function SeoManagementPage() {
  const [overview, setOverview] = useState<SeoOverview>()
  const [settings, setSettings] = useState<SeoSettings>()
  const [rows, setRows] = useState<SeoPage[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<number[]>([])
  const [query, setQuery] = useState<SeoPageQuery>({ pageNum: 1, pageSize: 20 })
  const [editor, setEditor] = useState<SeoPage | 'new'>()
  const [logPage, setLogPage] = useState<SeoPage>()
  const refreshRef = useRef<Promise<void> | null>(null)

  const refresh = useCallback((force = false) => {
    if (refreshRef.current) return refreshRef.current
    setLoading(true)
    const run = Promise.all([
      getSeoOverview(force), getSeoSettings(force), listSeoPages(query, force)
    ]).then(([overviewResponse, settingsResponse, pageResponse]: any[]) => {
      setOverview(resultData(overviewResponse, undefined))
      setSettings(resultData(settingsResponse, undefined))
      const page = resultData(pageResponse, { total: 0, items: [] })
      setRows(page.items || [])
      setTotal(page.total || 0)
    }).finally(() => {
      refreshRef.current = null
      setLoading(false)
    })
    refreshRef.current = run
    return run
  }, [query])

  useEffect(() => { refresh() }, [refresh])

  const runScan = async () => {
    setLoading(true)
    try {
      const response: any = await scanSeoPages()
      message.success(`扫描完成，发现 ${resultData(response, 0)} 个页面`)
      await refresh(true)
    } finally { setLoading(false) }
  }

  const submit = async (ids: number[] = selected) => {
    setLoading(true)
    try {
      const response: any = await submitSeoPages(ids)
      const result = resultData(response, undefined as any)
      if (result) {
        message.success(result.message || `已处理 ${result.selected || 0} 条链接`)
      }
      setSelected([])
      await refresh(true)
    } finally { setLoading(false) }
  }

  const columns: any[] = [
    {
      title: '页面与完整链接', dataIndex: 'pageTitle', width: 430, fixed: 'left',
      render: (_: string, row: SeoPage) => (
        <div className="seo-page-cell">
          <Space size={6} wrap>
            <Text strong>{row.pageTitle}</Text>
            <Tag>{row.sourceType}</Tag>
            {!row.indexable && <Tag color="default">禁止索引</Tag>}
          </Space>
          <div className="seo-full-url">
            <Tooltip title={row.canonicalUrl}><Link ellipsis onClick={() => openExternal(row.canonicalUrl)}>{row.canonicalUrl}</Link></Tooltip>
            <Tooltip title="复制完整链接"><Button type="text" size="small" icon={<CopyOutlined />} onClick={() => copyText(row.canonicalUrl, '完整链接已复制')} /></Tooltip>
          </div>
        </div>
      )
    },
    {
      title: '页面关键词', dataIndex: 'metaKeywords', width: 250,
      render: (value?: string) => value ? <div className="keyword-wrap">{value.split(',').slice(0, 5).map((item) => <Tag key={item}>{item}</Tag>)}</div> : <Text type="secondary">自动生成</Text>
    },
    {
      title: '百度 API', dataIndex: 'apiStatus', width: 125,
      render: (value: string | undefined, row: SeoPage) => <Tooltip title={row.lastErrorMessage}>{statusTag(value)}</Tooltip>
    },
    {
      title: '最近处理', dataIndex: 'lastAttemptTime', width: 170,
      render: (value?: string) => value || <Text type="secondary">—</Text>
    },
    {
      title: '操作', width: 180, fixed: 'right',
      render: (_: unknown, row: SeoPage) => (
        <Space size={2}>
          <Auth permission="aid:seo:edit"><Button type="link" icon={<EditOutlined />} onClick={() => setEditor(row)}>编辑</Button></Auth>
          <Auth permission="aid:seo:query"><Button type="link" icon={<HistoryOutlined />} onClick={() => setLogPage(row)}>记录</Button></Auth>
          <Auth permission="aid:seo:edit">
            <Popconfirm title="停用这个页面？" description="停用后不再进入 Sitemap 或提交队列。" onConfirm={async () => { await archiveSeoPage(row.id); message.success('页面已停用'); refresh(true) }}>
              <Button type="link" danger icon={<DeleteOutlined />}>停用</Button>
            </Popconfirm>
          </Auth>
        </Space>
      )
    }
  ]

  const selectedRows = useMemo(() => rows.filter((row) => selected.includes(row.id)), [rows, selected])

  return (
    <div className="seo-management-page">
      <PageHeader
        title={<><SearchOutlined />SEO 管理中心</>}
        desc="集中管理搜索引擎可发现页面、抓取规则、站点地图和提交记录"
        extra={<Space wrap>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => refresh(true)}>刷新</Button>
          <Auth permission="aid:seo:edit"><Button icon={<SyncOutlined />} loading={loading} onClick={runScan}>立即扫描</Button></Auth>
          <Auth permission="aid:seo:submit"><Button type="primary" icon={<CloudUploadOutlined />} loading={loading} onClick={() => submit([])}>提交待处理队列</Button></Auth>
        </Space>}
      />

      {!settings?.siteUrl && (
        <Alert className="seo-alert" type="warning" showIcon message="请先完成站点基础配置" description="配置公开网站地址后，系统才会生成规范链接、robots.txt、sitemap.xml 和百度待提交队列。" />
      )}

      <Row gutter={[14, 14]} className="seo-stats">
        <Col xs={12} xl={4}><Card bordered={false}><Statistic title="页面总数" value={overview?.totalPages || 0} prefix={<FileTextOutlined />} /></Card></Col>
        <Col xs={12} xl={5}><Card bordered={false}><Statistic title="允许索引" value={overview?.indexablePages || 0} prefix={<GlobalOutlined />} /></Card></Col>
        <Col xs={12} xl={5}><Card bordered={false}><Statistic title="待提交" value={overview?.pendingPages || 0} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#1677ff' }} /></Card></Col>
        <Col xs={12} xl={5}><Card bordered={false}><Statistic title="接口已接收" value={overview?.acceptedPages || 0} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#389e0d' }} /></Card></Col>
        <Col xs={24} xl={5}><Card bordered={false}><Statistic title="待处理异常" value={overview?.retryPages || 0} prefix={<ExclamationCircleOutlined />} valueStyle={{ color: overview?.retryPages ? '#d46b08' : undefined }} /></Card></Col>
      </Row>

      <div className="seo-run-status">
        <Space size={[20, 8]} wrap>
          <Badge status={overview?.baiduReady ? 'success' : 'default'} text={overview?.baiduReady ? '百度定时推送已就绪' : '百度定时推送等待配置'} />
          <Text type="secondary">计划：每天 02:20、14:20</Text>
          <Text type="secondary">最近扫描：{overview?.lastScanTime || '尚未执行'}</Text>
          <Text type="secondary">最近提交：{overview?.lastSubmitTime || '尚未执行'}</Text>
          <Text type="secondary">百度剩余额度：{overview?.providerRemain ?? '未知'}</Text>
        </Space>
      </div>

      <Card bordered={false} className="seo-main-card">
        <Tabs items={[
          {
            key: 'inventory', label: <span><FileSearchOutlined />页面清单</span>,
            children: <>
              <div className="seo-toolbar">
                <Space wrap>
                  <Input.Search allowClear placeholder="搜索标题、URL 或关键词" style={{ width: 310 }} onSearch={(keyword) => setQuery((old) => ({ ...old, keyword, pageNum: 1 }))} />
                  <Select allowClear placeholder="提交状态" style={{ width: 140 }} onChange={(submitStatus) => setQuery((old) => ({ ...old, submitStatus, pageNum: 1 }))} options={Object.entries(STATUS_META).map(([value, item]) => ({ value, label: item.label }))} />
                  <Select allowClear placeholder="索引状态" style={{ width: 130 }} onChange={(indexable) => setQuery((old) => ({ ...old, indexable, pageNum: 1 }))} options={[{ value: true, label: '允许索引' }, { value: false, label: '禁止索引' }]} />
                </Space>
                <Space wrap>
                  {!!selected.length && <Text type="secondary">已选 {selected.length} 条</Text>}
                  <Auth permission="aid:seo:submit"><Button disabled={!selected.length} icon={<CloudUploadOutlined />} onClick={() => submit()}>重试所选</Button></Auth>
                  <Auth permission="aid:seo:edit"><Button type="primary" icon={<PlusOutlined />} onClick={() => setEditor('new')}>添加页面</Button></Auth>
                </Space>
              </div>
              <Table
                rowKey="id" loading={loading} columns={columns} dataSource={rows} scroll={{ x: 1180 }}
                rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(Number)) }}
                pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (value) => `共 ${value} 个页面`, onChange: (pageNum, pageSize) => setQuery((old) => ({ ...old, pageNum, pageSize })) }}
              />
            </>
          },
          {
            key: 'manual', label: <span><LinkOutlined />手动提交</span>,
            children: <ManualSubmissionPanel initialRows={selectedRows} onChanged={() => refresh(true)} />
          },
          {
            key: 'crawler', label: <span><RobotOutlined />Sitemap 与 Robots</span>,
            children: <CrawlerPanel settings={settings} />
          },
          {
            key: 'settings', label: <span><SettingOutlined />搜索展示与推送配置</span>,
            children: settings ? <SettingsPanel settings={settings} onSaved={() => refresh(true)} /> : <Empty />
          }
        ]} />
      </Card>

      <PageEditor value={editor} onClose={() => setEditor(undefined)} onSaved={() => { setEditor(undefined); refresh(true) }} />
      <LogDrawer page={logPage} onClose={() => setLogPage(undefined)} />
    </div>
  )
}

function ManualSubmissionPanel({ initialRows, onChanged }: { initialRows: SeoPage[]; onChanged: () => void }) {
  const [rows, setRows] = useState<SeoPage[]>(initialRows.slice(0, 20))
  const [selected, setSelected] = useState<number[]>(initialRows.slice(0, 20).map((item) => item.id))
  const [loading, setLoading] = useState(false)

  const loadUnsubmitted = useCallback(async () => {
    setLoading(true)
    try {
      const response: any = await listSeoPages({ pageNum: 1, pageSize: 20, onlyUnsubmitted: true, indexable: true }, true)
      const page = resultData(response, { items: [] } as any)
      setRows(page.items || [])
      setSelected((page.items || []).map((item: SeoPage) => item.id))
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    if (initialRows.length) {
      const next = initialRows.slice(0, 20)
      setRows(next)
      setSelected(next.map((item) => item.id))
    } else {
      loadUnsubmitted()
    }
  }, [initialRows, loadUnsubmitted])

  const urls = rows.filter((row) => selected.includes(row.id)).map((row) => row.canonicalUrl).slice(0, 20)
  const confirm = async () => {
    if (!selected.length) return
    setLoading(true)
    try {
      const response: any = await confirmManualSeoPages(selected.slice(0, 20))
      message.success(`已确认 ${resultData(response, 0)} 条手动提交记录`)
      setSelected([])
      await loadUnsubmitted()
      onChanged()
    } finally { setLoading(false) }
  }

  return (
    <div className="manual-panel">
      <Alert type="info" showIcon message="这里展示尚未被任何渠道确认提交的有效完整链接" description="百度手动提交每次最多 20 条。先复制或下载链接并在百度站长平台提交，完成后再回到这里确认；确认只代表已操作，不代表百度已经收录。" />
      <div className="manual-actions">
        <Space wrap>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={loadUnsubmitted}>换一批未提交链接</Button>
          <Button icon={<CopyOutlined />} disabled={!urls.length} onClick={() => copyText(urls.join('\n'), `已复制 ${urls.length} 条完整链接`)}>复制所选</Button>
          <Button icon={<DownloadOutlined />} disabled={!urls.length} onClick={() => downloadUrls(urls)}>下载 urls.txt</Button>
          <Button type="primary" icon={<ExportOutlined />} onClick={() => openExternal(BAIDU_MANUAL_URL)}>打开百度手动提交</Button>
        </Space>
        <Auth permission="aid:seo:submit"><Button icon={<CheckCircleOutlined />} disabled={!selected.length} onClick={confirm}>确认已手动提交</Button></Auth>
      </div>
      <List
        bordered loading={loading} dataSource={rows}
        locale={{ emptyText: '目前没有待手动提交的页面' }}
        renderItem={(row) => <List.Item className="manual-url-item" actions={[<Button key="copy" type="text" icon={<CopyOutlined />} onClick={() => copyText(row.canonicalUrl)} />, <Button key="open" type="text" icon={<ExportOutlined />} onClick={() => openExternal(row.canonicalUrl)} />]}>
          <Checkbox checked={selected.includes(row.id)} onChange={(event) => setSelected((old) => event.target.checked ? [...old, row.id].slice(0, 20) : old.filter((id) => id !== row.id))}>
            <div><Text strong>{row.pageTitle}</Text><Paragraph copyable={false} ellipsis={{ rows: 1, tooltip: row.canonicalUrl }} className="manual-url-text">{row.canonicalUrl}</Paragraph></div>
          </Checkbox>
        </List.Item>}
      />
    </div>
  )
}

function CrawlerPanel({ settings }: { settings?: SeoSettings }) {
  return (
    <Row gutter={[18, 18]}>
      <Col xs={24} xl={12}>
        <Card className="crawler-card" title={<><FileTextOutlined /> robots.txt</>} extra={<Badge status={settings?.siteUrl ? 'success' : 'default'} text={settings?.siteUrl ? '动态生成' : '待配置'} />}>
          <Text type="secondary">控制蜘蛛允许和禁止抓取的目录；站点地图地址会自动附加。</Text>
          <Input.TextArea className="code-preview" readOnly autoSize={{ minRows: 10, maxRows: 16 }} value={settings?.robotsPreview || '请先配置网站地址'} />
          <Space wrap><Button icon={<CopyOutlined />} onClick={() => copyText(settings?.robotsUrl)}>复制访问地址</Button><Button icon={<ExportOutlined />} disabled={!settings?.robotsUrl} onClick={() => openExternal(settings?.robotsUrl)}>在线查看</Button></Space>
        </Card>
      </Col>
      <Col xs={24} xl={12}>
        <Card className="crawler-card" title={<><GlobalOutlined /> sitemap.xml</>} extra={<Tag color="blue">最多 50,000 条 / 小于 10MB</Tag>}>
          <Text type="secondary">系统仅输出允许索引且启用 Sitemap 的规范链接，可直接提交给主流搜索引擎。</Text>
          <div className="sitemap-address">
            <Text type="secondary">数据文件地址</Text>
            <Title level={5} copyable={{ text: settings?.sitemapUrl }}>{settings?.sitemapUrl || '请先配置网站地址'}</Title>
          </div>
          <Alert type="warning" showIcon message="百度站点地图需在百度站长平台中手动登记" description="本站不自动操作站长平台账号；复制上方完整 XML 地址后，在 Sitemap 页面提交即可。" />
          <Space wrap><Button icon={<CopyOutlined />} onClick={() => copyText(settings?.sitemapUrl)}>复制 Sitemap 地址</Button><Button icon={<ExportOutlined />} disabled={!settings?.sitemapUrl} onClick={() => openExternal(settings?.sitemapUrl)}>在线查看</Button><Button type="primary" icon={<CloudUploadOutlined />} onClick={() => openExternal(BAIDU_SITEMAP_URL)}>打开百度 Sitemap 提交</Button></Space>
        </Card>
      </Col>
    </Row>
  )
}

function SettingsPanel({ settings, onSaved }: { settings: SeoSettings; onSaved: () => void }) {
  const [form] = Form.useForm<SeoSettingsSave>()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    form.setFieldsValue({ ...settings, baiduToken: '', clearBaiduToken: false })
  }, [form, settings])

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await saveSeoSettings(values)
      message.success('SEO 配置已保存，完整链接和抓取文件已刷新')
      form.setFieldValue('baiduToken', '')
      onSaved()
    } finally { setSaving(false) }
  }

  return (
    <Form form={form} layout="vertical" onFinish={save} className="seo-settings-form">
      <Row gutter={[20, 20]}>
        <Col xs={24} xl={12}>
          <Card title={<><GlobalOutlined /> 站点与默认搜索展示</>} className="settings-card">
            <Form.Item name="siteUrl" label="公开网站地址" extra="只填写协议和域名，例如 https://www.example.com" rules={[{ required: true, message: '请填写公开网站地址' }, { type: 'url', message: '请输入完整 URL' }]}><Input placeholder="https://www.example.com" /></Form.Item>
            <Row gutter={14}>
              <Col span={12}><Form.Item name="siteName" label="站点名称" rules={[{ required: true }]}><Input maxLength={80} /></Form.Item></Col>
              <Col span={12}><Form.Item name="titleSuffix" label="页面标题后缀"><Input maxLength={80} placeholder="例如 AID Studio" /></Form.Item></Col>
            </Row>
            <Form.Item name="defaultDescription" label="默认描述" extra="页面未单独维护描述时使用，建议 80–160 个字符"><Input.TextArea rows={3} maxLength={300} showCount /></Form.Item>
            <Form.Item name="defaultKeywords" label="默认关键词" extra="使用逗号分隔；每个页面还会结合自身标题自动生成关键词"><Input maxLength={500} placeholder="AI视频,创作工具,分镜" /></Form.Item>
            <Form.Item name="robotsDisallow" label="禁止抓取路径" extra="每行一个站内路径，系统会校验并生成 robots.txt"><Input.TextArea className="code-input" rows={7} placeholder={'/admin\n/login\n/user'} /></Form.Item>
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title={<><ApiOutlined /> 百度普通收录 API</>} className="settings-card" extra={<Tag color={settings.baiduTokenConfigured ? 'success' : 'default'}>{settings.baiduTokenConfigured ? '密钥已配置' : '密钥未配置'}</Tag>}>
            <Alert className="settings-tip" type="info" showIcon message="提交与内容发布完全解耦" description="固定任务每日 02:20 和 14:20 扫描并提交；失败只写入 SEO 日志和重试队列，不会把作品或业务内容标记为失败。" />
            <Form.Item name="baiduEnabled" label="启用百度 API 定时提交" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用" /></Form.Item>
            <Form.Item name="baiduSite" label="百度验证站点" extra="填写与公开网站同源的完整地址；提交时系统会自动转换为百度要求的主机名参数" rules={[{ type: 'url', message: '请输入完整 URL' }]}><Input placeholder="https://www.example.com" /></Form.Item>
            <Form.Item name="baiduToken" label={settings.baiduTokenConfigured ? '更新准入密钥（留空保持不变）' : '准入密钥'} extra="密钥保存后加密存储，页面和接口均不会再次显示明文"><Input.Password autoComplete="new-password" maxLength={128} placeholder={settings.baiduTokenConfigured ? '留空保持现有密钥' : '填写百度站长平台提供的 Token'} /></Form.Item>
            {settings.baiduTokenConfigured && <Form.Item name="clearBaiduToken" valuePropName="checked"><Checkbox>清空已保存的百度准入密钥</Checkbox></Form.Item>}
            <Form.Item name="submitBatchSize" label="每批提交上限" rules={[{ required: true }]}><InputNumber min={1} max={2000} style={{ width: '100%' }} /></Form.Item>
            <Descriptions column={1} size="small" className="schedule-description" items={[
              { key: 'schedule', label: '定时计划', children: '每天 02:20、14:20' },
              { key: 'protocol', label: '推送协议', children: 'POST · Content-Type: text/plain · 每行一个完整 URL' },
              { key: 'state', label: '当前状态', children: <Tag color={settings.baiduEnabled && settings.baiduTokenConfigured ? 'success' : 'default'}>{settings.baiduEnabled && settings.baiduTokenConfigured ? '可运行' : '等待配置'}</Tag> }
            ]} />
          </Card>
        </Col>
      </Row>
      <div className="settings-save-bar"><Auth permission="aid:seo:edit"><Button size="large" type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>保存全部 SEO 配置</Button></Auth></div>
    </Form>
  )
}

function PageEditor({ value, onClose, onSaved }: { value?: SeoPage | 'new'; onClose: () => void; onSaved: () => void }) {
  const [form] = Form.useForm<SeoPageSave>()
  const [saving, setSaving] = useState(false)
  const current = value && value !== 'new' ? value : undefined

  useEffect(() => {
    if (!value) return
    form.setFieldsValue(current ? { ...current } : { sourceType: 'MANUAL', pagePath: '/', indexable: true, sitemapEnabled: true, status: '0' })
  }, [current, form, value])

  const save = async () => {
    const fields = await form.validateFields()
    setSaving(true)
    try {
      if (current) await updateSeoPage({ ...fields, id: current.id })
      else await addSeoPage(fields)
      message.success(current ? '页面 SEO 已更新' : '页面已加入 SEO 清单')
      onSaved()
    } finally { setSaving(false) }
  }

  return <Modal title={current ? '编辑页面 SEO' : '添加可索引页面'} open={!!value} width={760} onCancel={onClose} onOk={save} confirmLoading={saving} okText="保存页面">
    <Form form={form} layout="vertical">
      <Row gutter={14}>
        <Col span={16}><Form.Item name="pagePath" label="页面路径" extra="以 / 开头，不包含域名、查询参数或锚点" rules={[{ required: true }, { pattern: /^\/(?!.*[?#\\]).*/, message: '请输入合法站内路径' }]}><Input placeholder="/article/example" /></Form.Item></Col>
        <Col span={8}><Form.Item name="sourceType" label="来源类型"><Select options={[{ value: 'MANUAL', label: '管理员维护' }, { value: 'STATIC', label: '静态页面' }]} /></Form.Item></Col>
      </Row>
      <Form.Item name="pageTitle" label="页面标题" extra="保存时会按全局规则自动追加站点后缀" rules={[{ required: true }]}><Input maxLength={120} showCount /></Form.Item>
      <Form.Item name="metaDescription" label="页面描述"><Input.TextArea rows={3} maxLength={300} showCount /></Form.Item>
      <Form.Item name="metaKeywords" label="页面关键词" extra="留空时根据页面标题和全局关键词自动生成"><Input maxLength={500} placeholder="关键词1,关键词2" /></Form.Item>
      <Form.Item name="ogImageUrl" label="分享图片 URL"><Input placeholder="https://..." /></Form.Item>
      <Space size={32}><Form.Item name="indexable" valuePropName="checked"><Switch checkedChildren="允许索引" unCheckedChildren="禁止索引" /></Form.Item><Form.Item name="sitemapEnabled" valuePropName="checked"><Switch checkedChildren="进入 Sitemap" unCheckedChildren="不进入 Sitemap" /></Form.Item></Space>
    </Form>
  </Modal>
}

function LogDrawer({ page, onClose }: { page?: SeoPage; onClose: () => void }) {
  const [rows, setRows] = useState<SeoLog[]>([])
  const [loading, setLoading] = useState(false)
  useEffect(() => {
    if (!page) return
    setLoading(true)
    listSeoLogs(page.id, 100, true).then((response: any) => setRows(resultData(response, []))).finally(() => setLoading(false))
  }, [page])
  return <Drawer title={page ? `提交记录 · ${page.pageTitle}` : '提交记录'} width={720} open={!!page} onClose={onClose}>
    <Alert className="log-alert" type="info" showIcon message="“接口已接收”只表示百度提交接口已接受链接，不等同于已经收录或获得排名。" />
    <Table rowKey="id" loading={loading} dataSource={rows} pagination={false} size="small" columns={[
      { title: '时间', dataIndex: 'createTime', width: 165 },
      { title: '渠道', dataIndex: 'channel', width: 90, render: (value) => <Tag>{value === 'API' ? 'API' : '手动'}</Tag> },
      { title: '结果', dataIndex: 'submitStatus', width: 120, render: statusTag },
      { title: '说明', render: (_, row) => <div>
        <Text>{row.httpStatus && row.errorMessage === String(row.httpStatus)
          ? '历史记录仅保存了状态码，无法还原百度的具体拒绝原因。更新服务端后由管理员重试，查看新记录。'
          : row.errorMessage || row.responseSummary || '—'}</Text>
        {row.httpStatus && <div><Text type="secondary">HTTP {row.httpStatus} · {row.batchNo}</Text></div>}
        {row.errorCode && <div><Text type="secondary">错误标识：{row.errorCode}</Text></div>}
        {row.errorMessage && row.responseSummary && <div><Text type="secondary">{row.responseSummary}</Text></div>}
      </div> }
    ]} />
  </Drawer>
}
