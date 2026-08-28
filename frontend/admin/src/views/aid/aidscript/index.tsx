import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listAidscript, getAidscript, addAidscript, updateAidscript, delAidscript
} from '@/api/aid/aidscript';
import {
  YES_NO_OPTIONS, SCRIPT_STATUS_OPTIONS, getLabelByValue, getAntdTagColor
} from '@/utils/enums';

const config: CrudConfig = {
  title: '剧本',
  permPrefix: 'aid:aidscript',
  rowKey: 'id',
  viewable: true,
  modalWidth: 820,
  api: {
    list: listAidscript,
    get: getAidscript,
    add: addAidscript,
    update: updateAidscript,
    remove: delAidscript,
    exportUrl: '/aid/aidscript/export'
  },
  searchFields: [
    { name: 'isExtracted', label: '资产提取', type: 'select', options: YES_NO_OPTIONS },
    { name: 'status', label: '状态', type: 'select', options: SCRIPT_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value })) }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 100 },
    { title: '集数ID', dataIndex: 'episodeId', width: 100 },
    { title: '已提取资产', dataIndex: 'isExtracted', width: 110, render: (v: string) => <Tag color={getAntdTagColor(YES_NO_OPTIONS, v)}>{getLabelByValue(YES_NO_OPTIONS, v)}</Tag> },
    { title: '剧集版本', dataIndex: 'comicVersion', width: 100 },
    { title: '状态', dataIndex: 'status', width: 110, render: (v: number) => <Tag color={getAntdTagColor(SCRIPT_STATUS_OPTIONS, v)}>{getLabelByValue(SCRIPT_STATUS_OPTIONS, v)}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'projectId', label: '项目ID', type: 'number', required: true },
    { name: 'episodeId', label: '集数ID', type: 'number', placeholder: '电影为0' },
    { name: 'originalText', label: '原版剧本', type: 'textarea', span: 24, maxLength: 50000 },
    { name: 'simplifiedText', label: '简化版剧本', type: 'textarea', span: 24, maxLength: 50000 },
    { name: 'isExtracted', label: '已提取资产', type: 'select', options: YES_NO_OPTIONS },
    { name: 'comicVersion', label: '剧集版本', type: 'number' },
    { name: 'status', label: '状态', type: 'select', options: SCRIPT_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value })) },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
