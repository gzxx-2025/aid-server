import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Col, Empty, Form, Input, List, Popconfirm, Row, Select, Space,
  Switch, Tabs, Tag, Typography
} from 'antd';
import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  MAX_RESOURCE_BYTES, MAX_RESOURCE_TOTAL_BYTES, utf8ByteLength
} from './skillPackageUtils';

export interface EditableSkillResource {
  id?: number;
  resourceKey: string;
  resourceType: string;
  mimeType: string;
  content: string;
  routeJson: string;
  objectKey?: string;
  contentDigest?: string;
  sizeBytes?: number;
}

interface SkillResourceEditorProps {
  value?: EditableSkillResource[];
  onChange?: (value: EditableSkillResource[]) => void;
  disabled?: boolean;
}

type RouteConfig = Record<string, unknown> & {
  always?: boolean;
  operations?: string[];
  keywords?: string[];
};

function parseRoute(value?: string): { route?: RouteConfig; error?: string } {
  if (!value?.trim()) return { route: {} };
  try {
    const parsed = JSON.parse(value) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { error: '路由配置根节点必须是 JSON 对象' };
    }
    return { route: parsed as RouteConfig };
  } catch {
    return { error: '路由配置不是有效 JSON' };
  }
}

function stringArray(value: unknown) {
  return Array.isArray(value)
    ? value.map(String).map((item) => item.trim()).filter(Boolean)
    : [];
}

function nextResourceKey(resources: EditableSkillResource[]) {
  let index = resources.length + 1;
  while (resources.some((item) => item.resourceKey === `reference-${index}`)) index += 1;
  return `reference-${index}`;
}

