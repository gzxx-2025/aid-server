import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listPromptlib, getPromptlib, addPromptlib, updatePromptlib, delPromptlib
} from '@/api/aid/promptlib';
import {
  PROMPT_TYPE_OPTIONS, ENABLE_STATUS_OPTIONS, getLabelByValue, getAntdTagColor
} from '@/utils/enums';

const config: CrudConfig = {
  title: '提示词素材库',
  permPrefix: 'aid:promptlib',
  rowKey: 'id',
  modalWidth: 760,
  viewable: true,
  api: {
    list: listPromptlib,
    get: getPromptlib,
    add: addPromptlib,
    update: updatePromptlib,
    remove: delPromptlib,
    exportUrl: '/aid/promptlib/export'
  },
  searchFields: [
    { name: 'userId', label: '用户ID', type: 'input', placeholder: '0为官方预设' },
    { name: 'promptType', label: '分类', type: 'select', options: PROMPT_TYPE_OPTIONS },
    { name: 'promptName', label: '提示词名称', type: 'input' },
    { name: 'status', label: '状态', type: 'select', options: ENABLE_STATUS_OPTIONS }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户ID', dataIndex: 'userId', width: 110, render: (v: any) => v == 0 ? '官方预设' : v },
    { title: '分类', dataIndex: 'promptType', width: 110, render: (v: string) => getLabelByValue(PROMPT_TYPE_OPTIONS, v) },
    { title: '名称', dataIndex: 'promptName', width: 140 },
    { title: '预览图', dataIndex: 'coverUrl', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 40, objectFit: 'cover', borderRadius: 4 }} /> : '-' },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <Tag color={getAntdTagColor(ENABLE_STATUS_OPTIONS, v)}>{getLabelByValue(ENABLE_STATUS_OPTIONS, v)}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'userId', label: '所属用户ID', type: 'number', required: true, placeholder: '0=官方预设' },
    { name: 'promptType', label: '提示词分类', type: 'select', options: PROMPT_TYPE_OPTIONS, required: true },
    { name: 'promptName', label: '提示词名称', required: true, maxLength: 100 },
    { name: 'promptContent', label: '提示词内容', type: 'textarea', required: true, span: 24, maxLength: 5000 },
    { name: 'coverUrl', label: '预览图', type: 'image', span: 24 },
    { name: 'sortOrder', label: '排序', type: 'number', initialValue: 0 },
    { name: 'status', label: '状态', type: 'select', options: ENABLE_STATUS_OPTIONS, initialValue: '0' },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page() {
  return <CrudPage config={config} />;
}
