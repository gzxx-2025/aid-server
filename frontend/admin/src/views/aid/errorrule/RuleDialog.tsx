import React, { useEffect } from 'react'
import { Alert, Col, Form, Input, InputNumber, Modal, Row, Select, Switch, Tag } from 'antd'
import {
  ApiOutlined,
  CodeOutlined,
  ExclamationCircleOutlined,
  FontColorsOutlined,
  PartitionOutlined
} from '@ant-design/icons'
import { ProviderErrorRule, TaskErrorCodeOption } from '@/api/aid/errorrule'
import './style.less'

interface ProviderItem {
  providerCode: string
  providerName: string
}
interface ModelItem {
  modelCode: string
  modelName: string
  providerCode?: string
}

interface Props {
  open: boolean
  editing: ProviderErrorRule | null
  errorCodes: TaskErrorCodeOption[]
  providers: ProviderItem[]
  models: ModelItem[]
  onCancel: () => void
  onSave: (values: ProviderErrorRule) => Promise<void> | void
}

const MATCH_TYPE_OPTIONS: {
  value: ProviderErrorRule['matchType']
  label: string
  icon: React.ReactNode
  placeholder: string
  desc: string
}[] = [
  {
    value: 'HTTP_STATUS',
    label: 'HTTP 状态码',
    icon: <ApiOutlined />,
    placeholder: '逗号分隔，支持区间，如 401,500-504',
    desc: '按 HTTP 响应码精确或区间命中'
  },
  {
    value: 'CODE',
    label: '数字 code',
    icon: <CodeOutlined />,
    placeholder: '逗号分隔，如 50411,50412,50413',
    desc: '在错误体中提取完整数字 code，前后必须不是数字'
  },
  {
    value: 'KEYWORD',
    label: '关键字',
    icon: <FontColorsOutlined />,
    placeholder: '逗号分隔，如 timeout,timed out',
    desc: '任一关键字命中即匹配（默认大小写不敏感）'
  },
  {
    value: 'REGEX',
    label: '正则',
    icon: <ExclamationCircleOutlined />,
    placeholder: 'Java 正则字面量，如 余额不足|insufficient.+balance',
    desc: '保存前会校验正则可编译性'
  },
  {
    value: 'JSON_PATH',
    label: 'JSON 路径',
    icon: <PartitionOutlined />,
    placeholder: '关键字（联用下方字段路径），如 RATE_LIMIT_EXCEEDED',
    desc: '从原始 JSON 取值后再用关键字匹配'
  }
]

