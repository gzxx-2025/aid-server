import React, { useEffect, useMemo, useState } from 'react'
import { Button, Card, Col, Form, Input, Modal, Popconfirm, Row, Select, Space, Switch, Table, Tooltip, message } from 'antd'
import {
  BugOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  PlusOutlined,
  RedoOutlined,
  ReloadOutlined,
  SearchOutlined,
  ThunderboltOutlined,
  ToolOutlined
} from '@ant-design/icons'
import { useSearchParams } from 'react-router-dom'
import {
  addErrorRule,
  delErrorRule,
  getErrorRule,
  listErrorRule,
  listTaskErrorCodes,
  ProviderErrorRule,
  rebuildErrorRuleCache,
  TaskErrorCodeOption,
  toggleErrorRule,
  updateErrorRule
} from '@/api/aid/errorrule'
import { listProvider, listModel } from '@/api/aid/aimanage'
import PageHeader from '@/components/PageHeader'
import StatCard from '@/components/StatCard'
import RuleTester from './RuleTester'
import RuleDialog from './RuleDialog'
import './style.less'

const MATCH_TYPE_OPTIONS = [
  { value: 'HTTP_STATUS', label: 'HTTP 状态码', cls: '--http' },
  { value: 'CODE', label: '数字 code', cls: '--code' },
  { value: 'KEYWORD', label: '关键字', cls: '--keyword' },
  { value: 'REGEX', label: '正则', cls: '--regex' },
  { value: 'JSON_PATH', label: 'JSON 路径', cls: '--json' }
]