/** 编辑草稿包中的 Markdown 资源和确定性路由；摘要与对象路径仅作只读参考。 */
export default function SkillResourceEditor({ value, onChange, disabled }: SkillResourceEditorProps) {
  const resources = useMemo(() => value || [], [value]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [sizeError, setSizeError] = useState('');
  const selected = resources[selectedIndex];
  const routeResult = useMemo(() => parseRoute(selected?.routeJson), [selected?.routeJson]);
  const resourceSizes = useMemo(() => resources.map((item) => utf8ByteLength(item.content || '')), [resources]);
  const selectedBytes = resourceSizes[selectedIndex] || 0;
  const totalBytes = resourceSizes.reduce((sum, size) => sum + size, 0);
  const oversizedIndex = resourceSizes.findIndex((size) => size > MAX_RESOURCE_BYTES);
  const contentLimitError = oversizedIndex >= 0
    ? `资源 ${oversizedIndex + 1} 内容不能超过 100 KiB（UTF-8）`
    : totalBytes > MAX_RESOURCE_TOTAL_BYTES ? '资源内容合计不能超过 512 KiB（UTF-8）' : undefined;

  useEffect(() => {
    if (resources.length === 0) setSelectedIndex(0);
    else if (selectedIndex >= resources.length) setSelectedIndex(resources.length - 1);
  }, [resources.length, selectedIndex]);

  useEffect(() => { setSizeError(''); }, [selectedIndex]);

  const updateResource = (patch: Partial<EditableSkillResource>) => {
    if (!selected) return;
    const next = [...resources];
    next[selectedIndex] = { ...selected, ...patch };
    onChange?.(next);
  };
  const updateContent = (content: string) => {
    if (!selected) return;
    const nextBytes = utf8ByteLength(content);
    const nextTotalBytes = totalBytes - selectedBytes + nextBytes;
    if (nextBytes > MAX_RESOURCE_BYTES && nextBytes >= selectedBytes) {
      setSizeError('单项资源内容不能超过 100 KiB（UTF-8）');
      return;
    }
    if (nextTotalBytes > MAX_RESOURCE_TOTAL_BYTES && nextTotalBytes >= totalBytes) {
      setSizeError('资源内容合计不能超过 512 KiB（UTF-8）');
      return;
    }
    setSizeError('');
    updateResource({ content });
  };
  const updateRoute = (mutator: (route: RouteConfig) => void) => {
    if (!routeResult.route) return;
    const next = JSON.parse(JSON.stringify(routeResult.route)) as RouteConfig;
    mutator(next);
    updateResource({ routeJson: JSON.stringify(next) });
  };
  const addResource = () => {
    const next = [...resources, {
      resourceKey: nextResourceKey(resources),
      resourceType: 'REFERENCE',
      mimeType: 'text/markdown',
      content: '',
      routeJson: '{}'
    }];
    onChange?.(next);
    setSelectedIndex(next.length - 1);
  };
  const removeResource = () => {
    if (!selected) return;
    onChange?.(resources.filter((_, index) => index !== selectedIndex));
    setSelectedIndex(Math.max(0, selectedIndex - 1));
  };
  const moveResource = (offset: -1 | 1) => {
    const targetIndex = selectedIndex + offset;
    if (!selected || targetIndex < 0 || targetIndex >= resources.length) return;
    const next = [...resources];
    [next[selectedIndex], next[targetIndex]] = [next[targetIndex], next[selectedIndex]];
    onChange?.(next);
    setSelectedIndex(targetIndex);
  };
  const duplicateKey = !!selected?.resourceKey
    && resources.some((item, index) => index !== selectedIndex && item.resourceKey === selected.resourceKey);

  return <Row gutter={16} wrap={false}>
    <Col flex="280px">
      <Card size="small" title={`资源（${resources.length}）`} extra={!disabled && <Space size={0}>
        <Button type="text" aria-label="上移资源" disabled={!selected || selectedIndex === 0}
          icon={<ArrowUpOutlined />} onClick={() => moveResource(-1)} />
        <Button type="text" aria-label="下移资源" disabled={!selected || selectedIndex === resources.length - 1}
          icon={<ArrowDownOutlined />} onClick={() => moveResource(1)} />
        <Button type="text" icon={<PlusOutlined />} disabled={resources.length >= 64}
          onClick={addResource}>新增</Button>
      </Space>}>
        <Alert style={{ marginBottom: 8 }} type="info" showIcon message="靠前规则优先"
          description="运行时按当前顺序匹配，最多注入 4 项。" />
        {resources.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无资源" /> : <List
          size="small"
          dataSource={resources}
          renderItem={(item, index) => <List.Item onClick={() => setSelectedIndex(index)}
            style={{ cursor: 'pointer', paddingInline: 8,
              background: index === selectedIndex ? 'var(--ant-color-primary-bg)' : undefined }}>
            <Space direction="vertical" size={0} style={{ minWidth: 0 }}>
              <Typography.Text strong={index === selectedIndex} ellipsis>{item.resourceKey || '未命名资源'}</Typography.Text>
              <Space size={4}><Tag bordered={false}>{item.resourceType || 'REFERENCE'}</Tag>
                {parseRoute(item.routeJson).error && <Tag color="error">路由错误</Tag>}</Space>
            </Space>
          </List.Item>}
        />}
      </Card>
    </Col>
    <Col flex="auto">
      {!selected ? <Card><Empty description="选择或新增一个资源" /></Card> : <Card size="small"
        title={selected.resourceKey || '未命名资源'} extra={!disabled && <Popconfirm
          title="确认从草稿中移除此资源？" onConfirm={removeResource}>
          <Button danger type="text" icon={<DeleteOutlined />}>移除</Button>
        </Popconfirm>}>
        <Row gutter={12}>
          <Col span={10}><Form.Item label="资源键" required validateStatus={duplicateKey ? 'error' : undefined}
            help={duplicateKey ? '资源键必须唯一' : undefined}>
            <Input disabled={disabled} value={selected.resourceKey} maxLength={100}
              onChange={(event) => updateResource({ resourceKey: event.target.value.trim() })} />
          </Form.Item></Col>
          <Col span={7}><Form.Item label="资源类型" required><Select disabled={disabled}
            value={selected.resourceType} options={[
              { label: '参考资料', value: 'REFERENCE' },
              { label: '说明文档', value: 'INSTRUCTION' }
            ]} onChange={(resourceType) => updateResource({ resourceType })} /></Form.Item></Col>
          <Col span={7}><Form.Item label="MIME 类型" required><Select disabled={disabled}
            value={selected.mimeType} options={[
              { label: 'Markdown', value: 'text/markdown' }, { label: '纯文本', value: 'text/plain' }
            ]} onChange={(mimeType) => updateResource({ mimeType })} /></Form.Item></Col>
        </Row>
        {(selected.objectKey || selected.contentDigest) && <Alert style={{ marginBottom: 12 }} type="info" showIcon
          message="已发布资源索引（只读）" description={<Space direction="vertical" size={0}>
            {selected.objectKey && <Typography.Text copyable>{selected.objectKey}</Typography.Text>}
            {selected.contentDigest && <Typography.Text type="secondary" copyable>{selected.contentDigest}</Typography.Text>}
          </Space>} />}
        <Tabs items={[
          {
            key: 'content', label: 'Markdown 内容', children: <>
              <Input.TextArea disabled={disabled} value={selected.content} rows={20} showCount maxLength={102400}
                placeholder="输入会随草稿一起校验和发布的 Markdown 内容"
                onChange={(event) => updateContent(event.target.value)} />
              {(sizeError || contentLimitError) && <Alert style={{ marginTop: 8 }} type="error" showIcon
                message={sizeError || contentLimitError} />}
              <Space wrap style={{ marginTop: 8 }}>
                <Typography.Text type={selectedBytes > MAX_RESOURCE_BYTES ? 'danger' : 'secondary'}>
                  当前：{(selectedBytes / 1024).toFixed(1)} / 100 KiB
                </Typography.Text>
                <Typography.Text type={totalBytes > MAX_RESOURCE_TOTAL_BYTES ? 'danger' : 'secondary'}>
                  合计：{(totalBytes / 1024).toFixed(1)} / 512 KiB
                </Typography.Text>
                <Typography.Text type="secondary">按 UTF-8 字节计算；服务端发布校验仍为权威结果。</Typography.Text>
              </Space>
            </>
          },
          {
            key: 'route', label: '路由规则', children: <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {routeResult.error && <Alert type="error" showIcon message={routeResult.error}
                description="先在原始 JSON 中修复后，才能继续使用结构化路由编辑。" />}
              <Space><Typography.Text>始终装载</Typography.Text><Switch disabled={disabled || !routeResult.route}
                checked={routeResult.route?.always === true} onChange={(always) => updateRoute((route) => {
                  if (always) route.always = true;
                  else delete route.always;
                })} /></Space>
              <Form.Item label="操作匹配" tooltip="例如 CREATE、REWRITE、REPAIR">
                <Select mode="tags" disabled={disabled || !routeResult.route}
                  value={stringArray(routeResult.route?.operations)} tokenSeparators={[',']}
                  onChange={(operations) => updateRoute((route) => {
                    if (operations.length) route.operations = operations;
                    else delete route.operations;
                  })} />
              </Form.Item>
              <Form.Item label="关键词匹配" tooltip="操作或意图文本命中任一关键词时装载">
                <Select mode="tags" disabled={disabled || !routeResult.route}
                  value={stringArray(routeResult.route?.keywords)} tokenSeparators={[',']}
                  onChange={(keywords) => updateRoute((route) => {
                    if (keywords.length) route.keywords = keywords;
                    else delete route.keywords;
                  })} />
              </Form.Item>
              <Form.Item label="原始路由 JSON" extra="未受管的扩展字段会保留。">
                <Input.TextArea disabled={disabled} rows={7} value={selected.routeJson} maxLength={8000}
                  onChange={(event) => updateResource({ routeJson: event.target.value })} />
              </Form.Item>
            </Space>
          }
        ]} />
      </Card>}
    </Col>
  </Row>;
}