export default function RuleDialog({ open, editing, errorCodes, providers, models, onCancel, onSave }: Props) {
  const [form] = Form.useForm()

  useEffect(() => {
    if (open && editing) {
      form.resetFields()
      form.setFieldsValue({
        ...editing,
        caseSensitive: editing.caseSensitive === 1,
        enabled: editing.enabled !== 0
      })
    }
  }, [open, editing, form])

  const handleOk = async () => {
    const values = await form.validateFields()
    const payload: ProviderErrorRule = {
      ...editing,
      ...values,
      caseSensitive: values.caseSensitive ? 1 : 0,
      enabled: values.enabled ? 1 : 0
    }
    await onSave(payload)
  }

  const matchType = Form.useWatch('matchType', form)
  const currentOpt = MATCH_TYPE_OPTIONS.find((o) => o.value === matchType)

  return (
    <Modal
      open={open}
      title={editing?.id ? (editing.isBuiltin === 1 ? '查看内置规则' : '修改规则') : '新增规则'}
      width={820}
      onCancel={onCancel}
      onOk={handleOk}
      destroyOnClose
      okButtonProps={{ disabled: editing?.isBuiltin === 1 }}
      className="rule-dialog"
    >
      {editing?.isBuiltin === 1 && (
        <Alert
          showIcon
          type="info"
          message="内置规则只可禁用，不能修改/删除"
          style={{ marginBottom: 16 }}
        />
      )}
      <Form form={form} layout="vertical" disabled={editing?.isBuiltin === 1}>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item
              name="ruleName"
              label="规则名称"
              rules={[{ required: true, message: '请填写规则名称' }]}
            >
              <Input placeholder="如：火山方舟内容安全拦截" maxLength={128} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item
              name="priority"
              label="优先级"
              tooltip="数字越小越先匹配"
            >
              <InputNumber min={1} max={1000} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item label=" " colon={false}>
              <Form.Item name="enabled" valuePropName="checked" noStyle>
                <Switch checkedChildren="启用" unCheckedChildren="禁用" />
              </Form.Item>
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="providerCode" label="厂商" tooltip="留空表示对所有厂商生效">
              <Select
                allowClear
                showSearch
                placeholder="选择厂商（留空=全局）"
                optionFilterProp="label"
                onChange={() => form.setFieldValue('modelCode', undefined)}
                options={providers.map((p) => ({
                  value: p.providerCode,
                  label: `${p.providerName}（${p.providerCode}）`
                }))}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item shouldUpdate noStyle>
              {() => {
                const pc = form.getFieldValue('providerCode')
                const filtered = pc ? models.filter((m) => m.providerCode === pc) : models
                return (
                  <Form.Item name="modelCode" label="模型" tooltip="留空表示该厂商所有模型生效">
                    <Select
                      allowClear
                      showSearch
                      placeholder={pc ? '选择模型（留空=该厂商所有）' : '可先选厂商再选模型'}
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
          </Col>
        </Row>

        {/* 匹配类型卡片网格 */}
        <Form.Item
          label="匹配类型"
          required
          tooltip="不同匹配类型对应不同的 matchPattern 写法"
        >
          <Form.Item name="matchType" noStyle rules={[{ required: true }]}>
            <Input type="hidden" />
          </Form.Item>
          <div className="match-type-grid">
            {MATCH_TYPE_OPTIONS.map((opt) => (
              <div
                key={opt.value}
                className={`match-type-card ${matchType === opt.value ? '--active' : ''}`}
                onClick={() => {
                  if (editing?.isBuiltin !== 1) {
                    form.setFieldsValue({ matchType: opt.value })
                  }
                }}
              >
                <div className="match-type-card__icon">{opt.icon}</div>
                <div className="match-type-card__label">{opt.label}</div>
              </div>
            ))}
          </div>
          {currentOpt && (
            <div className="help-text" style={{ marginTop: 8 }}>
              {currentOpt.desc}
            </div>
          )}
        </Form.Item>

        <Form.Item
          name="matchPattern"
          label="匹配内容"
          rules={[{ required: true, message: '请填写匹配内容' }]}
        >
          <Input.TextArea
            rows={3}
            placeholder={currentOpt?.placeholder || '请输入匹配内容'}
            style={{ fontFamily: 'JetBrains Mono, Consolas, Menlo, monospace', fontSize: 13 }}
          />
        </Form.Item>

        {matchType === 'JSON_PATH' && (
          <Form.Item
            name="matchField"
            label="字段路径"
            rules={[{ required: true, message: 'JSON_PATH 模式必填' }]}
            extra="支持 $.a.b.c 这种点分形式，从原始 JSON 取值后再用上面的关键字匹配"
          >
            <Input
              placeholder="如 $.error.code"
              maxLength={64}
              style={{ fontFamily: 'JetBrains Mono, Consolas, Menlo, monospace' }}
            />
          </Form.Item>
        )}

        <Row gutter={16}>
          <Col span={12}>
            <Form.Item
              name="errorCode"
              label="映射到的错误码"
              rules={[{ required: true, message: '请选择错误码' }]}
            >
              <Select
                showSearch
                placeholder="选择 TaskErrorCode"
                optionLabelProp="label"
                options={errorCodes.map((e) => ({
                  value: e.code,
                  label: e.code,
                  filterText: `${e.code} ${e.userMessage}`,
                  data: e
                }))}
                filterOption={(input, opt) => {
                  const txt = (opt as any)?.filterText || ''
                  return txt.toLowerCase().includes(input.toLowerCase())
                }}
                optionRender={(option) => {
                  const e: TaskErrorCodeOption = (option.data as any).data
                  return (
                    <div style={{ padding: '4px 0' }}>
                      <div style={{ fontWeight: 500, fontFamily: 'JetBrains Mono, Consolas', fontSize: 12 }}>
                        {e.code}
                      </div>
                      <div className="help-text">{e.userMessage}</div>
                    </div>
                  )
                }}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="userMessage"
              label="覆盖默认 userMessage"
              tooltip="留空则用枚举默认文案"
            >
              <Input placeholder="可留空" maxLength={255} />
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col span={6}>
            <Form.Item
              name="caseSensitive"
              label="区分大小写"
              valuePropName="checked"
              tooltip="仅 KEYWORD / REGEX / JSON_PATH 生效"
            >
              <Switch />
            </Form.Item>
          </Col>
          <Col span={18}>
            <Form.Item name="remark" label="备注">
              <Input placeholder="可选，写给运营自己看的" maxLength={500} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  )
}
