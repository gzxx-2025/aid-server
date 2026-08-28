'use client'

import { Button,Drawer,Tabs } from 'antd'
import type { RechargeOrderRow } from '~/types/business-api'
import { formatPayStatus } from './rechargeFormat'

/** 订单列表筛选：与接口 payStatus 对应，全部不传 */
export type RechargeOrderTabKey = 'all' | 'pending' | 'paid' | 'closed'

const ORDER_TAB_ITEMS: { key: RechargeOrderTabKey; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'pending', label: '待支付' },
  { key: 'paid', label: '已支付' },
  { key: 'closed', label: '已取消' }
]

export interface RechargeOrdersDrawerProps {
  open: boolean
  onClose: () => void
  orderTab: RechargeOrderTabKey
  onTabChange: (key: RechargeOrderTabKey) => void
  orders: RechargeOrderRow[]
  highlightOrderNo: string
  repayingOrderNo: string
  cancellingOrderNo: string
  onRepay: (row: RechargeOrderRow) => void
  onCancelOrder: (orderNo: string) => void
}

/** 订单管理抽屉：状态筛选 + 待支付订单的继续支付 / 取消 */
export function RechargeOrdersDrawer({
  open,
  onClose,
  orderTab,
  onTabChange,
  orders,
  highlightOrderNo,
  repayingOrderNo,
  cancellingOrderNo,
  onRepay,
  onCancelOrder
}: RechargeOrdersDrawerProps) {
  return (
    <Drawer
      open={open}
      onClose={onClose}
      title="订单管理"
      size={520}
      mask={{ closable: true }}
      rootClassName="recharge-order-drawer"
    >
      <Tabs
        activeKey={orderTab}
        onChange={(key) => onTabChange(key as RechargeOrderTabKey)}
        className="order-status-tabs"
        items={ORDER_TAB_ITEMS}
      />
      {orders.length === 0 ? (
        <div className="order-empty">暂无订单</div>
      ) : (
        <div className="order-list">
          {orders.map((row) => (
            <div
              key={row.orderNo}
              className={`order-card${highlightOrderNo === row.orderNo ? ' order-card--highlight' : ''}`}
              data-order-no={row.orderNo}
            >
              <div className="order-card__title">{row.productName}</div>
              <div className="order-card__meta">订单号：{row.orderNo}</div>
              <div className="order-card__meta">金额：￥{row.payPrice}</div>
              <div className="order-card__meta">状态：{formatPayStatus(row.payStatus)}</div>
              {row.payStatus === 'pending' && (
                <div className="order-card__actions order-card__actions--pay">
                  <Button
                    type="primary"
                    size="small"
                    loading={repayingOrderNo === row.orderNo}
                    onClick={() => onRepay(row)}
                  >
                    立即支付
                  </Button>
                  <Button
                    size="small"
                    danger
                    loading={cancellingOrderNo === row.orderNo}
                    onClick={() => onCancelOrder(row.orderNo)}
                  >
                    取消支付
                  </Button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </Drawer>
  )
}

export default RechargeOrdersDrawer
