import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listFaq, getFaq, addFaq, updateFaq, delFaq
} from '@/api/aid/faq';

/** 状态选项 */
const STATUS_OPTIONS = [
  { label: '显示', value: '0' },
  { label: '隐藏', value: '1' }
];

/** 常用分类建议（combobox 既可选用也可自定义输入，分类不固定死） */
const CATEGORY_SUGGESTIONS = [
  { label: '账号', value: '账号' },
  { label: '充值', value: '充值' },
  { label: '生成', value: '生成' },
  { label: '会员', value: '会员' },
  { label: '功能使用', value: '功能使用' },
  { label: '其他', value: '其他' }
];

function labelOf(options: Array<{ label: string; value: any }>, value: any) {
  return options.find((o) => o.value === value)?.label ?? value;
}

/** 去除 HTML 标签，用于列表内容预览 */
function stripHtml(html: any): string {
  if (!html) return '';
  return String(html).replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim();
}

export default function FaqPage() {
  const config: CrudConfig = {
    title: '常见问题',
    permPrefix: 'aid:faq',
    rowKey: 'id',
    modalWidth: 820,
    viewable: true,
    api: {
      list: listFaq,
      get: getFaq,
      add: addFaq,
      update: updateFaq,
      remove: delFaq,
      exportUrl: '/aid/faq/export'
    },
    searchFields: [
      { name: 'title', label: '问题标题', type: 'input' },
      { name: 'category', label: '分类', type: 'input' },
      { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS }
    ],
    columns: [
      { title: 'ID', dataIndex: 'id', width: 70 },
      { title: '问题标题', dataIndex: 'title', width: 220, ellipsis: true },
      { title: '分类', dataIndex: 'category', width: 100, render: (v: string) => (v ? <Tag>{v}</Tag> : '-') },
      {
        title: '内容预览', dataIndex: 'content', ellipsis: true, width: 260,
        render: (v: any) => {
          const text = stripHtml(v);
          return text ? <span title={text}>{text.length > 60 ? text.slice(0, 60) + '…' : text}</span> : '-';
        }
      },
      { title: '排序', dataIndex: 'sortOrder', width: 70 },
      { title: '浏览量', dataIndex: 'viewCount', width: 90 },
      {
        title: '状态', dataIndex: 'status', width: 80,
        render: (v: string) => <Tag color={v === '0' ? 'green' : 'default'}>{labelOf(STATUS_OPTIONS, v)}</Tag>
      },
      { title: '发布时间', dataIndex: 'publishTime', dateFormat: true, width: 160 },
      { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
    ],
    formFields: [
      { name: 'title', label: '问题标题', required: true, maxLength: 255 },
      { name: 'category', label: '分类', type: 'combobox', options: CATEGORY_SUGGESTIONS, maxLength: 64, placeholder: '可选择或自定义输入分类' },
      { name: 'content', label: '问题内容（答案明细）', type: 'richtext', span: 24, required: true },
      { name: 'sortOrder', label: '排序', type: 'number', initialValue: 0 },
      { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS, initialValue: '0' },
      { name: 'publishTime', label: '发布时间', type: 'date' },
      { name: 'remark', label: '备注', type: 'textarea', span: 24, maxLength: 500 }
    ]
  };

  return <CrudPage config={config} />;
}
