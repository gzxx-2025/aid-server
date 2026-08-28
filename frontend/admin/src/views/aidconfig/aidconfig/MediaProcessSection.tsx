import React, { useEffect, useState } from 'react';
import { Alert, Button, Col, Form, Input, InputNumber, Row, Select, Space, Spin, Switch, message } from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import SectionTitle from '@/components/SectionTitle';
import { getMediaProcessConfig, getStorageConfig, saveMediaProcessConfig } from '@/api/aidconfig/aidconfig';
import './MediaProcessSection.less';

const PROCESS_OPTIONS = [
  { label: '腾讯云 MPS', value: 'tencent-mps' },
  { label: '阿里云 IMS', value: 'aliyun-ims' },
  { label: '本地 FFmpeg', value: 'local-ffmpeg' }
];
const STORAGE_ALLOWED_MODES: Record<string, string[]> = {
  cos: ['tencent-mps', 'local-ffmpeg'],
  oss: ['aliyun-ims', 'local-ffmpeg'],
  local: ['local-ffmpeg'],
  qiniu: ['local-ffmpeg']
};
const STORAGE_NAMES: Record<string, string> = {
  cos: '腾讯云 COS',
  oss: '阿里云 OSS',
  local: '本地存储',
  qiniu: '七牛云 Kodo'
};
const TENCENT_REGIONS = [
  'ap-bangkok', 'ap-beijing', 'ap-chengdu', 'ap-chongqing', 'ap-guangzhou', 'ap-hongkong',
  'ap-jakarta', 'ap-nanjing', 'ap-seoul', 'ap-shanghai', 'ap-singapore', 'ap-tokyo',
  'eu-frankfurt', 'na-ashburn', 'na-siliconvalley', 'sa-saopaulo'
].map((value) => ({ label: value, value }));
const ALIYUN_REGIONS = ['cn-shanghai', 'cn-hangzhou', 'cn-beijing', 'cn-shenzhen', 'ap-southeast-1', 'us-west-1'].map((value) => ({ label: value, value }));
const RESOLUTIONS = ['SD', 'HD', 'FHD', '2K', '4K'].map((value) => ({ label: value, value }));
const ALL_CODECS = [
  { label: 'H.264（兼容性最好）', value: 'H.264' },
  { label: 'H.265', value: 'H.265' },
  { label: 'AV1', value: 'AV1' }
];
const PRICE_FIELDS = [
  { suffix: 'Sd', legacy: 'SD', label: 'SD' },
  { suffix: 'Hd', legacy: 'HD', label: 'HD' },
  { suffix: 'Fhd', legacy: 'FHD', label: 'FHD' },
  { suffix: '2k', legacy: '2K', label: '2K' },
  { suffix: '4k', legacy: '4K', label: '4K' }
];

function toBool(value: unknown) {
  return typeof value === 'boolean' ? value : String(value).toLowerCase() === 'true';
}

function toNum(value: unknown, fallback?: number) {
  const parsed = Number(value);
  return value === '' || value === null || value === undefined || Number.isNaN(parsed) ? fallback : parsed;
}

function PriceFields({ prefix }: { prefix: 'tencent' | 'aliyun' }) {
  return (
    <Form.Item label="处理单价" extra="只计算媒体处理成本；对象存储、请求和流量费用不计入系统账单">
      <Row gutter={[12, 12]} style={{ maxWidth: 620 }}>
        {PRICE_FIELDS.map(({ suffix, label }) => (
          <Col span={12} key={suffix}>
            <Space>
              <span className="media-process-section__tier-label">{label}</span>
              <Form.Item name={`${prefix}Price${suffix}`} noStyle rules={[{ required: true, message: `请填写${label}单价` }]}>
                <InputNumber min={0} step={0.001} style={{ width: 160 }} addonAfter="元/分钟" />
              </Form.Item>
            </Space>
          </Col>
        ))}
      </Row>
    </Form.Item>
  );
}

