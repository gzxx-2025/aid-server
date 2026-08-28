import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Input, Modal, Select, Space, Switch, Tag, Tooltip, Typography, message } from 'antd';
import {
  ApiOutlined, CloudSyncOutlined, ExclamationCircleOutlined, GlobalOutlined, SaveOutlined, ThunderboltOutlined
} from '@ant-design/icons';

import {
  applyOfficialApi, fetchOfficialApi, getOfficialGateway, getUpgradeStatus, saveOfficialGateway
} from '@/api/aidconfig/upgrade';
import type { Model, Provider } from './types';

const { Text, Paragraph } = Typography;
const DEFAULT_OFFICIAL_API_BASE_URL = 'https://api.aidstudio.com.cn';

interface Props {
  /** 全量模型列表（供例外模型选择） */
  models: Model[];
  /** 厂商列表（供例外厂商选择，并为例外模型分组） */
  providers: Provider[];
}

/**
 * 官方API统一网关卡片：一键替代全部厂商网关，支持例外厂商/例外模型仍走自有网关
 */
export default function OfficialGatewayCard({ models, providers }: Props) {
  const [enabled, setEnabled] = useState(false);
  const [baseUrl, setBaseUrl] = useState(DEFAULT_OFFICIAL_API_BASE_URL);
  const [apiKey, setApiKey] = useState('');
  const [apiKeyMasked, setApiKeyMasked] = useState<string | undefined>();
  const [hasApiKey, setHasApiKey] = useState(false);
  const [excludedIds, setExcludedIds] = useState<number[]>([]);
  const [excludedProviderIds, setExcludedProviderIds] = useState<number[]>([]);
  const [remoteChanged, setRemoteChanged] = useState(false);
  const [websiteUrl, setWebsiteUrl] = useState('');
  const [saving, setSaving] = useState(false);
  const [fetching, setFetching] = useState(false);

  const loadSetting = useCallback(async () => {
    const res = await getOfficialGateway();
    const data = res.data;
    setEnabled(!!data?.enabled);
    setBaseUrl(data?.baseUrl || DEFAULT_OFFICIAL_API_BASE_URL);
    setApiKeyMasked(data?.apiKeyMasked);
    setHasApiKey(!!data?.hasApiKey);
    setExcludedIds(data?.excludedModelIds || []);
    setExcludedProviderIds(data?.excludedProviderIds || []);
    setApiKey('');
  }, []);

  useEffect(() => {
    loadSetting().catch(() => undefined);
    // 读取缓存态升级状态：官方地址变化提醒角标 + 官方API官网跳转入口（不自动拉取远端）
    getUpgradeStatus()
      .then((res) => {
        setRemoteChanged(!!res.data?.officialApi?.changed);
        setWebsiteUrl(res.data?.officialApi?.websiteUrl || '');
      })
      .catch(() => setRemoteChanged(false));
  }, [loadSetting]);

  /** 例外厂商下拉：整个厂商例外后，其下全部模型均走自有网关 */
  const providerOptions = useMemo(
    () =>
      providers.map((p) => ({
        value: p.id,
        rawLabel: `${p.providerName || ''} ${p.providerCode || ''}`.toLowerCase(),
        label: (
          <span>
            <span style={{ fontWeight: 500 }}>{p.providerName || p.providerCode}</span>
            <span style={{ color: '#94a3b8', marginLeft: 8, fontSize: 12 }}>{p.providerCode}</span>
          </span>
        )
      })),
    [providers]
  );

  /** 例外模型下拉：按厂商分组，支持按名称/编码搜索 */
  const excludeOptions = useMemo(() => {
    const providerNameMap = new Map<number, string>();
    providers.forEach((p) => providerNameMap.set(p.id, p.providerName));
    const grouped = new Map<number, Model[]>();
    models.forEach((m) => {
      if (!m.id || !m.providerId) return;
      const list = grouped.get(m.providerId) || [];
      list.push(m);
      grouped.set(m.providerId, list);
    });
    return Array.from(grouped.entries()).map(([providerId, list]) => ({
      label: providerNameMap.get(providerId) || `厂商 ${providerId}`,
      options: list.map((m) => ({
        value: m.id as number,
        rawLabel: `${m.modelName || ''} ${m.modelCode || ''}`.toLowerCase(),
        label: (
          <span>
            <span style={{ fontWeight: 500 }}>{m.modelName || m.modelCode}</span>
            <span style={{ color: '#94a3b8', marginLeft: 8, fontSize: 12 }}>{m.modelCode}</span>
            {m.modelType && (
              <Tag bordered={false} color="purple" style={{ marginLeft: 6, fontSize: 11, lineHeight: '16px' }}>
                {m.modelType}
              </Tag>
            )}
          </span>
        )
      }))
    }));
  }, [models, providers]);

  /** 保存开关/地址/Key/例外模型/例外厂商；密钥留空表示不修改 */
  const handleSave = async (nextEnabled: boolean) => {
    if (nextEnabled && !baseUrl.trim()) {
      message.warning('请先填写官方网关地址');
      return;
    }
    setSaving(true);
    try {
      await saveOfficialGateway({
        enabled: nextEnabled,
        baseUrl: baseUrl.trim(),
        apiKey: apiKey.trim() || undefined,
        excludedModelIds: excludedIds,
        excludedProviderIds
      });
      message.success('官方网关配置已保存');
      setEnabled(nextEnabled);
      await loadSetting();
    } finally {
      setSaving(false);
    }
  };

  /** 开关切换需二次确认，说明全局影响与例外模型规则 */
  const handleToggle = (checked: boolean) => {
    Modal.confirm({
      title: checked ? '启用官方统一网关？' : '关闭官方统一网关？',
      icon: <ExclamationCircleOutlined />,
      content: checked
        ? '启用后，所有厂商模型调用统一走官方运营的API网关地址与Key，无需逐个配置各厂商网关；协议完全遵循原厂商。官方网关暂不支持的可加入下方「例外厂商」或「例外模型」，例外的继续走自有厂商网关。'
        : '关闭后，全部模型恢复使用各自厂商配置的网关地址与Key。',
      okText: checked ? '启用' : '关闭',
      cancelText: '取消',
      onOk: () => handleSave(checked)
    });
  };

  /** 手动获取官方最新地址：仅变化时提醒应用 */
  const handleFetchRemote = async () => {
    setFetching(true);
    try {
      const res = await fetchOfficialApi();
      const data = res.data;
      // 官网地址随手动获取同步刷新
      if (data?.websiteUrl) {
        setWebsiteUrl(data.websiteUrl);
      }
      if (!data?.remoteBaseUrl) {
        message.info('更新清单未提供官方API地址');
        return;
      }
      if (!data.changed) {
        setRemoteChanged(false);
        message.success('官方API地址无变化');
        return;
      }
      Modal.confirm({
        title: '官方API地址有更新',
        icon: <ExclamationCircleOutlined />,
        content: (
          <div>
            <Paragraph><Text type="secondary">当前：</Text>{data.localBaseUrl || '（未配置）'}</Paragraph>
            <Paragraph><Text type="secondary">最新：</Text><Text strong>{data.remoteBaseUrl}</Text></Paragraph>
          </div>
        ),
        okText: '应用最新地址',
        cancelText: '暂不应用',
        onOk: async () => {
          await applyOfficialApi();
          setRemoteChanged(false);
          message.success('官方API地址已更新');
          await loadSetting();
        }
      });
    } finally {
      setFetching(false);
    }
  };

  return (
    <div className="official-gateway-card">
      <div className="official-gateway-card__head">
        <div className="official-gateway-card__brand">
          <ThunderboltOutlined className="official-gateway-card__brand-icon" />
          <div>
            <div className="official-gateway-card__title">
              官方API统一网关
              {enabled
                ? <Tag color="green" style={{ marginLeft: 8 }}>已启用 · 全局厂商走官方网关</Tag>
                : <Tag style={{ marginLeft: 8 }}>未启用 · 各厂商使用自有网关</Tag>}
              {enabled && excludedProviderIds.length > 0 && (
                <Tag color="orange">{excludedProviderIds.length} 个例外厂商走自有网关</Tag>
              )}
              {enabled && excludedIds.length > 0 && (
                <Tag color="orange">{excludedIds.length} 个例外模型走自有网关</Tag>
              )}
            </div>
            <div className="official-gateway-card__desc">
              填写官方运营的API地址与Key，一键替代所有厂商网关，无需逐个配置；协议完全遵循原厂商。
              {websiteUrl && (
                <a
                  className="official-gateway-card__site-link"
                  href={websiteUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  <GlobalOutlined /> 前往官方API官网
                </a>
              )}
            </div>
          </div>
        </div>
        <Space>
          <Tooltip title="从版本更新清单获取官方最新API地址，仅地址变化时提醒">
            <Button icon={<CloudSyncOutlined />} loading={fetching} onClick={handleFetchRemote}>
              手动获取最新地址
              {remoteChanged && <Tag color="gold" style={{ marginLeft: 6 }}>有更新</Tag>}
            </Button>
          </Tooltip>
          <Switch
            checked={enabled}
            checkedChildren="官方网关"
            unCheckedChildren="厂商自有"
            loading={saving}
            onChange={handleToggle}
          />
        </Space>
      </div>
      <div className="official-gateway-card__form">
        <Input
          addonBefore={<><ApiOutlined /> 官方网关地址</>}
          placeholder={DEFAULT_OFFICIAL_API_BASE_URL}
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
          allowClear
        />
        <Input.Password
          addonBefore="官方API Key"
          placeholder={hasApiKey ? `已配置（${apiKeyMasked}），留空表示不修改` : '请输入官方API Key'}
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          autoComplete="new-password"
        />
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => handleSave(enabled)}>
          保存配置
        </Button>
      </div>
      {enabled && (
        <div className="official-gateway-card__exclude">
          <div className="official-gateway-card__exclude-label">
            例外厂商
            <Text type="secondary" style={{ fontWeight: 400, marginLeft: 8, fontSize: 12 }}>
              整个厂商例外后，其下全部模型继续使用该厂商配置的网关与Key；修改后点「保存配置」生效
            </Text>
          </div>
          <Select
            mode="multiple"
            allowClear
            showSearch
            value={excludedProviderIds}
            onChange={(ids) => setExcludedProviderIds(ids as number[])}
            placeholder="选择仍走自有网关的厂商（可按名称/编码搜索）"
            style={{ width: '100%' }}
            options={providerOptions}
            optionFilterProp="rawLabel"
            filterOption={(input, option: any) => String(option?.rawLabel || '').includes(input.toLowerCase())}
            maxTagCount="responsive"
          />
          <div className="official-gateway-card__exclude-label" style={{ marginTop: 12 }}>
            例外模型
            <Text type="secondary" style={{ fontWeight: 400, marginLeft: 8, fontSize: 12 }}>
              官方网关暂不支持的模型在此选择，它们将继续使用各自厂商配置的网关与Key；修改后点「保存配置」生效
            </Text>
          </div>
          <Select
            mode="multiple"
            allowClear
            showSearch
            value={excludedIds}
            onChange={(ids) => setExcludedIds(ids as number[])}
            placeholder="选择仍走自有厂商网关的模型（可按名称/编码搜索）"
            style={{ width: '100%' }}
            options={excludeOptions}
            optionFilterProp="rawLabel"
            filterOption={(input, option: any) => String(option?.rawLabel || '').includes(input.toLowerCase())}
            maxTagCount="responsive"
          />
        </div>
      )}
    </div>
  );
}
