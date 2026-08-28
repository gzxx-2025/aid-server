import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Upload,
  message
} from 'antd';
import type { TableColumnsType, UploadFile } from 'antd';
import { CopyOutlined, ExperimentOutlined, InboxOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import SectionTitle from '@/components/SectionTitle';
import {
  getTencentAsrConfig,
  saveTencentAsrConfig,
  testTencentAsr,
  type TencentAsrTestCue,
  type TencentAsrTestResult
} from '@/api/aidconfig/aidconfig';
import './TencentAsrSection.less';

const REGION_OPTIONS = [
  { label: '广州 ap-guangzhou', value: 'ap-guangzhou' },
  { label: '上海 ap-shanghai', value: 'ap-shanghai' },
  { label: '北京 ap-beijing', value: 'ap-beijing' },
  { label: '南京 ap-nanjing', value: 'ap-nanjing' },
  { label: '成都 ap-chengdu', value: 'ap-chengdu' },
  { label: '重庆 ap-chongqing', value: 'ap-chongqing' },
  { label: '中国香港 ap-hongkong', value: 'ap-hongkong' },
  { label: '新加坡 ap-singapore', value: 'ap-singapore' },
  { label: '东京 ap-tokyo', value: 'ap-tokyo' }
];

/** 只提供支持字幕详细时间戳的腾讯云常用 16k 引擎。 */
const ENGINE_OPTIONS = [
  { label: '中英大模型 2.0（推荐）', value: '16k_zh_en_2.0' },
  { label: '中英大模型 1.0', value: '16k_zh_en' },
  { label: '中文普通话通用', value: '16k_zh' },
  { label: '英文通用', value: '16k_en' },
  { label: '粤语通用', value: '16k_yue' }
];

function toBool(value: unknown): boolean {
  return typeof value === 'string' ? value.trim().toLowerCase() === 'true' : Boolean(value);
}

function toNumber(value: unknown, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

const TEST_VIDEO_EXTENSIONS = ['mp4', 'flv', '3gp'];

function formatSeconds(value?: number): string {
  if (value === undefined || value === null || !Number.isFinite(value)) return '--';
  const minutes = Math.floor(value / 60);
  const seconds = value - minutes * 60;
  return `${String(minutes).padStart(2, '0')}:${seconds.toFixed(3).padStart(6, '0')}`;
}

function formatFileSize(value?: number): string {
  if (value === undefined || value === null || !Number.isFinite(value)) return '--';
  if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(2)} MB`;
  return `${(value / 1024).toFixed(2)} KB`;
}

/** 腾讯云语音识别专用配置区块。 */
export default function TencentAsrSection() {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testFile, setTestFile] = useState<File | null>(null);
  const [testFileList, setTestFileList] = useState<UploadFile[]>([]);
  const [testResult, setTestResult] = useState<TencentAsrTestResult | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response: any = await getTencentAsrConfig();
      const raw = (response?.data || {}) as Record<string, unknown>;
      form.setFieldsValue({
        enabled: toBool(raw.enabled),
        secretId: raw.secretId,
        secretKey: raw.secretKey,
        region: raw.region || 'ap-guangzhou',
        engineModelType: raw.engineModelType || '16k_zh_en_2.0',
        sentenceMaxLength: toNumber(raw.sentenceMaxLength, 10),
        speakerDiarization: toNumber(raw.speakerDiarization, 0) === 1,
        hotwordId: raw.hotwordId,
        hotwordList: raw.hotwordList,
        timeoutSeconds: toNumber(raw.timeoutSeconds, 180),
        maxAttempts: toNumber(raw.maxAttempts, 2)
      });
    } finally {
      setLoading(false);
    }
  }, [form]);

  useEffect(() => {
    load();
  }, [load]);

  const handleSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await saveTencentAsrConfig({
        ...values,
        speakerDiarization: values.speakerDiarization ? 1 : 0
      });
      message.success('保存成功');
      await load();
    } finally {
      setSaving(false);
    }
  };

  const handleTestFile = (file: File) => {
    const extension = file.name.split('.').pop()?.toLowerCase() || '';
    if (!TEST_VIDEO_EXTENSIONS.includes(extension)) {
      message.error('仅支持 MP4、FLV、3GP 视频');
      return Upload.LIST_IGNORE;
    }
    if (file.size > 1024 * 1024 * 1024) {
      message.error('测试视频不能超过 1GB');
      return Upload.LIST_IGNORE;
    }
    setTestFile(file);
    setTestResult(null);
    setTestFileList([{ uid: file.name, name: file.name, size: file.size, status: 'done' }]);
    return false;
  };

  const handleTest = async () => {
    if (!testFile) {
      message.warning('请先选择测试视频');
      return;
    }
    setTesting(true);
    setTestResult(null);
    try {
      const response = await testTencentAsr(testFile);
      setTestResult(response.data || null);
      message.success('识别测试完成');
    } finally {
      setTesting(false);
    }
  };

  const copyResult = async () => {
    const text = testResult?.text || '';
    if (!text) {
      message.warning('暂无可复制文本');
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      message.success('识别文本已复制');
    } catch {
      message.error('复制失败，请手动复制');
    }
  };

  const cueColumns: TableColumnsType<TencentAsrTestCue> = [
    {
      title: '序号',
      width: 64,
      render: (_: unknown, __: TencentAsrTestCue, index: number) => index + 1
    },
    {
      title: '时间',
      width: 190,
      render: (_: unknown, cue: TencentAsrTestCue) =>
        `${formatSeconds(cue.startSeconds)} ～ ${formatSeconds(cue.endSeconds)}`
    },
    {
      title: '说话人',
      dataIndex: 'speaker',
      width: 110,
      render: (value?: string) => value || '--'
    },
    {
      title: '最终字幕',
      dataIndex: 'text',
      render: (value?: string) => value || '--'
    }
  ];

  return (
    <Spin spinning={loading}>
      <PageCard
        title="腾讯云语音识别配置"
        extra={
          <Space>
            <Button icon={<ExperimentOutlined />} onClick={() => setTestOpen(true)}>
              测试识别
            </Button>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
              保存配置
            </Button>
          </Space>
        }
      >
        <Form
          className="tencent-asr-form"
          form={form}
          layout="horizontal"
          labelCol={{ flex: '150px' }}
          wrapperCol={{ flex: '1 1 0', style: { minWidth: 0 } }}
          labelAlign="right"
          style={{ width: '100%', maxWidth: 760 }}
        >
          <SectionTitle title="自动字幕" style={{ justifyContent: 'center' }} />
          <Form.Item
            label="自动生成字幕"
            name="enabled"
            valuePropName="checked"
            extra="开启后，导出时逐分镜同步识别有台词的视频；识别失败时本次导出失败。关闭后沿用原始字幕拼接。"
          >
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>

          <SectionTitle title="腾讯云凭证" style={{ justifyContent: 'center' }} />
          <Form.Item label="SecretId" name="secretId">
            <Input placeholder="腾讯云 SecretId" autoComplete="off" allowClear />
          </Form.Item>
          <Form.Item label="SecretKey" name="secretKey" extra="含 **** 表示未修改，留空不清空原值">
            <Input.Password placeholder="腾讯云 SecretKey" autoComplete="new-password" visibilityToggle={false} />
          </Form.Item>
          <Form.Item label="接口地域" name="region" rules={[{ required: true, message: '请选择接口地域' }]}>
            <Select style={{ maxWidth: 320 }} options={REGION_OPTIONS} showSearch optionFilterProp="label" />
          </Form.Item>

          <SectionTitle title="识别与字幕" style={{ justifyContent: 'center' }} />
          <Form.Item label="识别引擎" name="engineModelType" rules={[{ required: true, message: '请选择识别引擎' }]}>
            <Select style={{ maxWidth: 320 }} options={ENGINE_OPTIONS} />
          </Form.Item>
          <Form.Item
            label="单行字幕最大字数"
            name="sentenceMaxLength"
            rules={[{ required: true, message: '请填写单行字幕最大字数' }]}
            extra="该值是上限，不是固定字数。设置为 10 时每行最多 10 字，遇到标点或句尾可能少于 10 字；允许 6～40，最终展示会移除逗号、句号等标点。"
          >
            <InputNumber min={6} max={40} precision={0} style={{ width: 180 }} addonAfter="字" />
          </Form.Item>
          <Form.Item
            label="说话人分离"
            name="speakerDiarization"
            valuePropName="checked"
            extra="用于多人同场语音分段；最终人物名仍以分镜原台词匹配结果为准。"
          >
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>
          <Form.Item label="热词表 ID" name="hotwordId" extra="腾讯云控制台已创建的热词表 ID，可不填。">
            <Input allowClear placeholder="热词表 ID" />
          </Form.Item>
          <Form.Item
            label="临时热词"
            name="hotwordList"
            extra="格式：词语|权重，多个用英文逗号分隔；同时填写时临时热词优先生效。"
          >
            <Input.TextArea rows={3} allowClear placeholder="角色名|10,专有名词|8" />
          </Form.Item>

          <SectionTitle title="失败处理" style={{ justifyContent: 'center' }} />
          <Form.Item label="单镜等待上限" name="timeoutSeconds" rules={[{ required: true, message: '请填写等待上限' }]}>
            <InputNumber min={30} max={600} precision={0} style={{ width: 180 }} addonAfter="秒" />
          </Form.Item>
          <Form.Item
            label="最多尝试次数"
            name="maxAttempts"
            rules={[{ required: true, message: '请填写尝试次数' }]}
            extra="包含首次识别；全部尝试失败后，整次视频导出失败。"
          >
            <InputNumber min={1} max={3} precision={0} style={{ width: 180 }} addonAfter="次" />
          </Form.Item>
        </Form>
      </PageCard>

      <Modal
        title="腾讯云语音识别测试"
        open={testOpen}
        width={960}
        maskClosable={!testing}
        closable={!testing}
        keyboard={!testing}
        onCancel={() => setTestOpen(false)}
        footer={[
          <Button key="close" disabled={testing} onClick={() => setTestOpen(false)}>
            关闭
          </Button>,
          <Button key="test" type="primary" loading={testing} disabled={!testFile} onClick={handleTest}>
            {testing ? '识别中' : '开始识别'}
          </Button>
        ]}
      >
        <Alert
          type="info"
          showIcon
          message="使用已保存的配置进行测试，总开关关闭时也可以测试。测试会产生腾讯云 ASR 费用，结果不落库，临时视频会在识别结束后删除。"
          style={{ marginBottom: 16 }}
        />

        <Upload.Dragger
          accept=".mp4,.flv,.3gp,video/mp4,video/x-flv,video/3gpp"
          maxCount={1}
          disabled={testing}
          fileList={testFileList}
          beforeUpload={(file) => handleTestFile(file as File)}
          onRemove={() => {
            setTestFile(null);
            setTestFileList([]);
            setTestResult(null);
            return true;
          }}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖入一个测试视频</p>
          <p className="ant-upload-hint">支持 MP4、FLV、3GP，文件大小同时受后台文件存储配置限制</p>
        </Upload.Dragger>

        {testing && (
          <Alert
            type="warning"
            showIcon
            message="视频已提交，正在等待腾讯云返回最终结果，请不要关闭窗口。"
            style={{ marginTop: 16 }}
          />
        )}

        {testResult && (
          <div className="tencent-asr-test-result">
            <SectionTitle title="最终识别结果" style={{ justifyContent: 'center' }} />
            <Descriptions
              size="small"
              column={4}
              items={[
                { key: 'file', label: '文件', children: testResult.fileName || '--' },
                { key: 'size', label: '大小', children: formatFileSize(testResult.fileSize) },
                { key: 'duration', label: '时长', children: formatSeconds(testResult.durationSeconds) },
                { key: 'cues', label: '字幕段数', children: testResult.cueCount ?? 0 },
                {
                  key: 'elapsed',
                  label: '测试耗时',
                  children: testResult.elapsedMs ? `${(testResult.elapsedMs / 1000).toFixed(2)} 秒` : '--'
                }
              ]}
            />

            <div className="tencent-asr-result-title">
              <span>完整识别文本</span>
              <Button size="small" icon={<CopyOutlined />} onClick={copyResult}>
                复制文本
              </Button>
            </div>
            <Input.TextArea value={testResult.text || ''} readOnly autoSize={{ minRows: 4, maxRows: 10 }} />

            <div className="tencent-asr-result-title">时间戳分段</div>
            <Table<TencentAsrTestCue>
              size="small"
              pagination={false}
              rowKey={(_, index) => String(index)}
              columns={cueColumns}
              dataSource={testResult.cues || []}
              scroll={{ y: 320 }}
            />

            {testResult.rawText && (
              <Collapse
                ghost
                items={[
                  {
                    key: 'raw',
                    label: '查看腾讯云原始文本',
                    children: <pre className="tencent-asr-raw-result">{testResult.rawText}</pre>
                  }
                ]}
              />
            )}
          </div>
        )}
      </Modal>
    </Spin>
  );
}
