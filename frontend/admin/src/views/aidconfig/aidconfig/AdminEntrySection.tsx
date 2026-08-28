import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Space, Switch, Tag, Tooltip, message } from 'antd';
import {
  CopyOutlined, ExportOutlined, ReloadOutlined, SafetyCertificateOutlined, SaveOutlined, SyncOutlined
} from '@ant-design/icons';

import { listAidconfig, updateAidconfig, addAidconfig } from '@/api/aidconfig/aidconfig';
import { resolveAppUrl } from '@/utils/ruoyi';
import './style.less';

const CATEGORY = 'admin_entry';
const KEY_ENABLED = 'enabled';
const KEY_CODE = 'access_code';
const KEY_RATE = 'rate_limit_per_min';
const CODE_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

interface CfgItem {
  id?: number;
  configName: string;
  configValue: string;
}

/** 生成 12 位（大小写字母 + 数字）随机访问码 */
function genCode(): string {
  if (!window.crypto?.getRandomValues) {
    throw new Error('当前浏览器不支持安全随机数');
  }
  let s = '';
  const arr = new Uint32Array(12);
  window.crypto.getRandomValues(arr);
  for (let i = 0; i < arr.length; i++) s += CODE_CHARS[arr[i] % CODE_CHARS.length];
  return s;
}

/**
 * 后台登录入口安全配置（需求：可配置的随机登录路径）。
 * 开启后只有 {@code 站点/<访问码>} 能进入登录页，其它页面登录后正常访问；与后端 aid_config(admin_entry) 联动。
 */
export default function AdminEntrySection() {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [code, setCode] = useState('');
  const [rateLimit, setRateLimit] = useState<number>(10);
  const [items, setItems] = useState<Record<string, CfgItem>>({});

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await listAidconfig({ pageNum: 1, pageSize: 1000, category: CATEGORY });
      const rows: any[] = (res.rows || res.data || []).filter((r: any) => r.category === CATEGORY);
      const map: Record<string, CfgItem> = {};
      rows.forEach((r) => { map[r.configName] = { id: r.id, configName: r.configName, configValue: r.configValue }; });
      setItems(map);
      setEnabled(['true', 'Y', '1'].includes(String(map[KEY_ENABLED]?.configValue || '').trim()));
      setCode(map[KEY_CODE]?.configValue || '');
      const rl = parseInt(String(map[KEY_RATE]?.configValue ?? '').trim(), 10);
      setRateLimit(Number.isFinite(rl) ? rl : 10);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loginUrl = useMemo(() => {
    const origin = window.location.origin;
    return `${origin}${resolveAppUrl(enabled && code ? `/${code}` : '/')}`;
  }, [enabled, code]);

  /** 持久化单个配置项（存在则更新，否则新增） */
  const persist = async (name: string, value: string, dict: string, order: number) => {
    const it = items[name];
    if (it?.id) {
      await updateAidconfig({ id: it.id, configValue: value });
    } else {
      await addAidconfig({ category: CATEGORY, configName: name, configValue: value, configDict: dict, delFlag: '0', orderNum: order });
    }
  };

  const handleSave = async () => {
    if (enabled && (!code || code.length < 8)) {
      message.warning('启用前请先生成访问码（至少8位）');
      return;
    }
    setSaving(true);
    try {
      await persist(KEY_ENABLED, enabled ? 'true' : 'false', '是否启用随机登录入口(true/false)', 1);
      await persist(KEY_CODE, code || '', '后台登录访问码(至少8位大小写字母+数字)', 2);
      await persist(KEY_RATE, String(rateLimit ?? 10), '单IP每分钟尝试次数(<=0不限流)', 3);
      message.success('已保存，登录入口实时生效（无需重启）');
      await load();
    } finally {
      setSaving(false);
    }
  };

  const handleGenerate = () => {
    try {
      setCode(genCode());
      message.info('已生成新访问码，记得点击「保存」生效');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '生成访问码失败');
    }
  };

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制');
    } catch {
      message.warning('当前环境不支持自动复制，请手动选择复制');
    }
  };

  return (
    <div className="config-section">
      <Card
        bordered={false}
        className="page-card"
        title={<Space><SafetyCertificateOutlined className="config-section__icon" />后台登录入口安全</Space>}
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>保存</Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="开启后，后台登录页只能通过「站点地址 + 访问码」进入，直接访问 /login 或站点根路径都会被挡到 404，提升安全性。登录之后的所有功能页面不受影响。"
        />

        <Form layout="horizontal" labelCol={{ flex: '150px' }} wrapperCol={{ flex: 'auto' }} labelAlign="right">
          <Form.Item label="启用随机入口">
            <Space>
              <Switch checked={enabled} onChange={setEnabled} checkedChildren="开" unCheckedChildren="关" />
              {enabled
                ? <Tag color="green">已开启（仅 /访问码 可登录）</Tag>
                : <Tag>未开启（默认入口 /login）</Tag>}
            </Space>
          </Form.Item>

          <Form.Item label="访问码">
            <Space wrap>
              <Input
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/[^A-Za-z0-9]/g, '').slice(0, 32))}
                placeholder="点击右侧生成 12 位随机码"
                style={{ width: 260, fontFamily: 'Consolas, Menlo, monospace', letterSpacing: 1 }}
                maxLength={32}
              />
              <Button icon={<SyncOutlined />} onClick={handleGenerate}>生成随机码</Button>
              {code && (
                <Tooltip title="复制访问码">
                  <Button icon={<CopyOutlined />} onClick={() => handleCopy(code)} />
                </Tooltip>
              )}
            </Space>
          </Form.Item>

          <Form.Item
            label="单IP每分钟尝试"
            extra="登录与访问码校验共用此阈值；填 0 表示不限流。默认 10。"
          >
            <InputNumber
              value={rateLimit}
              onChange={(v) => setRateLimit(Number(v ?? 0))}
              min={0}
              max={1000}
              style={{ width: 160 }}
              addonAfter="次/分钟"
            />
          </Form.Item>

          <Form.Item label="登录地址" style={{ marginBottom: 0 }}>
            <Space wrap>
              <Input
                readOnly
                value={loginUrl}
                style={{ width: 420, color: enabled && code ? '#16a34a' : undefined }}
              />
              <Tooltip title="复制登录地址">
                <Button icon={<CopyOutlined />} onClick={() => handleCopy(loginUrl)}>复制链接</Button>
              </Tooltip>
              <Tooltip title="在新标签打开">
                <Button icon={<ExportOutlined />} onClick={() => window.open(loginUrl, '_blank')} />
              </Tooltip>
            </Space>
          </Form.Item>
        </Form>

        {enabled && (
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 16 }}
            message="请妥善保存上面的登录地址；若遗忘，可在数据库 aid_config(category=admin_entry, config_name=access_code) 中查回，或在此重新生成并保存。"
          />
        )}
      </Card>
    </div>
  );
}
