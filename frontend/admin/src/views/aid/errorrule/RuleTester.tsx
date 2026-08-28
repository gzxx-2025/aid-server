import React, { useState } from 'react'
import { Alert, Button, Col, Descriptions, Form, Input, InputNumber, Row, Select, Tag, message } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { ErrorRuleTestResult, testErrorRule } from '@/api/aid/errorrule'
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
  providers: ProviderItem[]
  models: ModelItem[]
}

/**
 * 规则测试器：贴一段原始错误，看会命中哪条规则、最终的 errorCode。
 * 走 dryRun 路径，不写 aid_error_log。
 */
export default function RuleTester({ providers, models }: Props) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<ErrorRuleTestResult | null>(null)

  const handleTest = async () => {
    const values = await form.validateFields()
    setLoading(true)
    try {
      const res: any = await testErrorRule(values)
      setResult(res?.data || null)
    } catch {
      message.error('测试失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="rule-tester">
      <div className="help-text" style={{ marginBottom: 12 }}>
        贴入上游返回的原始错误体，试运行将展示命中的规则与归一化结果（dryRun，不写入错误日志）。
      </div>
      <Form
        form={form}
        layout="vertical"
        onValuesChange={(changed) => {
          if ('providerCode' in changed) {
            form.setFieldValue('modelCode', undefined)
          }
        }}
      >
        <Row gutter={12}>
          <Col span={8}>
            <Form.Item name="providerCode" label="厂商">
              <Select
                allowClear
                showSearch
                placeholder="留空走全局规则"
                optionFilterProp="label"
                options={providers.map((p) => ({
                  value: p.providerCode,
                  label: `${p.providerName}（${p.providerCode}）`
                }))}
              />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item shouldUpdate noStyle>
              {() => {
                const pc = form.getFieldValue('providerCode')
                const filtered = pc ? models.filter((m) => m.providerCode === pc) : models
                return (
                  <Form.Item name="modelCode" label="模型">
                    <Select
                      allowClear
                      showSearch
                      placeholder="可选"
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
          <Col span={8}>
            <Form.Item name="httpStatus" label="HTTP 状态">
              <InputNumber min={100} max={599} placeholder="如 429" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item
          name="rawMessage"
          label="原始错误体"
          rules={[{ required: true, message: '请输入待测试的错误文案' }]}
        >
          <Input.TextArea
            rows={6}
            placeholder='例如: {"error":{"code":"SensitiveContentDetected","message":"..."}}'
            style={{ fontFamily: 'JetBrains Mono, Consolas, Menlo, monospace', fontSize: 12 }}
          />
        </Form.Item>
        <Button type="primary" loading={loading} onClick={handleTest} icon={<ThunderboltOutlined />}>
          运行测试
        </Button>
      </Form>

      {result && (
        <div className="tester-result">
          <Descriptions
            bordered
            size="small"
            column={1}
            labelStyle={{ width: 120, background: '#f8fafc' }}
            title={
              <span>
                <Tag color="success">归一化结果</Tag>
                {result.errorCode === 'AI_GENERATION_FAILED' && (
                  <Tag color="warning">未识别 → 兜底</Tag>
                )}
              </span>
            }
          >
            <Descriptions.Item label="错误码">
              <Tag color="red" style={{ fontFamily: 'JetBrains Mono, Consolas' }}>
                {result.errorCode}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="错误大类">{result.errorType}</Descriptions.Item>
            <Descriptions.Item label="错误来源">{result.errorSource}</Descriptions.Item>
            <Descriptions.Item label="用户文案">{result.userMessage}</Descriptions.Item>
            <Descriptions.Item label="可重试">
              {result.retryable ? <Tag color="green">是</Tag> : <Tag>否</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="需充值">
              {result.needRecharge ? <Tag color="orange">是</Tag> : <Tag>否</Tag>}
            </Descriptions.Item>
          </Descriptions>
          {result.errorCode === 'AI_GENERATION_FAILED' && (
            <Alert
              style={{ marginTop: 12 }}
              type="warning"
              showIcon
              message="未识别"
              description="兜底为 AI_GENERATION_FAILED。建议在错误规则管理页基于此样本新建一条规则。"
            />
          )}
        </div>
      )}
    </div>
  )
}
