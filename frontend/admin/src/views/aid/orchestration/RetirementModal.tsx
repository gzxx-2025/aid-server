import React, { useEffect, useState } from 'react';
import { Alert, Checkbox, Descriptions, Modal, Select, Skeleton, Space, Table, Tag, Typography } from 'antd';
import type { OrchestrationImpact } from '@/api/aid/orchestration';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  loading?: boolean;
  submitting?: boolean;
  impact?: OrchestrationImpact | null;
  replacementOptions?: { label: string; value: string }[];
  onCancel: () => void;
  onConfirm: (replacementCode?: string) => Promise<void> | void;
}

/** 通用“影响预览 → 替换/清理引用 → 明确确认”下线弹窗。 */
export default function RetirementModal({
  open,
  loading,
  submitting,
  impact,
  replacementOptions = [],
  onCancel,
  onConfirm
}: Props) {
  const [replacementCode, setReplacementCode] = useState<string>();
  const [confirmed, setConfirmed] = useState(false);

  useEffect(() => {
    if (open) {
      setReplacementCode(undefined);
      setConfirmed(false);
    }
  }, [open, impact?.resourceId]);

  const typeName = impact?.resourceType === 'agent' ? '智能体' : '模型';
  const hasReferences = (impact?.activeReferenceCount || 0) > 0;

  return (
    <Modal
      open={open}
      width={760}
      title={`${typeName}受控下线`}
      okText={replacementCode ? '替换引用并下线' : '清理引用并下线'}
      okButtonProps={{ danger: true, disabled: !impact || !confirmed }}
      confirmLoading={submitting}
      onCancel={onCancel}
      onOk={() => onConfirm(replacementCode)}
      destroyOnClose
    >
      {loading || !impact ? <Skeleton active paragraph={{ rows: 6 }} /> : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type={hasReferences ? 'warning' : 'info'}
            showIcon
            message={hasReferences
              ? `检测到 ${impact.activeReferenceCount} 条活动引用，系统不会静默级联删除`
              : '未检测到活动引用，可直接软下线'}
            description="下线操作在一个事务内完成；任一步失败都会整体回滚。"
          />

          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label={`${typeName}名称`}>{impact.resourceName || '-'}</Descriptions.Item>
            <Descriptions.Item label="稳定编码"><Text code>{impact.resourceCode}</Text></Descriptions.Item>
            {impact.bizCategoryCode && (
              <Descriptions.Item label="业务分类" span={2}><Text code>{impact.bizCategoryCode}</Text></Descriptions.Item>
            )}
          </Descriptions>

          <Table
            rowKey="type"
            size="small"
            pagination={false}
            dataSource={(impact.references || []).filter((item) => item.count > 0)}
            locale={{ emptyText: '没有活动引用' }}
            columns={[
              { title: '引用位置', dataIndex: 'label', width: 180 },
              { title: '数量', dataIndex: 'count', width: 80, render: (count: number) => <Tag color="orange">{count}</Tag> },
              { title: '下线处理', dataIndex: 'action' }
            ]}
          />

          {impact.replacementSupported && hasReferences && (
            <div>
              <Text strong>替代{typeName}（推荐）</Text>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                value={replacementCode}
                options={replacementOptions}
                onChange={setReplacementCode}
                placeholder={`不选择则清理活动引用并回退上层默认配置`}
                style={{ width: '100%', marginTop: 8 }}
                notFoundContent={`没有可用的同类替代${typeName}`}
              />
            </div>
          )}

          <Alert type="success" showIcon message="历史数据策略" description={impact.historyPolicy} />
          <Checkbox checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)}>
            <Paragraph style={{ display: 'inline', margin: 0 }}>
              我已核对以上影响，确认执行{replacementCode ? '替换引用并' : '清理活动引用后'}下线。
            </Paragraph>
          </Checkbox>
        </Space>
      )}
    </Modal>
  );
}
