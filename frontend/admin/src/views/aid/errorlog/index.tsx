import React, { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  message
} from 'antd'
import {
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import {
  convertErrorLogToRule,
  ErrorLog,
  getErrorRuleDraft,
  listErrorLog
} from '@/api/aid/errorlog'
import { listTaskErrorCodes, ProviderErrorRule, TaskErrorCodeOption } from '@/api/aid/errorrule'
import { listModel, listProvider } from '@/api/aid/aimanage'
import Auth from '@/components/Auth'
import PageHeader from '@/components/PageHeader'
import { checkPermi } from '@/hooks/useAuth'
import RuleDialog from '@/views/aid/errorrule/RuleDialog'

/**
 * 未识别错误 / 错误样本日志。
 * <p>
 * 默认只展示未识别的样本（matched_rule_id IS NULL），按出现次数排序。
 * 点"基于此创建规则"直接打开规则编辑器，保存后同步标记来源样本。
 * </p>
 */
export default function ErrorLogPage() {
  const [loading, setLoading] = useState(false)
  const [list, setList] = useState<ErrorLog[]>([])
  const [total, setTotal] = useState(0)
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 20, onlyUnmatched: true })
  const [searchForm] = Form.useForm()

  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState<ErrorLog | null>(null)
  const [providers, setProviders] = useState<{ providerCode: string; providerName: string }[]>([])
  const [models, setModels] = useState<
    { modelCode: string; modelName: string; providerCode?: string }[]
  >([])
  const [errorCodes, setErrorCodes] = useState<TaskErrorCodeOption[]>([])
  const [ruleDialogOpen, setRuleDialogOpen] = useState(false)
  const [ruleDraft, setRuleDraft] = useState<ProviderErrorRule | null>(null)
  const [convertingLogId, setConvertingLogId] = useState<number | null>(null)
  const [conversionLoadingId, setConversionLoadingId] = useState<number | null>(null)

  const loadDictData = async () => {
    try {
      const providerRes: any = await listProvider({ pageNum: 1, pageSize: 999, status: '0' })
      const providerList = providerRes?.rows || []
      setProviders(providerList.map((p: any) => ({
        providerCode: p.providerCode,
        providerName: p.providerName
      })))
      if (!checkPermi('aid:errorlog:convert')) {
        return
      }
      const [modelRes, errorCodeRes]: any[] = await Promise.all([
        listModel({ pageNum: 1, pageSize: 999, status: '0' }),
        listTaskErrorCodes()
      ])
      const providerIdToCode = new Map<number, string>()
      providerList.forEach((p: any) => providerIdToCode.set(p.id, p.providerCode))
      setModels((modelRes?.rows || []).map((model: any) => ({
        modelCode: model.modelCode,
        modelName: model.modelName,
        providerCode: providerIdToCode.get(model.providerId)
      })))
      setErrorCodes(errorCodeRes?.data || [])
    } catch {
      message.error('规则选项加载失败')
    }
  }

  const loadList = async () => {
    setLoading(true)
    try {
      const res: any = await listErrorLog(query)
      setList(res.rows || [])
      setTotal(res.total || 0)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadDictData()
  }, [])

  useEffect(() => {
    loadList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  const handleSearch = () => {
    const v = searchForm.getFieldsValue()
    setQuery({ ...query, ...v, pageNum: 1 })
  }

  const handleReset = () => {
    searchForm.resetFields()
    setQuery({ pageNum: 1, pageSize: query.pageSize, onlyUnmatched: true })
  }

  const handleConvertToRule = async (row: ErrorLog) => {
    setConversionLoadingId(row.id)
    try {
      const res: any = await getErrorRuleDraft(row.id)
      setRuleDraft(res?.data || null)
      setConvertingLogId(row.id)
      setDetailOpen(false)
      setRuleDialogOpen(true)
    } finally {
      setConversionLoadingId(null)
    }
  }

  const handleSaveRule = async (rule: ProviderErrorRule) => {
    if (!convertingLogId) {
      message.error('错误样本不存在')
      return
    }
    await convertErrorLogToRule({ errorLogId: convertingLogId, rule })
    message.success('规则创建成功')
    setRuleDialogOpen(false)
    setRuleDraft(null)
    setConvertingLogId(null)
    setDetail(null)
    await loadList()
  }

  const columns: any[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '厂商 / 模型',
      key: 'scope',
      width: 220,
      render: (_: any, r: ErrorLog) => (
        <Space direction="vertical" size={2}>
          <Tag color="geekblue">{r.providerCode || '_global'}</Tag>
          {r.modelCode && <Tag color="purple">{r.modelCode}</Tag>}
        </Space>
      )
    },
    {
      title: 'HTTP',
      dataIndex: 'httpStatus',
      width: 80,
      render: (v: number) => (v ? <Tag>{v}</Tag> : <span style={{ color: '#94a3b8' }}>--</span>)
    },
    {
      title: '原始错误（截断）',
      dataIndex: 'rawMessage',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v} placement="topLeft">
          <code className="code-text">{v}</code>
        </Tooltip>
      )
    },
    {
      title: '命中规则',
      dataIndex: 'matchedRuleId',
      width: 130,
      render: (v: number | null, r: ErrorLog) =>
        v ? (
          <Tag color="green">规则 #{v}</Tag>
        ) : (
          <Tag color="orange">未识别 → {r.matchedErrorCode}</Tag>
        )
    },
    { title: '次数', dataIndex: 'occurrenceCount', width: 80 },
    { title: '首次', dataIndex: 'firstSeen', width: 160 },
    { title: '最近', dataIndex: 'lastSeen', width: 160 },
    {
      title: '操作',
      key: 'op',
      width: 220,
      fixed: 'right',
      render: (_: any, r: ErrorLog) => (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => {
              setDetail(r)
              setDetailOpen(true)
            }}
          >
            详情
          </Button>
          {!r.matchedRuleId && (
            <Auth permission="aid:errorlog:convert">
              <Button
                type="link"
                size="small"
                icon={<PlusOutlined />}
                loading={conversionLoadingId === r.id}
                onClick={() => handleConvertToRule(r)}
              >
                转为规则
              </Button>
            </Auth>
          )}
        </Space>
      )
    }
  ]

  return (
    <div className="crud-page">
      <PageHeader
        title={<><ThunderboltOutlined />未识别错误样本</>}
        desc="默认仅展示未命中规则的厂商错误样本，按出现次数排序，可直接基于样本创建识别规则"
      />
      <Card className="page-card" bordered={false}>
        <Form form={searchForm} layout="inline" onFinish={handleSearch}>
          <Row gutter={[8, 8]} style={{ width: '100%' }}>
            <Col>
              <Form.Item name="providerCode" label="厂商">
                <Select
                  allowClear
                  showSearch
                  placeholder="选择厂商"
                  style={{ width: 220 }}
                  optionFilterProp="label"
                  options={providers.map((p) => ({
                    value: p.providerCode,
                    label: `${p.providerName}（${p.providerCode}）`
                  }))}
                />
              </Form.Item>
            </Col>
            <Col>
              <Form.Item name="onlyUnmatched" label="仅未识别" valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col>
              <Space>
                <Button icon={<SearchOutlined />} type="primary" htmlType="submit">
                  查询
                </Button>
                <Button icon={<ReloadOutlined />} onClick={handleReset}>
                  重置
                </Button>
              </Space>
            </Col>
          </Row>
        </Form>
      </Card>

      <Card className="page-card" bordered={false}>
        <Table
          rowKey="id"
          size="middle"
          loading={loading}
          dataSource={list}
          columns={columns}
          scroll={{ x: 1200 }}
          pagination={{
            current: query.pageNum,
            pageSize: query.pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s })
          }}
        />
      </Card>

      <Modal
        title="样本详情"
        open={detailOpen}
        footer={null}
        width={760}
        onCancel={() => setDetailOpen(false)}
      >
        {detail && (
          <div>
            <p>
              <Tag color="geekblue">{detail.providerCode || '_global'}</Tag>
              {detail.modelCode && <Tag color="purple">{detail.modelCode}</Tag>}
              {detail.httpStatus && <Tag>HTTP {detail.httpStatus}</Tag>}
              <Tag>出现 {detail.occurrenceCount} 次</Tag>
            </p>
            <pre className="readonly-preview">{detail.rawMessage}</pre>
            <p style={{ marginTop: 12 }}>
              <span style={{ marginRight: 8 }}>命中：</span>
              {detail.matchedRuleId ? (
                <Tag color="green">规则 #{detail.matchedRuleId}</Tag>
              ) : (
                <Tag color="orange">未识别 → {detail.matchedErrorCode}</Tag>
              )}
            </p>
            {!detail.matchedRuleId && (
              <Auth permission="aid:errorlog:convert">
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  loading={conversionLoadingId === detail.id}
                  onClick={() => handleConvertToRule(detail)}
                >
                  基于此创建规则
                </Button>
              </Auth>
            )}
          </div>
        )}
      </Modal>

      <RuleDialog
        open={ruleDialogOpen}
        editing={ruleDraft}
        errorCodes={errorCodes}
        providers={providers}
        models={models}
        onCancel={() => {
          setRuleDialogOpen(false)
          setRuleDraft(null)
          setConvertingLogId(null)
        }}
        onSave={handleSaveRule}
      />
    </div>
  )
}
