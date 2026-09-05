import React, { useEffect, useState } from 'react';
import { Alert, Card, Empty, Space, Tabs, Tag, Typography } from 'antd';
import { ApartmentOutlined, DeploymentUnitOutlined, RobotOutlined } from '@ant-design/icons';
import AgentPage from '@/views/agent';
import FuncConfigPage from '@/views/aid/funcconfig';
import GenAgentPoolPage from '@/views/aid/genagentpool';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';

const { Paragraph, Text, Title } = Typography;

const DESCRIPTIONS = {
  models: {
    title: '模型池',
    summary: '定义每个业务功能允许使用哪些底层模型，是运行时模型白名单和排序入口。',
    details: [
      '功能编码 funcCode 是稳定场景标识，并与智能体业务分类、矩阵业务场景保持一致。',
      '模型顺序决定默认兜底优先级；停用的模型或服务商会在运行时自动剔除。',
      '这里不修改供应商密钥、计费规则和模型能力，底层模型仍在“AI模型管理”中维护。'
    ],
    icon: <DeploymentUnitOutlined />,
    color: 'blue'
  },
  agents: {
    title: '智能体',
    summary: '把提示词、角色说明、默认模型和业务分类封装为可复用的业务执行单元。',
    details: [
      '一个模型池可以关联多个智能体，用于不同角色、质量档位或提示词版本。',
      '默认模型必须属于同一业务分类的模型池；留空时由模型池或策略矩阵兜底。',
      '智能体编码创建后保持稳定，避免矩阵、项目配置和历史记录出现悬空引用。'
    ],
    icon: <RobotOutlined />,
    color: 'purple'
  },
  matrix: {
    title: '策略矩阵',
    summary: '按业务场景、创作模式和剧本类型编排经济/性能默认项及候选智能体。',
    details: [
      '矩阵只引用模型池和智能体，不复制提示词、密钥、价格或模型能力。',
      '客户端先读取矩阵默认项，再回退智能体默认模型，最后回退模型池首个可用模型。',
      '保存时服务端会重新校验智能体归属、模型池成员关系以及清晰度/比例能力。'
    ],
    icon: <ApartmentOutlined />,
    color: 'cyan'
  }
} as const;

function NoPermission({ label }: { label: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`当前账号没有“${label}”查看权限`} />;
}

/** AI 模型池、智能体和策略矩阵的统一业务编排入口。 */
export default function AiOrchestrationPage() {
  const { hasPermi, hasPermiOr } = useAuth();
  const available = [
    { key: 'models', allowed: hasPermiOr(['aid:funcconfig:query', 'aid:funcconfig:list']) },
    { key: 'agents', allowed: hasPermi('aid:agent:list') },
    { key: 'matrix', allowed: hasPermi('aid:genagentpool:list') }
  ];
  const firstAllowed = available.find((item) => item.allowed)?.key || 'models';
  const [activeKey, setActiveKey] = useState(firstAllowed);
  const activeAllowed = available.some((item) => item.key === activeKey && item.allowed);

  useEffect(() => {
    if (!activeAllowed) setActiveKey(firstAllowed);
  }, [activeAllowed, firstAllowed]);

  const meta = DESCRIPTIONS[activeKey as keyof typeof DESCRIPTIONS];

  const page = activeKey === 'models'
    ? (available[0].allowed ? <FuncConfigPage /> : <NoPermission label="模型池" />)
    : activeKey === 'agents'
      ? (available[1].allowed ? <AgentPage /> : <NoPermission label="智能体" />)
      : (available[2].allowed ? <GenAgentPoolPage /> : <NoPermission label="策略矩阵" />);

  return (
    <div className="crud-page">
      <PageHeader
        title="AI 业务编排"
        desc="按照“模型供给 → 智能体封装 → 策略路由”组织生成能力；统一入口不改变各领域的数据边界和权限边界。"
      />
      <Alert
        type="info"
        showIcon
        message="三层关系"
        description="模型池决定可用模型范围，智能体定义业务角色与提示词，策略矩阵决定具体场景下优先使用哪个智能体和模型。"
        style={{ marginBottom: 16 }}
      />
      <Card bordered={false} className="page-card">
        <Tabs
          activeKey={activeKey}
          onChange={setActiveKey}
          items={(Object.keys(DESCRIPTIONS) as (keyof typeof DESCRIPTIONS)[]).map((key) => ({
            key,
            label: <Space>{DESCRIPTIONS[key].icon}{DESCRIPTIONS[key].title}</Space>
          }))}
        />
        <div style={{ padding: '4px 4px 16px' }}>
          <Title level={5} style={{ marginTop: 0 }}>
            <Tag color={meta.color}>{meta.title}</Tag>{meta.summary}
          </Title>
          {meta.details.map((detail) => (
            <Paragraph key={detail} style={{ marginBottom: 6, color: '#64748b' }}>
              <Text type="secondary">•</Text> {detail}
            </Paragraph>
          ))}
        </div>
      </Card>
      <div style={{ marginTop: 16 }}>{page}</div>
    </div>
  );
}
