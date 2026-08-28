import React from 'react';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listBalancelog,
  getBalancelog
} from '@/api/aid/balancelog';

const config: CrudConfig = {
  title: '余额变动记录',
  permPrefix: 'aid:balancelog',
  rowKey: 'id',
  viewable: true,
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  api: {
    list: listBalancelog,
    get: getBalancelog,
    exportUrl: '/aid/balancelog/export'
  },
  searchFields: [
    { name: 'userId', label: '用户ID', type: 'input' },
    { name: 'changeType', label: '变动类型', type: 'dict', dictType: 'change_type' },
    { name: 'bizType', label: '业务类型', type: 'dict', dictType: 'biz_type' },
    { name: 'bizName', label: '业务名称', type: 'input' }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '变动类型', dataIndex: 'changeType', dictType: 'change_type', width: 110 },
    { title: '变动金额', dataIndex: 'amount', width: 110, prefix: '¥' },
    { title: '变更前', dataIndex: 'beforeBalance', width: 110 },
    { title: '变更后', dataIndex: 'afterBalance', width: 110 },
    { title: '业务ID', dataIndex: 'relatedId', width: 110 },
    { title: '业务类型', dataIndex: 'bizType', dictType: 'biz_type', width: 120 },
    { title: '业务名称', dataIndex: 'bizName', ellipsis: true, width: 180 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: []
};

export default function Page() {
  return <CrudPage config={config} />;
}
