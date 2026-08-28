import React from 'react';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listPost,
  getPost,
  addPost,
  updatePost,
  delPost
} from '@/api/system/post';

const config: CrudConfig = {
  title: '岗位',
  permPrefix: 'system:post',
  rowKey: 'postId',
  api: {
    list: listPost,
    get: getPost,
    add: addPost,
    update: updatePost,
    remove: delPost,
    exportUrl: '/system/post/export'
  },
  searchFields: [
    { name: 'postCode', label: '岗位编码', type: 'input' },
    { name: 'postName', label: '岗位名称', type: 'input' },
    { name: 'status', label: '状态', type: 'dict', dictType: 'sys_normal_disable' }
  ],
  columns: [
    { title: '岗位编号', dataIndex: 'postId', width: 100 },
    { title: '岗位编码', dataIndex: 'postCode', width: 160 },
    { title: '岗位名称', dataIndex: 'postName', width: 160 },
    { title: '岗位排序', dataIndex: 'postSort', width: 100 },
    { title: '状态', dataIndex: 'status', dictType: 'sys_normal_disable', width: 100 },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'postName', label: '岗位名称', required: true, maxLength: 50 },
    { name: 'postCode', label: '岗位编码', required: true, maxLength: 64 },
    { name: 'postSort', label: '岗位顺序', type: 'number', required: true, initialValue: 0 },
    { name: 'status', label: '岗位状态', type: 'dict', dictType: 'sys_normal_disable', initialValue: '0' },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page() {
  return <CrudPage config={config} />;
}
