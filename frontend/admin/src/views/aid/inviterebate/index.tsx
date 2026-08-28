import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listInviteRebate,
  getInviteRebate
} from '@/api/aid/inviterebate';

function RebateStatusTag({ value }: { value?: string }) {
  if (value === 'revoked') return <Tag color="orange">已撤回</Tag>;
  return <Tag color="success">已发放</Tag>;
}

const config: CrudConfig = {
  title: '邀请返佣记录',
  permPrefix: 'aid:inviterebate',
  rowKey: 'id',
  viewable: true,
  // 返佣记录由支付回调自动产生（发放/退款撤回），审计数据禁止任何增删改
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  api: {
    list: listInviteRebate,
    get: getInviteRebate
  },
  searchFields: [
    { name: 'inviterUserId', label: '邀请人ID', type: 'input' },
    { name: 'inviteeUserId', label: '被邀请人ID', type: 'input' },
    { name: 'orderNo', label: '订单号', type: 'input' },
    {
      name: 'status',
      label: '状态',
      type: 'select',
      options: [
        { label: '已发放', value: 'granted' },
        { label: '已撤回', value: 'revoked' }
      ]
    }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '邀请人ID', dataIndex: 'inviterUserId', width: 90 },
    { title: '邀请人昵称', dataIndex: 'inviterNickName', width: 130, ellipsis: true },
    { title: '被邀请人ID', dataIndex: 'inviteeUserId', width: 100 },
    { title: '被邀请人昵称', dataIndex: 'inviteeNickName', width: 130, ellipsis: true },
    { title: '订单号', dataIndex: 'orderNo', width: 200, ellipsis: true },
    { title: '订单积分', dataIndex: 'orderCredits', width: 100 },
    { title: '实付金额', dataIndex: 'payPrice', width: 100, prefix: '¥' },
    { title: '返佣比例', dataIndex: 'rebateRatio', width: 90, suffix: '%' },
    { title: '返佣积分', dataIndex: 'rebateAmount', width: 100 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => <RebateStatusTag value={v} />
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '返佣时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: []
};

export default function Page() {
  return <CrudPage config={config} />;
}
