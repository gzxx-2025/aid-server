import { Alert, Card, Col, Descriptions, Empty, Row, Statistic, Table, Typography } from 'antd';

export interface ProviderBalanceData {
  balance?: number | string;
  unit?: string;
  queriedAt?: number;
  isAvailable?: boolean;
  balanceAvailable?: boolean;
  delayNotice?: string;
  balanceInfos?: { currency: string; total_balance: string; granted_balance: string; topped_up_balance: string }[];
  remains?: { type: string; credit_remain: number | string; concurrency_limit?: number; current_concurrency?: number }[];
  resource_pack_subscribe_infos?: Record<string, any>[];
  credits?: number;
  creditsUsed?: number;
}

export function balanceNumber(value: unknown): string {
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '') return '未返回';
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString('zh-CN', { maximumFractionDigits: 8 }) : '金额格式异常';
}

const packageNames: Record<string, string> = { test: '测试资源包', metered: '积分资源包', concurrent: '并发资源包' };

export default function ProviderBalanceSummary({ data }: { data: ProviderBalanceData | null }) {
  if (!data) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="查询后展示官方账户余额" />;
  const currencies = data.balanceInfos;
  return <>
    {data.isAvailable === false && <Alert type="warning" showIcon message="官方标记账户余额不足以调用，请前往供应商检查余额。" style={{ marginBottom: 12 }} />}
    {currencies?.length ? <Row gutter={[12, 12]}>
      {currencies.map((row) => <Col xs={24} md={12} key={row.currency}>
        <Card size="small"><Statistic title={`可用余额 · ${row.currency}`} value={0} formatter={() => balanceNumber(row.total_balance)} />
          <Descriptions size="small" column={1} style={{ marginTop: 12 }} items={[
            { key: 'grant', label: '赠送余额', children: `${balanceNumber(row.granted_balance)} ${row.currency}` },
            { key: 'paid', label: '充值余额', children: `${balanceNumber(row.topped_up_balance)} ${row.currency}` }
          ]} />
        </Card>
      </Col>)}
    </Row> : <Card size="small">
      <Statistic title={data.unit?.toLowerCase() === 'credits' ? '可用积分' : '可用余额'} value={0}
        formatter={() => balanceNumber(data.balance)} suffix={data.unit || undefined} />
      {(data.credits != null || data.creditsUsed != null) && <Descriptions size="small" column={2} style={{ marginTop: 12 }} items={[
        { key: 'credits', label: '累计额度', children: `${balanceNumber(data.credits)} ${data.unit || ''}` },
        { key: 'used', label: '累计消耗', children: `${balanceNumber(data.creditsUsed)} ${data.unit || ''}` }
      ]} />}
    </Card>}
    {Array.isArray(data.remains) && <Table size="small" style={{ marginTop: 16 }} pagination={false}
      dataSource={data.remains} rowKey="type" locale={{ emptyText: '官方未返回积分资源包明细' }} columns={[
        { title: '资源包类型', dataIndex: 'type', render: (value: string) => packageNames[value] || value },
        { title: '剩余积分', dataIndex: 'credit_remain', render: balanceNumber },
        { title: '并发上限', dataIndex: 'concurrency_limit', render: (value: unknown) => value == null ? '—' : balanceNumber(value) },
        { title: '使用中并发', dataIndex: 'current_concurrency', render: (value: unknown) => value == null ? '—' : balanceNumber(value) }
      ]} />}
    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
      数据来自供应商账户；积分不等于人民币，不同币种不合并计算。本系统的用户积分不受影响。
    </Typography.Paragraph>
  </>;
}
