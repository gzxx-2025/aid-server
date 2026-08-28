import React from 'react';
import { Tag, message } from 'antd';
import { StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listInviteRelation,
  getInviteRelation,
  changeInviteRelationStatus
} from '@/api/aid/inviterelation';

const channelTagMap: Record<string, { color: string; text: string }> = {
  sms: { color: 'blue', text: '手机号' },
  email: { color: 'cyan', text: '邮箱' },
  wechat: { color: 'green', text: '微信' }
};

function ChannelTag({ value }: { value?: string }) {
  if (!value) return <span>-</span>;
  const hit = channelTagMap[value];
  return hit ? <Tag color={hit.color}>{hit.text}</Tag> : <Tag>{value}</Tag>;
}

function StatusTag({ value }: { value?: string }) {
  return value === '1'
    ? <Tag color="error">已禁用</Tag>
    : <Tag color="success">正常</Tag>;
}

const config: CrudConfig = {
  title: '邀请关系',
  permPrefix: 'aid:inviterelation',
  rowKey: 'id',
  viewable: true,
  // 邀请关系由注册链路自动建立，后台禁止手动增删改（仅风控禁用/恢复）
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  api: {
    list: listInviteRelation,
    get: getInviteRelation
  },
  searchFields: [
    { name: 'inviterUserId', label: '邀请人ID', type: 'input' },
    { name: 'inviteeUserId', label: '被邀请人ID', type: 'input' },
    { name: 'inviteCode', label: '邀请码', type: 'input' },
    {
      name: 'registerChannel',
      label: '注册渠道',
      type: 'select',
      options: [
        { label: '手机号', value: 'sms' },
        { label: '邮箱', value: 'email' },
        { label: '微信', value: 'wechat' }
      ]
    },
    {
      name: 'status',
      label: '状态',
      type: 'select',
      options: [
        { label: '正常', value: '0' },
        { label: '已禁用', value: '1' }
      ]
    }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '邀请人ID', dataIndex: 'inviterUserId', width: 90 },
    { title: '邀请人昵称', dataIndex: 'inviterNickName', width: 130, ellipsis: true },
    { title: '被邀请人ID', dataIndex: 'inviteeUserId', width: 100 },
    { title: '被邀请人昵称', dataIndex: 'inviteeNickName', width: 130, ellipsis: true },
    { title: '邀请码', dataIndex: 'inviteCode', width: 110 },
    {
      title: '注册渠道',
      dataIndex: 'registerChannel',
      width: 100,
      render: (v: string) => <ChannelTag value={v} />
    },
    { title: '注册IP', dataIndex: 'registerIp', width: 130, ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => <StatusTag value={v} />
    },
    { title: '累计返佣', dataIndex: 'totalRebate', width: 100 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '绑定时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [],
  rowActions: [
    {
      label: '禁用',
      icon: <StopOutlined />,
      danger: true,
      perm: 'aid:inviterelation:edit',
      visible: (row) => row.status === '0',
      confirm: (row) => `禁用后「${row.inviterNickName || row.inviterUserId}」不再从该关系获得返佣（历史返佣不受影响），是否禁用？`,
      onClick: async (row, { refresh }) => {
        const res: any = await changeInviteRelationStatus({ id: row.id, status: '1', remark: '后台风控禁用' });
        message.success(res.msg || '已禁用');
        refresh();
      }
    },
    {
      label: '恢复',
      icon: <CheckCircleOutlined />,
      perm: 'aid:inviterelation:edit',
      visible: (row) => row.status === '1',
      confirm: () => '恢复后该关系将继续产生充值返佣，是否恢复？',
      onClick: async (row, { refresh }) => {
        const res: any = await changeInviteRelationStatus({ id: row.id, status: '0', remark: '后台恢复' });
        message.success(res.msg || '已恢复');
        refresh();
      }
    }
  ]
};

export default function Page() {
  return <CrudPage config={config} />;
}
