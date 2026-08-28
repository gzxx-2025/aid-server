import React, { useEffect, useState } from 'react';
import {
  Button,
  Collapse,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Upload,
  message
} from 'antd';
import type { UploadFile } from 'antd';
import {
  ExperimentOutlined,
  ReloadOutlined,
  SaveOutlined,
  UploadOutlined
} from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import {
  getImageModerationConfig,
  saveImageModerationConfig,
  testImageModeration,
  type ImageModerationConfig,
  type ModerationResult
} from '@/api/system/configTest';

/**
 * 图片内容安全审查 - 内嵌区块。
 *
 * 路径迁移说明：图片审查作为「配置信息」(/manager/aidconfig) 页面的内嵌区块统一管理，
 * 不再单独建 C 菜单；所有读写/测试接口均在 /aidconfig/imgmoderation/* 下，沿用 aidconfig:aidconfig:edit 权限。
 *
 * 注：moderationStage 为预留配置项，当前审查时机由各上传入口按场景固定
 * （本地写盘走上传前审、云存储走上传后审），暂不在 UI 暴露，避免管理员误以为可全局切换。
 */

/** suggestion → 展示色 */
function suggestionColor(s?: string | null): string {
  const v = (s || '').toLowerCase();
  if (v === 'pass') return 'green';
  if (v === 'review') return 'orange';
  if (v === 'block') return 'red';
  return 'default';
}

/** 需要规整为布尔的配置项（后端以字符串 "true"/"false" 返回） */
const BOOLEAN_KEYS: Array<keyof ImageModerationConfig> = [
  'enabled',
  'prioritizeFileUrl',
  'blockOnSuggestionReview',
  'failOpenOnError',
  'logPassed'
];

/** 把任意值转为布尔：兼容布尔本身与字符串 "true"/"false" */
function toBool(v: unknown): boolean {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') return v.trim().toLowerCase() === 'true';
  return Boolean(v);
}

/**
 * 规整后端返回的配置：后端 GET 返回 Map<String,String>，布尔/数值项均为字符串。
 * 不做转换直接回填会让 Switch 把字符串 "false" 当真值显示为开启，导致「关不掉」。
 */
function normalizeConfig(
  raw?: Partial<Record<keyof ImageModerationConfig, unknown>> | null
): Partial<ImageModerationConfig> {
  const src = (raw || {}) as Record<string, unknown>;
  const result: Record<string, unknown> = { ...src };
  for (const key of BOOLEAN_KEYS) {
    if (src[key] !== undefined && src[key] !== null) {
      result[key] = toBool(src[key]);
    }
  }
  if (src.logRetentionDays !== undefined && src.logRetentionDays !== null) {
    const n = Number(src.logRetentionDays);
    if (!Number.isNaN(n)) result.logRetentionDays = n;
  }
  return result as Partial<ImageModerationConfig>;
}