export default function ErrorRulePage() {
  const [loading, setLoading] = useState(false)
  const [list, setList] = useState<ProviderErrorRule[]>([])
  const [total, setTotal] = useState(0)
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 20 })
  const [searchForm] = Form.useForm()
  const [errorCodes, setErrorCodes] = useState<TaskErrorCodeOption[]>([])
  /** 厂商列表（来自 /aid/aidprovider/list） */
  const [providers, setProviders] = useState<{ providerCode: string; providerName: string }[]>([])
  /** 模型列表（来自 /aid/aidmodel/list） */
  const [models, setModels] = useState<{ modelCode: string; modelName: string; providerId?: number; providerCode?: string }[]>([])

  const [dlgOpen, setDlgOpen] = useState(false)
  const [editing, setEditing] = useState<ProviderErrorRule | null>(null)

  const [testerOpen, setTesterOpen] = useState(false)

  const [searchParams, setSearchParams] = useSearchParams()

  const loadErrorCodes = async () => {
    try {
      const res = await listTaskErrorCodes()
      setErrorCodes((res?.data as TaskErrorCodeOption[]) || [])
    } catch {
      // ignore
    }
  }

  /**
   * 拉厂商 + 模型清单（一次性，规则页生命周期内复用）。
   * 厂商和模型在 RuoYi 列表接口里返回 rows，pageSize=999 取全量。
   */
  const loadDictData = async () => {
    try {
      const [pRes, mRes]: any[] = await Promise.all([
        listProvider({ pageNum: 1, pageSize: 999, status: '0' }),
        listModel({ pageNum: 1, pageSize: 999, status: '0' })
      ])
      setProviders((pRes?.rows || []).map((p: any) => ({
        providerCode: p.providerCode,
        providerName: p.providerName
      })))
      // 给每个 model 补一个 providerCode（基于 providerId 反查）
      const providerIdToCode = new Map<number, string>()
      ;(pRes?.rows || []).forEach((p: any) => providerIdToCode.set(p.id, p.providerCode))
      setModels((mRes?.rows || []).map((m: any) => ({
        modelCode: m.modelCode,
        modelName: m.modelName,
        providerId: m.providerId,
        providerCode: providerIdToCode.get(m.providerId)
      })))
    } catch {
      // ignore
    }
  }

  const loadList = async () => {
    setLoading(true)
    try {
      const res: any = await listErrorRule(query)
      setList(res.rows || [])
      setTotal(res.total || 0)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadErrorCodes()
    loadDictData()
  }, [])

  useEffect(() => {
    loadList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  // 从 URL query 接收"未识别错误"页跳过来的预填参数
  useEffect(() => {
    const matchPattern = searchParams.get('matchPattern')
    if (matchPattern) {
      openAdd({
        providerCode: searchParams.get('providerCode') || undefined,
        modelCode: searchParams.get('modelCode') || undefined,
        matchType: (searchParams.get('matchType') as ProviderErrorRule['matchType']) || 'KEYWORD',
        matchPattern
      })
      setSearchParams({})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 顶部统计
  const stats = useMemo(() => {
    const enabled = list.filter((r) => r.enabled === 1).length
    const builtin = list.filter((r) => r.isBuiltin === 1).length
    const custom = list.length - builtin
    const distinctErrors = new Set(list.map((r) => r.errorCode)).size
    return { total, enabled, builtin, custom, distinctErrors }
  }, [list, total])

  const handleSearch = () => {
    const v = searchForm.getFieldsValue()
    setQuery({ ...query, ...v, pageNum: 1 })
  }

  const handleReset = () => {
    searchForm.resetFields()
    setQuery({ pageNum: 1, pageSize: query.pageSize })
  }

  const openAdd = (preset?: Partial<ProviderErrorRule>) => {
    setEditing({
      ruleName: '',
      matchType: 'KEYWORD',
      matchPattern: '',
      errorCode: '',
      priority: 100,
      enabled: 1,
      isBuiltin: 0,
      ...preset
    } as ProviderErrorRule)
    setDlgOpen(true)
  }

  const openEdit = async (row: ProviderErrorRule) => {
    const res: any = await getErrorRule(row.id!)
    setEditing(res?.data || row)
    setDlgOpen(true)
  }

  const handleDelete = async (row: ProviderErrorRule) => {
    try {
      await delErrorRule(row.id!)
      message.success('删除成功')
      loadList()
    } catch {
      // 后端会返回内置规则不可删除
    }
  }

  const handleToggle = async (row: ProviderErrorRule, checked: boolean) => {
    await toggleErrorRule(row.id!, checked ? 1 : 0)
    message.success(checked ? '已启用' : '已禁用')
    loadList()
  }

  const handleSave = async (values: ProviderErrorRule) => {
    if (editing?.id) {
      await updateErrorRule({ ...editing, ...values })
      message.success('修改成功')
    } else {
      await addErrorRule(values)
      message.success('新增成功')
    }
    setDlgOpen(false)
    setEditing(null)
    loadList()
  }

  const handleRebuild = async () => {
    await rebuildErrorRuleCache()
    message.success('缓存已重建')
  }

  const columns: any[] = [
    { title: '#', dataIndex: 'priority', width: 64, className: 'rule-priority',
      render: (v: number) => <span style={{ color: '#94a3b8' }}>{v ?? '-'}</span> },
    {
      title: '规则名称',
      dataIndex: 'ruleName',
      width: 280,
      render: (v: string, r: ProviderErrorRule) => (
        <span className="rule-name">
          {v}
          {r.isBuiltin === 1 && <span className="builtin-tag">内置</span>}
        </span>
      )
    },
    {
      title: '范围',
      key: 'scope',
      width: 180,
      render: (_: any, r: ProviderErrorRule) => {
        if (r.modelCode) return <span className="scope-tag --model">模型 · {r.modelCode}</span>
        if (r.providerCode) return <span className="scope-tag --provider">厂商 · {r.providerCode}</span>
        return <span className="scope-tag --global">全局</span>
      }
    },
    {
      title: '匹配类型',
      dataIndex: 'matchType',
      width: 120,
      render: (v: string) => {
        const opt = MATCH_TYPE_OPTIONS.find((o) => o.value === v)
        return <span className={`match-type-tag ${opt?.cls || ''}`}>{opt?.label || v}</span>
      }
    },
    {
      title: '匹配内容',
      dataIndex: 'matchPattern',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v} placement="topLeft">
          <span className="pattern-cell">{v}</span>
        </Tooltip>
      )
    },
    {
      title: '错误码',
      dataIndex: 'errorCode',
      width: 200,
      render: (v: string) => <span className="errorcode-tag">{v}</span>
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 70,
      align: 'center',
      render: (v: number, r: ProviderErrorRule) => (
        <Switch checked={v === 1} size="small" onChange={(c) => handleToggle(r, c)} />
      )
    },
    {
      title: '操作',
      key: 'op',
      width: 140,
      fixed: 'right',
      render: (_: any, r: ProviderErrorRule) => (
        <Space size={4}>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除？"
            disabled={r.isBuiltin === 1}
            onConfirm={() => handleDelete(r)}
          >
            <Button
              size="small"
              type="link"
              danger
              icon={<DeleteOutlined />}
              disabled={r.isBuiltin === 1}
              title={r.isBuiltin === 1 ? '内置规则不可删除' : ''}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <div className="crud-page errorrule-page">
      <PageHeader
        title={<><BugOutlined />错误识别规则</>}
        desc="把上游各厂商错误统一映射成平台错误码，运营无需改代码"
        extra={(
          <Space>
            <Button icon={<ExperimentOutlined />} onClick={() => setTesterOpen(true)}>
              规则测试器
            </Button>
            <Button icon={<RedoOutlined />} onClick={handleRebuild}>
              重建缓存
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openAdd()}>
              新增规则
            </Button>
          </Space>
        )}
      />

      {/* 顶部统计 */}
      <Row gutter={[14, 14]}>
        <Col xs={12} sm={6}>
          <StatCard label="规则总数" value={stats.total} icon={<BugOutlined />} color="#2563eb" />
        </Col>
        <Col xs={12} sm={6}>
          <StatCard label="已启用" value={stats.enabled} icon={<CheckCircleOutlined />} color="#059669" />
        </Col>
        <Col xs={12} sm={6}>
          <StatCard label="自定义规则" value={stats.custom} icon={<ToolOutlined />} color="#7c3aed" />
        </Col>
        <Col xs={12} sm={6}>
          <StatCard label="覆盖错误码" value={stats.distinctErrors} icon={<ThunderboltOutlined />} color="#d97706" />
        </Col>
      </Row>

      {/* 搜索区 */}
      <Card className="page-card" bordered={false}>
        <Form
          form={searchForm}
          layout="inline"
          onFinish={handleSearch}
          onValuesChange={(changed) => {
            // 切换厂商时清空模型筛选，避免组合不一致
            if ('providerCode' in changed) {
              searchForm.setFieldValue('modelCode', undefined)
            }
          }}
        >
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
          <Form.Item name="modelCode" label="模型" shouldUpdate>
            {({ getFieldValue }: any) => {
              const pc = getFieldValue('providerCode')
              const filtered = pc ? models.filter((m) => m.providerCode === pc) : models
              return (
                <Form.Item name="modelCode" noStyle>
                  <Select
                    allowClear
                    showSearch
                    placeholder={pc ? '选择模型' : '先选厂商或留空'}
                    style={{ width: 240 }}
                    optionFilterProp="label"
                    options={filtered.map((m) => ({
                      value: m.modelCode,
                      label: `${m.modelName}（${m.modelCode}）`
                    }))}
                  />
                </Form.Item>
              )
            }}
          </Form.Item>
          <Form.Item name="errorCode" label="错误码">
            <Select
              allowClear
              showSearch
              placeholder="选择错误码"
              style={{ width: 240 }}
              options={errorCodes.map((e) => ({ value: e.code, label: e.code }))}
            />
          </Form.Item>
          <Form.Item name="enabled" label="启用">
            <Select
              allowClear
              style={{ width: 100 }}
              options={[
                { value: 1, label: '启用' },
                { value: 0, label: '禁用' }
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button icon={<SearchOutlined />} type="primary" htmlType="submit">
                查询
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleReset}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {/* 主表格 */}
      <Card className="page-card" bordered={false}>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={list}
          columns={columns}
          scroll={{ x: 1200 }}
          size="middle"
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

      <RuleDialog
        open={dlgOpen}
        editing={editing}
        errorCodes={errorCodes}
        providers={providers}
        models={models}
        onCancel={() => {
          setDlgOpen(false)
          setEditing(null)
        }}
        onSave={handleSave}
      />

      <Modal
        title={
          <Space>
            <ThunderboltOutlined style={{ color: '#6366f1' }} />
            规则测试器
          </Space>
        }
        open={testerOpen}
        footer={null}
        width={800}
        onCancel={() => setTesterOpen(false)}
      >
        <RuleTester providers={providers} models={models} />
      </Modal>
    </div>
  )
}
