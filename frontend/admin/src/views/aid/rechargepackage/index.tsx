import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listRechargepackage, getRechargepackage, addRechargepackage, updateRechargepackage, delRechargepackage
} from '@/api/aid/rechargepackage';
import { YES_NO_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';
import { useDict } from '@/hooks/useDict';

export default function RechargepackagePage() {
  const dicts = useDict('one_or_zero');
  const statusDict = dicts['one_or_zero'] || [];

  const config: CrudConfig = {
    title: '充值套餐',
    permPrefix: 'aid:rechargepackage',
    rowKey: 'id',
    viewable: true,
    api: {
      list: listRechargepackage,
      get: getRechargepackage,
      add: addRechargepackage,
      update: updateRechargepackage,
      remove: delRechargepackage,
      exportUrl: '/aid/rechargepackage/export'
    },
    searchFields: [
      { name: 'packageName', label: '套餐名称', type: 'input' },
      { name: 'credits', label: '获得积分', type: 'input' },
      { name: 'status', label: '状态', type: 'dict', dictType: 'one_or_zero' }
    ],
    columns: [
      { title: 'ID', dataIndex: 'id', width: 80 },
      { title: '套餐名称', dataIndex: 'packageName', width: 160 },
      { title: '获得积分', dataIndex: 'credits', width: 100 },
      { title: '原价(元)', dataIndex: 'originalPrice', width: 100, prefix: '¥' },
      { title: '折扣', dataIndex: 'discount', width: 80 },
      { title: '实付(元)', dataIndex: 'payPrice', width: 100, prefix: '¥' },
      { title: '图标', dataIndex: 'icon', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 24, height: 24, objectFit: 'cover' }} /> : '-' },
      { title: '描述', dataIndex: 'description', ellipsis: true, width: 180 },
      { title: '排序', dataIndex: 'sortOrder', width: 80 },
      { title: '状态', dataIndex: 'status', width: 100, dictType: 'one_or_zero' },
      { title: '备注', dataIndex: 'remark', ellipsis: true },
      { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
    ],
    formFields: [
      { name: 'packageName', label: '套餐名称', required: true, maxLength: 64 },
      { name: 'credits', label: '获得积分', type: 'number', required: true },
      { name: 'originalPrice', label: '原价(元)', type: 'number' },
      { name: 'discount', label: '折扣', type: 'number', placeholder: '0.9=9折' },
      { name: 'payPrice', label: '实付金额(元)', type: 'number', required: true },
      { name: 'icon', label: '图标', type: 'image', span: 24 },
      { name: 'sortOrder', label: '排序', type: 'number', initialValue: 0 },
      { name: 'status', label: '状态', type: 'dict', dictType: 'one_or_zero', initialValue: '0' },
      { name: 'description', label: '描述', type: 'textarea', span: 24 },
      { name: 'remark', label: '备注', type: 'textarea', span: 24 }
    ],
    dictTypes: ['one_or_zero']
  };

  return <CrudPage config={config} />;
}
