import React, { useState } from 'react';
import { Descriptions, Drawer, Tag, message, Modal, Input, Form } from 'antd';
import { EyeOutlined, SyncOutlined, RollbackOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listPayorder,
  getPayorder,
  syncPayorder,
  refundPayorder
} from '@/api/aid/payorder';
import { parseTime } from '@/utils/ruoyi';

const payStatusTagMap: Record<string, { color: string; text: string }> = {
  pending: { color: 'warning', text: '待支付' },
  paid: { color: 'success', text: '已支付' },
  failed: { color: 'error', text: '支付失败' },
  closed: { color: 'default', text: '已关闭' },
  refunded: { color: 'orange', text: '已退款' }
};

function PayStatus({ value }: { value?: string }) {
  if (!value) return <span>-</span>;
  const hit = payStatusTagMap[value];
  return hit ? <Tag color={hit.color}>{hit.text}</Tag> : <Tag>{value}</Tag>;
}

export default function PayorderPage() {
  const [detail, setDetail] = useState<any | null>(null);
  // 退款弹窗状态：保存当前待退款订单 + 一个 refresh 回调
  const [refundCtx, setRefundCtx] = useState<{ row: any; refresh: () => void } | null>(null);
  const [refundLoading, setRefundLoading] = useState(false);
  const [refundForm] = Form.useForm();

  const submitRefund = async () => {
    if (!refundCtx) return;
    let values: { refundReason?: string };
    try {
      values = await refundForm.validateFields();
    } catch {
      return;
    }
    setRefundLoading(true);
    try {
      const res: any = await refundPayorder({
        orderNo: refundCtx.row.orderNo,
        refundReason: values.refundReason
      });
      message.success(res.msg || '退款成功');
      const refresh = refundCtx.refresh;
      setRefundCtx(null);
      refundForm.resetFields();
      refresh();
    } finally {
      setRefundLoading(false);
    }
  };

  const config: CrudConfig = {
    title: '支付订单',
    permPrefix: 'aid:payorder',
    rowKey: 'id',
    // 后端已下线 add/edit/remove（资金类审计表），前端隐藏对应按钮
    hideAdd: true,
    hideEdit: true,
    hideDelete: true,
    api: {
      list: listPayorder,
      get: getPayorder,
      exportUrl: '/aid/payorder/export'
    },
    searchFields: [
      { name: 'orderNo', label: '订单号', type: 'input' },
      { name: 'userId', label: '用户ID', type: 'input' },
      { name: 'payChannel', label: '支付渠道', type: 'dict', dictType: 'aid_pay_channel' },
      { name: 'payStatus', label: '支付状态', type: 'dict', dictType: 'aid_pay_status' }
    ],
    columns: [
      { title: '订单号', dataIndex: 'orderNo', width: 200, ellipsis: true },
      { title: '用户ID', dataIndex: 'userId', width: 90 },
      { title: '商品名称', dataIndex: 'productName', width: 160, ellipsis: true },
      { title: '获得积分', dataIndex: 'credits', width: 100 },
      { title: '实付金额', dataIndex: 'payPrice', width: 110, prefix: '¥' },
      { title: '支付渠道', dataIndex: 'payChannel', dictType: 'aid_pay_channel', width: 110 },
      {
        title: '支付状态',
        dataIndex: 'payStatus',
        width: 110,
        render: (v: string) => <PayStatus value={v} />
      },
      { title: '支付时间', dataIndex: 'payTime', dateFormat: true, width: 160 },
      { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
    ],
    rowActions: [
      {
        label: '详情',
        icon: <EyeOutlined />,
        onClick: async (row) => {
          const res: any = await getPayorder(row.id);
          setDetail(res.data ?? res);
        }
      },
      {
        label: '同步',
        icon: <SyncOutlined />,
        perm: 'aid:payorder:sync',
        visible: (row) => row.payStatus === 'pending',
        confirm: (row) => `是否同步订单 ${row.orderNo} 的支付状态？`,
        onClick: async (row, { refresh }) => {
          const res: any = await syncPayorder(row.orderNo);
          message.success(res.msg || '同步成功');
          refresh();
        }
      },
      {
        label: '退款',
        icon: <RollbackOutlined />,
        danger: true,
        perm: 'aid:payorder:refund',
        // 仅已支付订单可退款
        visible: (row) => row.payStatus === 'paid',
        onClick: (row, { refresh }) => {
          refundForm.resetFields();
          setRefundCtx({ row, refresh });
        }
      }
    ]
  };

  return (
    <>
      <CrudPage config={config} />
      <Drawer
        open={!!detail}
        width={560}
        title="订单详情"
        onClose={() => setDetail(null)}
      >
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="订单号">{detail.orderNo}</Descriptions.Item>
            <Descriptions.Item label="用户ID">{detail.userId}</Descriptions.Item>
            <Descriptions.Item label="商品名称">{detail.productName}</Descriptions.Item>
            <Descriptions.Item label="获得积分">{detail.credits}</Descriptions.Item>
            <Descriptions.Item label="原价">¥{detail.originalPrice}</Descriptions.Item>
            <Descriptions.Item label="折扣">
              {detail.discount ? (detail.discount * 10).toFixed(1) + '折' : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="实付金额">
              <span style={{ color: '#ef4444', fontWeight: 600 }}>¥{detail.payPrice}</span>
            </Descriptions.Item>
            <Descriptions.Item label="支付状态">
              <PayStatus value={detail.payStatus} />
            </Descriptions.Item>
            <Descriptions.Item label="支付时间">
              {parseTime(detail.payTime) || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="过期时间">
              {parseTime(detail.expireTime) || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {parseTime(detail.createTime)}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>

      <Modal
        open={!!refundCtx}
        title="订单退款"
        okText="确认退款"
        okButtonProps={{ danger: true, loading: refundLoading }}
        onOk={submitRefund}
        onCancel={() => {
          setRefundCtx(null);
          refundForm.resetFields();
        }}
        destroyOnClose
      >
        {refundCtx && (
          <>
            <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="订单号">{refundCtx.row.orderNo}</Descriptions.Item>
              <Descriptions.Item label="用户ID">{refundCtx.row.userId}</Descriptions.Item>
              <Descriptions.Item label="退款金额">
                <span style={{ color: '#ef4444', fontWeight: 600 }}>¥{refundCtx.row.payPrice}</span>
              </Descriptions.Item>
              <Descriptions.Item label="扣回积分">{refundCtx.row.credits}</Descriptions.Item>
            </Descriptions>
            <div style={{ color: '#ad6800', marginBottom: 12, fontSize: 12 }}>
              将按原渠道全额退款，并扣回该订单发放的积分。若用户积分已消费导致余额不足，将无法退款，请人工核对。
            </div>
            <Form form={refundForm} layout="vertical">
              <Form.Item
                name="refundReason"
                label="退款原因"
                rules={[{ required: true, message: '请填写退款原因' }]}
              >
                <Input.TextArea rows={3} maxLength={200} showCount placeholder="请填写退款原因" />
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>
    </>
  );
}