export default function MediaProcessSection() {
  const [form] = Form.useForm();
  const mode = Form.useWatch('processMode', form) || 'tencent-mps';
  const enabled = Form.useWatch('enabled', form) ?? false;
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [storageMode, setStorageMode] = useState('local');
  const processOptions = PROCESS_OPTIONS.filter((item) =>
    (STORAGE_ALLOWED_MODES[storageMode] || STORAGE_ALLOWED_MODES.local).includes(item.value)
  );
  const codecOptions = mode === 'tencent-mps'
    ? ALL_CODECS.slice(0, 1)
    : mode === 'aliyun-ims' ? ALL_CODECS.slice(0, 2) : ALL_CODECS;

  const load = async () => {
    setLoading(true);
    try {
      const [res, storageRes]: any[] = await Promise.all([getMediaProcessConfig(), getStorageConfig()]);
      const raw = (res?.data || {}) as Record<string, any>;
      const currentStorageMode = String(storageRes?.data?.uploadMode || 'local').toLowerCase();
      const allowedModes = STORAGE_ALLOWED_MODES[currentStorageMode] || STORAGE_ALLOWED_MODES.local;
      const configuredMode = raw.processMode || 'tencent-mps';
      setStorageMode(currentStorageMode);
      const values: Record<string, any> = {
        ...raw,
        enabled: toBool(raw.enabled),
        processMode: allowedModes.includes(configuredMode) ? configuredMode : 'local-ffmpeg',
        tencentSecretId: raw.tencentSecretId || raw.secretId,
        tencentSecretKey: raw.tencentSecretKey || raw.secretKey,
        tencentRegion: raw.tencentRegion || raw.region || 'ap-guangzhou',
        tencentCallbackUrl: raw.tencentCallbackUrl || raw.callbackUrl,
        tencentMaxConcurrency: toNum(raw.tencentMaxConcurrency, 5),
        aliyunRegion: raw.aliyunRegion || 'cn-shanghai',
        aliyunMaxConcurrency: toNum(raw.aliyunMaxConcurrency, 5),
        ffmpegPath: raw.ffmpegPath || 'ffmpeg',
        ffprobePath: raw.ffprobePath || 'ffprobe',
        ffmpegTimeoutSeconds: toNum(raw.ffmpegTimeoutSeconds, 3600),
        ffmpegMaxConcurrency: toNum(raw.ffmpegMaxConcurrency, 2),
        ffmpegThreads: toNum(raw.ffmpegThreads, 0),
        localUnitPrice: toNum(raw.localUnitPrice, 0),
        outputDir: raw.outputDir || '/compose_result/',
        outputResolution: raw.outputResolution || 'FHD',
        codec: raw.codec || 'H.264',
        creditRate: toNum(raw.creditRate, 100),
        profitMultiplier: toNum(raw.profitMultiplier, 1.1)
      };
      let legacy: Record<string, any> = {};
      try { legacy = raw.pricingTiers ? JSON.parse(raw.pricingTiers) : {}; } catch { legacy = {}; }
      PRICE_FIELDS.forEach(({ suffix, legacy: legacyKey }) => {
        values[`tencentPrice${suffix}`] = toNum(raw[`tencentPrice${suffix}`], toNum(legacy[legacyKey], 0));
        values[`aliyunPrice${suffix}`] = toNum(raw[`aliyunPrice${suffix}`], 0);
      });
      form.setFieldsValue(values);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);
  useEffect(() => {
    const current = form.getFieldValue('codec');
    if (current && !codecOptions.some((item) => item.value === current)) {
      form.setFieldValue('codec', 'H.264');
    }
  }, [mode]);

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await saveMediaProcessConfig(values);
      message.success('保存成功');
      await load();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Spin spinning={loading}>
      <PageCard title="媒体处理配置" extra={<Space>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>保存配置</Button>
      </Space>}>
        <Alert type="info" showIcon message={`当前文件存储：${STORAGE_NAMES[storageMode] || storageMode}`} description="处理方式已按当前存储自动筛选：COS 可选腾讯云 MPS 或本地 FFmpeg；OSS 可选阿里云 IMS 或本地 FFmpeg；本地与七牛存储仅可选本地 FFmpeg。云处理地域保存时还会再次校验，超过并发上限的任务继续排队；切换存储厂商前请先停用媒体处理或改用本地 FFmpeg。" style={{ marginBottom: 20 }} />
        <Form form={form} labelCol={{ flex: '165px' }} wrapperCol={{ flex: 'auto' }} labelAlign="right" style={{ maxWidth: 860 }}>
          <SectionTitle title="公共配置" style={{ justifyContent: 'center' }} />
          <Form.Item label="启用媒体处理" name="enabled" valuePropName="checked" extra="关闭后新的整片合成请求会被拒绝">
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>
          <Form.Item label="媒体处理方式" name="processMode" rules={[{ required: true, message: '请选择处理方式' }]}>
            <Select options={processOptions} style={{ maxWidth: 320 }} />
          </Form.Item>
          <Form.Item label="成片输出目录" name="outputDir" rules={[{ required: true, message: '请填写输出目录' }]} extra="只填写桶内目录；输出桶使用文件存储页当前配置，无需重复填写">
            <Input placeholder="/compose_result/" style={{ maxWidth: 360 }} />
          </Form.Item>
          <Form.Item label="默认分辨率档" name="outputResolution" rules={[{ required: true }]}><Select options={RESOLUTIONS} style={{ maxWidth: 240 }} /></Form.Item>
          <Form.Item label="视频编码" name="codec" rules={[{ required: true }]} extra="腾讯云合成仅支持 H.264；阿里云支持 H.264/H.265；本地 FFmpeg 可按已安装编码器选择"><Select options={codecOptions} style={{ maxWidth: 300 }} /></Form.Item>

          {mode === 'tencent-mps' && <>
            <SectionTitle title="腾讯云 MPS" style={{ justifyContent: 'center' }} />
            <Form.Item label="SecretId" name="tencentSecretId" rules={[{ required: enabled, message: '请填写SecretId' }]}><Input autoComplete="off" style={{ maxWidth: 460 }} /></Form.Item>
            <Form.Item label="SecretKey" name="tencentSecretKey" rules={[{ required: enabled, message: '请填写SecretKey' }]} extra="**** 表示已保存；私有 COS 使用原生桶输入输出，请先在腾讯云控制台授权 MPS 访问 COS"><Input.Password autoComplete="new-password" visibilityToggle={false} style={{ maxWidth: 460 }} /></Form.Item>
            <Form.Item label="MPS 地域" name="tencentRegion" rules={[{ required: true }]} extra="必须与 COS 地域一致，保存时自动校验"><Select options={TENCENT_REGIONS} showSearch style={{ maxWidth: 320 }} /></Form.Item>
            <Form.Item label="最大并发任务数" name="tencentMaxConcurrency" rules={[{ required: true }]} extra="达到上限后保留在队列等待"><InputNumber min={1} max={1000} /></Form.Item>
            <Form.Item label="任务回调地址" name="tencentCallbackUrl" extra="可留空依赖轮询；推荐填写 /api/media/callback/mps 的完整 HTTPS 地址"><Input placeholder="https://example.com/api/media/callback/mps" /></Form.Item>
            <PriceFields prefix="tencent" />
          </>}

          {mode === 'aliyun-ims' && <>
            <SectionTitle title="阿里云 IMS" style={{ justifyContent: 'center' }} />
            <Form.Item label="AccessKey ID" name="aliyunAccessKeyId" rules={[{ required: enabled, message: '请填写AccessKey' }]}><Input autoComplete="off" style={{ maxWidth: 460 }} /></Form.Item>
            <Form.Item label="AccessKey Secret" name="aliyunAccessKeySecret" rules={[{ required: enabled, message: '请填写密钥' }]} extra="私有 OSS 由 AliyunICEDefaultRole 读取输入并写入输出；Timeline 素材须位于当前同地域 OSS，外部/CDN 地址不能直接提交"><Input.Password autoComplete="new-password" visibilityToggle={false} style={{ maxWidth: 460 }} /></Form.Item>
            <Form.Item label="IMS 地域" name="aliyunRegion" rules={[{ required: true }]} extra="必须与 OSS Endpoint 地域一致，保存时自动校验"><Select options={ALIYUN_REGIONS} showSearch style={{ maxWidth: 320 }} /></Form.Item>
            <Form.Item label="最大并发任务数" name="aliyunMaxConcurrency" rules={[{ required: true }]} extra="达到上限后继续排队，不判失败"><InputNumber min={1} max={1000} /></Form.Item>
            <Form.Item label="任务回调地址" name="aliyunCallbackUrl" extra="可留空依赖轮询；填写 /api/media/callback/ims 的完整 HTTPS 地址可更快收口"><Input placeholder="https://example.com/api/media/callback/ims" /></Form.Item>
            <PriceFields prefix="aliyun" />
          </>}

          {mode === 'local-ffmpeg' && <>
            <SectionTitle title="本地 FFmpeg" style={{ justifyContent: 'center' }} />
            <Form.Item label="FFmpeg 路径" name="ffmpegPath" rules={[{ required: enabled, message: '请填写FFmpeg路径' }]} extra="已加入 PATH 时填写 ffmpeg，也可填绝对路径"><Input style={{ maxWidth: 560 }} /></Form.Item>
            <Form.Item label="FFprobe 路径" name="ffprobePath" rules={[{ required: enabled, message: '请填写FFprobe路径' }]} extra="用于验证输出并读取实际时长"><Input style={{ maxWidth: 560 }} /></Form.Item>
            <Form.Item label="临时工作目录" name="ffmpegTempDir" extra="留空使用 Java 临时目录；运行账户需要读写权限"><Input style={{ maxWidth: 560 }} /></Form.Item>
            <Form.Item label="字幕字体文件" name="ffmpegFontFile" extra="可选；需要稳定显示中文时填写字体文件绝对路径"><Input style={{ maxWidth: 560 }} /></Form.Item>
            <Form.Item label="任务超时" name="ffmpegTimeoutSeconds" rules={[{ required: true }]}><InputNumber min={60} max={21600} addonAfter="秒" /></Form.Item>
            <Form.Item label="最大并发任务数" name="ffmpegMaxConcurrency" rules={[{ required: true }]} extra="建议按 CPU、内存和磁盘吞吐设置"><InputNumber min={1} max={1000} /></Form.Item>
            <Form.Item label="编码线程数" name="ffmpegThreads" rules={[{ required: true }]} extra="0 表示 FFmpeg 自动选择"><InputNumber min={0} max={256} /></Form.Item>
            <Form.Item label="平台计算费" name="localUnitPrice" rules={[{ required: true }]} extra="默认 0；只在需要收取本机算力费时设置"><InputNumber min={0} step={0.001} addonAfter="元/分钟" /></Form.Item>
          </>}

          <SectionTitle title="结算参数" style={{ justifyContent: 'center' }} />
          <Form.Item label="积分兑换比例" name="creditRate" rules={[{ required: true }]} extra="1 元媒体处理成本折算的积分数"><InputNumber min={1} max={100000} addonBefore="1 元 =" addonAfter="积分" /></Form.Item>
          <Form.Item label="利润倍率" name="profitMultiplier" rules={[{ required: true }]} extra="1.0 为成本价，1.1 表示加价 10%"><InputNumber min={0} max={100} step={0.1} /></Form.Item>
        </Form>
      </PageCard>
    </Spin>
  );
}
