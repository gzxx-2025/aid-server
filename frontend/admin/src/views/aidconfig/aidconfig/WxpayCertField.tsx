import React, { useState } from 'react';
import { Button, Input, Upload, message } from 'antd';
import { EyeInvisibleOutlined, EyeOutlined, UploadOutlined } from '@ant-design/icons';
import { parseWxpayCert } from '@/api/aidconfig/aidconfig';

interface Props {
  /** Config item name: privateKey | serialNo | publicKey */
  name: string;
  value: string;
  onChange: (v: string) => void;
}

const FIELD_META: Record<string, {
  fileName: string;
  keyword: string;
  uploadText: string;
  successText: string;
  placeholder: string;
  helpText: React.ReactNode;
}> = {
  privateKey: {
    fileName: 'apiclient_key.pem',
    keyword: 'PRIVATE KEY',
    uploadText: '上传 apiclient_key.pem',
    successText: '已读取商户私钥内容，请保存配置',
    placeholder: '上传 apiclient_key.pem，或粘贴 -----BEGIN PRIVATE KEY----- 内容',
    helpText: <>商户私钥来自 <code>apiclient_key.pem</code>，用于商户请求签名。</>
  },
  publicKey: {
    fileName: 'pub_key.pem',
    keyword: 'PUBLIC KEY',
    uploadText: '上传 pub_key.pem',
    successText: '已读取微信支付公钥内容，请保存配置',
    placeholder: '上传 pub_key.pem，或粘贴 -----BEGIN PUBLIC KEY----- 内容',
    helpText: <>微信支付公钥来自商户平台 API 安全页下载的 <code>pub_key.pem</code>，用于微信应答与回调验签。</>
  }
};

/**
 * WeChat Pay V3 certificate and public key field editor.
 */
export default function WxpayCertField({ name, value, onChange }: Props) {
  const [parsing, setParsing] = useState(false);
  const [showKey, setShowKey] = useState(false);

  if (name === 'privateKey' || name === 'publicKey') {
    const meta = FIELD_META[name];
    const beforePemUpload = (file: File) => {
      const reader = new FileReader();
      reader.onload = () => {
        const text = String(reader.result || '').trim();
        if (!text.includes('BEGIN') || !text.toUpperCase().includes(meta.keyword)) {
          message.error(`该文件不是有效的 PEM 文件，请上传 ${meta.fileName}`);
          return;
        }
        onChange(text);
        message.success(meta.successText);
      };
      reader.onerror = () => message.error('读取文件失败');
      reader.readAsText(file);
      return false;
    };

    return (
      <div style={{ width: '100%' }}>
        <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
          <Upload beforeUpload={beforePemUpload} maxCount={1} showUploadList={false} accept=".pem,.txt">
            <Button size="small" icon={<UploadOutlined />}>{meta.uploadText}</Button>
          </Upload>
          <Button
            size="small"
            type="text"
            icon={showKey ? <EyeInvisibleOutlined /> : <EyeOutlined />}
            onClick={() => setShowKey((s) => !s)}
          >
            {showKey ? '隐藏' : '查看'}
          </Button>
        </div>
        <Input.TextArea
          value={showKey ? value : maskPem(value)}
          onChange={(e) => onChange(e.target.value)}
          onFocus={() => setShowKey(true)}
          rows={4}
          placeholder={meta.placeholder}
          style={{ fontFamily: 'Menlo, Monaco, Consolas, monospace', fontSize: 12 }}
        />
        <div style={{ marginTop: 4, color: '#94a3b8', fontSize: 12 }}>{meta.helpText}</div>
      </div>
    );
  }

  const beforeCertUpload = async (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    setParsing(true);
    try {
      const res: any = await parseWxpayCert(fd);
      const data = res?.data ?? res;
      if (data?.serialNo) {
        onChange(data.serialNo);
        message.success(
          '已解析证书序列号' + (data.notAfter ? `（证书有效期至 ${data.notAfter}）` : '') + '，请保存配置'
        );
      } else {
        message.error(res?.msg || '未解析到证书序列号');
      }
    } catch (e: any) {
      message.error(e?.message || '证书解析失败');
    } finally {
      setParsing(false);
    }
    return false;
  };

  return (
    <div style={{ width: '100%' }}>
      <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
        <Upload beforeUpload={beforeCertUpload} maxCount={1} showUploadList={false} accept=".pem,.crt,.cer">
          <Button size="small" icon={<UploadOutlined />} loading={parsing}>
            上传 apiclient_cert.pem 解析序列号
          </Button>
        </Upload>
      </div>
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="上传 apiclient_cert.pem 自动解析，或手动填写证书序列号"
        allowClear
      />
      <div style={{ marginTop: 4, color: '#94a3b8', fontSize: 12 }}>
        序列号取自商户 API 证书 <code>apiclient_cert.pem</code>，不是微信支付公钥 ID。
      </div>
    </div>
  );
}

function maskPem(v: string): string {
  if (!v) return '';
  const s = String(v);
  if (s.length <= 40) return '********';
  return s.slice(0, 32) + '\n*** 已隐藏，点击查看或聚焦编辑 ***\n' + s.slice(-28);
}
