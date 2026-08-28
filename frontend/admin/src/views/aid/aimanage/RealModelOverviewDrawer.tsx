import React, { useCallback, useEffect, useState } from 'react';
import { Drawer, Empty, Input, Space, Spin, Switch, Table, Tag, Tooltip, message } from 'antd';
import { GlobalOutlined, SearchOutlined } from '@ant-design/icons';
import { realModelOverview, updateModel, type RealModelGroup, type RealModelItem } from '@/api/aid/aimanage';
import { MODEL_TYPE_OPTIONS, GENERATE_MODE_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';

interface Props {
  open: boolean;
  onClose: () => void;
  /** 抽屉内启停模型后回调，父页面同步刷新模型列表 */
  onStatusChanged?: () => void;
}

/**
 * 真实模型总览抽屉：按真实上游模型名聚合各厂商模型，
 * 模型身份由「真实模型 + 模型代码」共同决定，组内各模型代码可独立启停。
 */
export default function RealModelOverviewDrawer({ open, onClose, onStatusChanged }: Props) {
  const [loading, setLoading] = useState(false);
  const [groups, setGroups] = useState<RealModelGroup[]>([]);
  const [keyword, setKeyword] = useState('');
  const [togglingId, setTogglingId] = useState<number | null>(null);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

  const load = useCallback(async (kw?: string) => {
    setLoading(true);
    try {
      const res: any = await realModelOverview(kw?.trim() || undefined);
      const list: RealModelGroup[] = res.data || [];
      setGroups(list);
      // 多模型组默认展开，便于直观处理
      setExpandedKeys(list.filter((g) => g.totalCount > 1).map((g) => g.realModelCode));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      setKeyword('');
      load();
    }
  }, [open, load]);

  /** 行内启停：失败由统一拦截器提示，成功后刷新总览与父页面 */
  const handleToggle = async (item: RealModelItem, enabled: boolean) => {
    if (togglingId != null) return;
    setTogglingId(item.id);
    try {
      await updateModel({ id: item.id, modelCode: item.modelCode, status: enabled ? '0' : '1' });
      message.success(enabled ? `已启用【${item.modelName}】` : `已停用【${item.modelName}】`);
      await load(keyword);
      onStatusChanged?.();
    } catch {
      // 请求失败由统一拦截器提示，保留原状态
    } finally {
      setTogglingId(null);
    }
  };

  const memberColumns: any[] = [
    { title: '服务商', dataIndex: 'providerName', width: 140, ellipsis: true, render: (v: string) => v || '--' },
    { title: '模型代码', dataIndex: 'modelCode', width: 170, ellipsis: true,
      render: (v: string) => <code style={{ background: '#f1f5f9', color: '#475569', padding: '2px 8px', borderRadius: 6, fontSize: 12 }}>{v}</code> },
    { title: '模型名称', dataIndex: 'modelName', width: 160, ellipsis: true },
    { title: '生成模式', dataIndex: 'generateMode', width: 110,
      render: (v: string) => v ? <Tag color={getAntdTagColor(GENERATE_MODE_OPTIONS, v)} style={{ borderRadius: 6 }}>{getLabelByValue(GENERATE_MODE_OPTIONS, v)}</Tag> : '--' },
    { title: '优先级', dataIndex: 'priority', width: 80, render: (v: any) => v ?? '--' },
    { title: '状态', key: 'status', width: 110, render: (_: any, r: RealModelItem) => {
      const enabled = r.status === '0';
      return (
        <Tooltip title={enabled ? '点击停用' : '点击启用'}>
          <Switch
            size="small"
            checked={enabled}
            checkedChildren="启用"
            unCheckedChildren="停用"
            loading={togglingId === r.id}
            onChange={(checked) => handleToggle(r, checked)}
          />
        </Tooltip>
      );
    } }
  ];

  const groupColumns: any[] = [
    { title: '真实模型', dataIndex: 'realModelCode', ellipsis: true, render: (v: string) => (
      <b style={{ fontSize: 13 }}>{v}</b>
    ) },
    { title: '分类', dataIndex: 'modelType', width: 90,
      render: (v: string) => v ? <Tag color={getAntdTagColor(MODEL_TYPE_OPTIONS, v)} style={{ borderRadius: 6 }}>{getLabelByValue(MODEL_TYPE_OPTIONS, v)}</Tag> : '--' },
    { title: '关联模型', dataIndex: 'totalCount', width: 100, render: (v: number) => <Tag style={{ borderRadius: 6 }}>{v} 个</Tag> },
    { title: '启用中', dataIndex: 'activeCount', width: 100, render: (v: number) => (
      <Tag color={v > 0 ? 'success' : 'default'} style={{ borderRadius: 6 }}>
        {v > 0 ? `${v} 个启用` : '全部停用'}
      </Tag>
    ) }
  ];

  return (
    <Drawer
      title={<Space><GlobalOutlined style={{ color: '#2563eb' }} /><span>真实模型总览</span></Space>}
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Input
          allowClear
          placeholder="按真实模型名 / 模型代码 / 模型名称搜索，回车确认"
          prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
          value={keyword}
          onChange={(e) => {
            const next = e.target.value;
            setKeyword(next);
            // 清空即恢复全量展示
            if (!next) load('');
          }}
          onPressEnter={() => load(keyword)}
          style={{ maxWidth: 360 }}
        />
        <Spin spinning={loading}>
          {groups.length === 0 && !loading ? (
            <Empty description="暂无模型数据" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 40 }} />
          ) : (
            <Table
              rowKey="realModelCode"
              size="small"
              dataSource={groups}
              columns={groupColumns}
              pagination={false}
              expandable={{
                expandedRowKeys: expandedKeys,
                onExpandedRowsChange: (keys) => setExpandedKeys([...keys]),
                expandedRowRender: (g: RealModelGroup) => (
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={g.models}
                    columns={memberColumns}
                    pagination={false}
                  />
                )
              }}
            />
          )}
        </Spin>
      </Space>
    </Drawer>
  );
}