export default function ImageModerationSection() {
  const [form] = Form.useForm<ImageModerationConfig>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // 测试区状态
  const [testForm] = Form.useForm<{ fileUrl?: string }>();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [pickedFile, setPickedFile] = useState<File | null>(null);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<ModerationResult | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await getImageModerationConfig();
      // 后端 GET 返回的是 Map<String,String>，布尔/数值项均为字符串（如 enabled="false"）。
      // 直接灌给 Switch 的 checked 时，字符串 "false" 在 JS 中为「真值」，会导致开关恒显示为开启、
      // 关闭后保存又被回显为开启。这里统一把字符串规整为真正的布尔/数字再回填表单。
      form.setFieldsValue(normalizeConfig(res.data));
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await saveImageModerationConfig(values);
      message.success('保存成功');
      await load();
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    const { fileUrl } = testForm.getFieldsValue();
    if (!pickedFile && !fileUrl?.trim()) {
      message.warning('请上传一张图片或填写图片 URL');
      return;
    }
    // 测试用当前未保存的表单值（含密钥），叠加 fileUrl
    const cfg = form.getFieldsValue();
    const payload: Record<string, any> = { ...cfg };
    if (fileUrl?.trim()) payload.fileUrl = fileUrl.trim();

    setTesting(true);
    setTestResult(null);
    try {
      const res = await testImageModeration(pickedFile, payload);
      setTestResult(res.data);
      if (res.data?.error) {
        message.error(res.data?.errorMessage || '审查调用失败');
      } else {
        message.success('审查完成');
      }
    } catch (e: any) {
      message.error(e?.message || '测试请求失败');
    } finally {
      setTesting(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Spin spinning={loading}>
        <PageCard
          title="图片内容安全审查配置"
          extra={
            <Space>
              <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
                刷新
              </Button>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                onClick={handleSave}
                loading={saving}
              >
                保存配置
              </Button>
            </Space>
          }
        >
          <Form
            form={form}
            layout="horizontal"
            labelCol={{ flex: '160px' }}
            wrapperCol={{ flex: 'auto' }}
            labelAlign="right"
            style={{ maxWidth: 720 }}
            initialValues={{
              enabled: false,
              provider: 'tencent',
              tencentRegion: 'ap-shanghai',
              prioritizeFileUrl: true,
              moderationStage: 'AFTER_UPLOAD',
              blockOnSuggestionReview: true,
              failOpenOnError: false,
              logPassed: false,
              logRetentionDays: 90
            }}
          >
            <Form.Item label="审查总开关" name="enabled" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>
            <Form.Item label="服务商" name="provider">
              <Select
                options={[{ label: '腾讯云', value: 'tencent' }]}
                style={{ maxWidth: 280 }}
              />
            </Form.Item>
            <Form.Item label="SecretId" name="tencentSecretId">
              <Input placeholder="腾讯云 SecretId" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="SecretKey"
              name="tencentSecretKey"
              extra="含 **** 表示未修改，留空不清空原值"
            >
              <Input.Password
                placeholder="腾讯云 SecretKey"
                autoComplete="new-password"
                visibilityToggle={false}
              />
            </Form.Item>
            <Form.Item label="腾讯云地域" name="tencentRegion">
              <Select
                style={{ maxWidth: 280 }}
                options={[
                  { label: '上海 ap-shanghai', value: 'ap-shanghai' },
                  { label: '广州 ap-guangzhou', value: 'ap-guangzhou' },
                  { label: '北京 ap-beijing', value: 'ap-beijing' },
                  { label: '南京 ap-nanjing', value: 'ap-nanjing' },
                  { label: '新加坡 ap-singapore', value: 'ap-singapore' }
                ]}
              />
            </Form.Item>
            <Form.Item
              label="疑似时拦截"
              name="blockOnSuggestionReview"
              valuePropName="checked"
              extra="疑似违规是否拦截"
            >
              <Switch checkedChildren="拦截" unCheckedChildren="放行" />
            </Form.Item>
            <Form.Item
              label="异常时放行"
              name="failOpenOnError"
              valuePropName="checked"
              extra="审查服务异常时是否放行，默认不放行"
            >
              <Switch checkedChildren="放行" unCheckedChildren="拦截" />
            </Form.Item>
            <Form.Item
              label="优先用图片URL"
              name="prioritizeFileUrl"
              valuePropName="checked"
              extra="COS下优先让审查服务自取图片"
            >
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
            <Form.Item
              label="记录通过日志"
              name="logPassed"
              valuePropName="checked"
              extra="通过的图片也写日志"
            >
              <Switch checkedChildren="记录" unCheckedChildren="不记录" />
            </Form.Item>
            <Form.Item label="日志保留天数" name="logRetentionDays">
              <InputNumber min={1} max={3650} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item label="审查时机" name="moderationStage" hidden>
              <Input />
            </Form.Item>
          </Form>
        </PageCard>
      </Spin>

      <PageCard
        title="审查测试"
        extra={
          <Button
            type="primary"
            ghost
            icon={<ExperimentOutlined />}
            onClick={handleTest}
            loading={testing}
          >
            测试审查
          </Button>
        }
      >
        <div className="help-text" style={{ marginBottom: 12 }}>
          上传一张图片，或填写图片 URL，使用上方当前（未保存）的配置发起一次审查。结果不会落库。
        </div>
        <Form form={testForm} layout="vertical" style={{ maxWidth: 560 }}>
          <Form.Item label="上传图片">
            <Upload
              accept="image/*"
              listType="picture"
              maxCount={1}
              fileList={fileList}
              beforeUpload={(file) => {
                setPickedFile(file as File);
                setFileList([
                  {
                    uid: '-1',
                    name: file.name,
                    status: 'done'
                  }
                ]);
                // 拦截自动上传
                return false;
              }}
              onRemove={() => {
                setPickedFile(null);
                setFileList([]);
                return true;
              }}
            >
              <Button icon={<UploadOutlined />}>选择图片</Button>
            </Upload>
          </Form.Item>
          <Form.Item
            label="或填写图片 URL"
            name="fileUrl"
            extra="与上传二选一；同时存在时以上传文件优先"
          >
            <Input placeholder="https://example.com/image.jpg" allowClear />
          </Form.Item>
        </Form>

        {testResult && (
          <div style={{ marginTop: 8 }}>
            <Space size={16} wrap style={{ marginBottom: 12 }}>
              <span>
                处置建议：
                <Tag color={suggestionColor(testResult.suggestion)}>
                  {testResult.suggestion || '--'}
                </Tag>
              </span>
              <span>
                命中标签：<Tag bordered={false}>{testResult.label || '--'}</Tag>
                {testResult.subLabel ? (
                  <Tag bordered={false}>{testResult.subLabel}</Tag>
                ) : null}
              </span>
              <span>
                分值：
                <Tag bordered={false} color="blue">
                  {testResult.score ?? '--'}
                </Tag>
              </span>
              {testResult.requestId && (
                <span className="help-text">
                  RequestId：{testResult.requestId}
                </span>
              )}
            </Space>
            {testResult.error && testResult.errorMessage && (
              <div style={{ color: '#ff4d4f', marginBottom: 12 }}>
                错误：{testResult.errorMessage}
              </div>
            )}
            {testResult.rawJson && (
              <Collapse
                ghost
                items={[
                  {
                    key: 'raw',
                    label: (
                      <span style={{ fontWeight: 500 }}>原始审查结果（rawJson）</span>
                    ),
                    children: (
                      <pre className="readonly-preview" style={{ maxHeight: 360 }}>
                        {(() => {
                          try {
                            return JSON.stringify(JSON.parse(testResult.rawJson!), null, 2);
                          } catch {
                            return testResult.rawJson;
                          }
                        })()}
                      </pre>
                    )
                  }
                ]}
              />
            )}
          </div>
        )}
      </PageCard>
    </div>
  );
}
