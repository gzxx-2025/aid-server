import React, { useEffect, useMemo, useState } from 'react';
import {
  Badge, Button, Col, Empty, Input, List, Modal, Row, Segmented, Space, Spin, Tag,
  Tooltip, Typography, message
} from 'antd';
import {
  ClearOutlined, CopyOutlined, DeleteOutlined, KeyOutlined, ReloadOutlined, SearchOutlined
} from '@ant-design/icons';

import {
  listCacheName, listCacheKey, getCacheValue,
  clearCacheName, clearCacheKey, clearCacheAll
} from '@/api/monitor/cache';
import PageCard from '@/components/PageCard';

/**
 * 缓存管理面板（需求12 重点优化）
 * - 三栏联动：缓存分组 → 键名 → 内容
 * - 键名内置搜索过滤、数量徽标、单键清理
 * - 内容支持 JSON 美化 / 原文切换、一键复制、元信息展示
 * - 各栏独立刷新、清空二次确认
 */
export default function CacheManagePanel() {
  const [names, setNames] = useState<any[]>([]);
  const [keys, setKeys] = useState<string[]>([]);
  const [currentName, setCurrentName] = useState<any | null>(null);
  const [currentKey, setCurrentKey] = useState<string | null>(null);
  const [content, setContent] = useState<any | null>(null);

  const [keyFilter, setKeyFilter] = useState('');
  const [viewMode, setViewMode] = useState<'pretty' | 'raw'>('pretty');

  const [namesLoading, setNamesLoading] = useState(false);
  const [keysLoading, setKeysLoading] = useState(false);
  const [valueLoading, setValueLoading] = useState(false);

  const loadNames = async () => {
    setNamesLoading(true);
    try {
      const res: any = await listCacheName();
      setNames(res.data || []);
    } finally {
      setNamesLoading(false);
    }
  };

  const loadKeys = async (name: string) => {
    setKeysLoading(true);
    try {
      const res: any = await listCacheKey(name);
      setKeys(res.data || []);
      setCurrentKey(null);
      setContent(null);
      setKeyFilter('');
    } finally {
      setKeysLoading(false);
    }
  };

  const loadValue = async (name: string, key: string) => {
    setValueLoading(true);
    try {
      const res: any = await getCacheValue(name, key);
      setContent(res.data);
    } finally {
      setValueLoading(false);
    }
  };

  useEffect(() => {
    loadNames();
  }, []);

  const filteredKeys = useMemo(() => {
    if (!keyFilter.trim()) return keys;
    const kw = keyFilter.trim().toLowerCase();
    return keys.filter((k) => k.toLowerCase().includes(kw));
  }, [keys, keyFilter]);

  const rawValue = useMemo(() => {
    if (!content || content.cacheValue == null) return '';
    return typeof content.cacheValue === 'object'
      ? JSON.stringify(content.cacheValue)
      : String(content.cacheValue);
  }, [content]);

  const prettyValue = useMemo(() => {
    if (!rawValue) return '';
    try {
      return JSON.stringify(JSON.parse(rawValue), null, 2);
    } catch {
      return rawValue;
    }
  }, [rawValue]);

  const isJson = useMemo(() => {
    if (!rawValue) return false;
    try {
      JSON.parse(rawValue);
      return true;
    } catch {
      return false;
    }
  }, [rawValue]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(viewMode === 'pretty' && isJson ? prettyValue : rawValue);
      message.success('已复制到剪贴板');
    } catch {
      message.warning('当前环境不支持自动复制');
    }
  };

  const confirmClearAll = () => {
    Modal.confirm({
      title: '确认清空全部缓存？',
      content: '该操作将清空 Redis 中由系统管理的全部缓存分组，请谨慎操作。',
      okType: 'danger',
      okText: '清空全部',
      onOk: async () => {
        await clearCacheAll();
        message.success('已清空全部缓存');
        setKeys([]);
        setCurrentName(null);
        setCurrentKey(null);
        setContent(null);
        loadNames();
      }
    });
  };

  const panelStyle: React.CSSProperties = { height: 'calc(100vh - 320px)', minHeight: 420, overflow: 'auto' };

  return (
    <Row gutter={16}>
      {/* 缓存分组 */}
      <Col flex="300px">
        <PageCard
          title={<Space><KeyOutlined />缓存分组 <Badge count={names.length} color="#2563eb" /></Space>}
          extra={
            <Space size={4}>
              <Tooltip title="刷新"><Button size="small" icon={<ReloadOutlined />} onClick={loadNames} /></Tooltip>
              <Tooltip title="清空全部缓存">
                <Button size="small" danger icon={<ClearOutlined />} onClick={confirmClearAll} />
              </Tooltip>
            </Space>
          }
        >
          <Spin spinning={namesLoading}>
            <div style={panelStyle}>
              {names.length ? (
                <List
                  size="small"
                  dataSource={names}
                  renderItem={(item: any) => (
                    <List.Item
                      style={{
                        cursor: 'pointer',
                        background: currentName?.cacheName === item.cacheName ? 'rgba(37,99,235,0.08)' : undefined,
                        borderRadius: 8,
                        padding: '8px 10px'
                      }}
                      onClick={() => {
                        setCurrentName(item);
                        loadKeys(item.cacheName);
                      }}
                      actions={[
                        <Tooltip title="清理该分组" key="clean">
                          <Button
                            size="small"
                            type="text"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={async (e) => {
                              e.stopPropagation();
                              await clearCacheName(item.cacheName);
                              message.success('已清理分组');
                              if (currentName?.cacheName === item.cacheName) {
                                setKeys([]);
                                setContent(null);
                              }
                              loadNames();
                            }}
                          />
                        </Tooltip>
                      ]}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {item.cacheName}
                        </div>
                        <div style={{ color: '#94a3b8', fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {item.remark || '—'}
                        </div>
                      </div>
                    </List.Item>
                  )}
                />
              ) : (
                <Empty description="暂无缓存分组" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </div>
          </Spin>
        </PageCard>
      </Col>

      {/* 键名列表 */}
      <Col flex="340px">
        <PageCard
          title={<Space>键名 <Badge count={filteredKeys.length} color="#16a34a" /></Space>}
          extra={
            currentName ? (
              <Button size="small" icon={<ReloadOutlined />} onClick={() => loadKeys(currentName.cacheName)} />
            ) : null
          }
        >
          <Input
            allowClear
            prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
            placeholder="搜索键名"
            value={keyFilter}
            onChange={(e) => setKeyFilter(e.target.value)}
            style={{ marginBottom: 10 }}
            disabled={!currentName}
          />
          <Spin spinning={keysLoading}>
            <div style={{ ...panelStyle, height: 'calc(100vh - 372px)' }}>
              {currentName ? (
                filteredKeys.length ? (
                  <List
                    size="small"
                    dataSource={filteredKeys}
                    renderItem={(k) => (
                      <List.Item
                        style={{
                          cursor: 'pointer',
                          background: currentKey === k ? 'rgba(37,99,235,0.08)' : undefined,
                          borderRadius: 8,
                          padding: '8px 10px'
                        }}
                        onClick={() => {
                          setCurrentKey(k);
                          loadValue(currentName.cacheName, k);
                        }}
                        actions={[
                          <Tooltip title="删除该键" key="del">
                            <Button
                              size="small"
                              type="text"
                              danger
                              icon={<DeleteOutlined />}
                              onClick={async (e) => {
                                e.stopPropagation();
                                await clearCacheKey(k);
                                message.success('已删除键');
                                if (currentKey === k) setContent(null);
                                loadKeys(currentName.cacheName);
                              }}
                            />
                          </Tooltip>
                        ]}
                      >
                        <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>{k}</div>
                      </List.Item>
                    )}
                  />
                ) : (
                  <Empty description={keyFilter ? '无匹配键名' : '该分组暂无键'} image={Empty.PRESENTED_IMAGE_SIMPLE} />
                )
              ) : (
                <Empty description="请先在左侧选择缓存分组" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </div>
          </Spin>
        </PageCard>
      </Col>

      {/* 缓存内容 */}
      <Col flex="auto" style={{ minWidth: 0 }}>
        <PageCard
          title="缓存内容"
          extra={
            content ? (
              <Space>
                {isJson && (
                  <Segmented
                    size="small"
                    value={viewMode}
                    onChange={(v) => setViewMode(v as any)}
                    options={[
                      { label: 'JSON', value: 'pretty' },
                      { label: '原文', value: 'raw' }
                    ]}
                  />
                )}
                <Button size="small" icon={<CopyOutlined />} onClick={handleCopy}>复制</Button>
              </Space>
            ) : null
          }
        >
          <Spin spinning={valueLoading}>
            {content ? (
              <>
                <Space wrap style={{ marginBottom: 12 }}>
                  <Tag color="blue">分组：{content.cacheName}</Tag>
                  <Tag color="geekblue" style={{ maxWidth: 420, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    键名：{content.cacheKey}
                  </Tag>
                  <Tag>{isJson ? 'JSON' : '文本'}</Tag>
                  <Tag>长度：{rawValue.length}</Tag>
                </Space>
                <Input.TextArea
                  value={viewMode === 'pretty' && isJson ? prettyValue : rawValue}
                  autoSize={{ minRows: 18, maxRows: 28 }}
                  readOnly
                  style={{ fontFamily: 'Menlo, Monaco, Consolas, monospace', fontSize: 13, background: '#0f172a08' }}
                />
              </>
            ) : (
              <div style={{ padding: 60 }}>
                <Empty description="请选择缓存键查看内容" />
              </div>
            )}
          </Spin>
        </PageCard>
      </Col>
    </Row>
  );
}
