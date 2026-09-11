import { Space, Tag, Tooltip } from 'antd';
import { CheckCircleFilled, ExclamationCircleFilled } from '@ant-design/icons';
import type { Model } from './types';
import { getModelBillingOverview } from './billingSummary';

/** 模型列表中的计费总览：无需进入编辑弹窗即可确认是否启用了 SKU 及其价格。 */
export function ModelBillingOverview({ model }: { model: Model }) {
  const overview = getModelBillingOverview(model);
  const visiblePrices = Array.from(new Set(overview.routes.flatMap((route) => route.priceLabels))).slice(0, 2);
  const routeCount = overview.routes.length;
  const details = (
    <div className="billing-overview__tooltip">
      {overview.routes.map((route, index) => (
        <div key={`${route.label}-${index}`}>
          <strong>{route.label}</strong>
          <span>{route.mode === 'SKU'
            ? `SKU ${route.enabledSkuCount}/${route.skuCount} 启用`
            : '固定价格'}</span>
          <span>{route.priceLabels.join('；') || '价格未配置'}</span>
        </div>
      ))}
    </div>
  );
  return (
    <Tooltip title={details} placement="left">
      <div className="billing-overview" tabIndex={0} aria-label="计费配置概览">
        <Space size={[4, 4]} wrap>
          {overview.skuRouteCount > 0 && <Tag color="purple">SKU 计费 · {overview.enabledSkuCount} 条</Tag>}
          {overview.fixedRouteCount > 0 && <Tag color="blue">固定价 · {overview.fixedRouteCount} 路</Tag>}
          {model.isFree === true && <Tag color="green">用户侧免费</Tag>}
          {overview.issueCount > 0
            ? <Tag color="error" icon={<ExclamationCircleFilled />}>有价格缺口</Tag>
            : <Tag color="success" icon={<CheckCircleFilled />}>价格已配置</Tag>}
        </Space>
        <div className={visiblePrices.length ? 'billing-overview__price' : 'billing-overview__price billing-overview__price--missing'}>
          {visiblePrices.join('；') || '尚未配置可用价格'}
        </div>
        <div className="billing-overview__meta">
          {routeCount} 个启用调用协议 · 单模型倍率 ×{Number(model.billingMultiplier ?? 1).toFixed(2)}
        </div>
      </div>
    </Tooltip>
  );
}
