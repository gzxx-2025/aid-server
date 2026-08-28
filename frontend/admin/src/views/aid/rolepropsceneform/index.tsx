import React from 'react';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listRolepropsceneform, getRolepropsceneform, addRolepropsceneform,
  updateRolepropsceneform, delRolepropsceneform
} from '@/api/aid/rolepropsceneform';

const config: CrudConfig = {
  title: '场景形态图',
  permPrefix: 'aid:rolepropsceneform',
  rowKey: 'id',
  viewable: true,
  modalWidth: 720,
  api: {
    list: listRolepropsceneform,
    get: getRolepropsceneform,
    add: addRolepropsceneform,
    update: updateRolepropsceneform,
    remove: delRolepropsceneform,
    exportUrl: '/aid/rolepropsceneform/export'
  },
  searchFields: [
    { name: 'name', label: '形态名称', type: 'input' },
    { name: 'isUse', label: '是否启用', type: 'dict', dictType: 'one_or_zero' }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '资产ID', dataIndex: 'assetId', width: 100 },
    { title: '项目ID', dataIndex: 'projectId', width: 100 },
    { title: '剧集ID', dataIndex: 'episodeId', width: 100 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '形态名称', dataIndex: 'name', width: 160, ellipsis: true },
    { title: '是否启用', dataIndex: 'isUse', width: 100, dictType: 'one_or_zero' },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'assetId', label: '关联资产ID', type: 'number', required: true },
    { name: 'projectId', label: '项目ID', type: 'number' },
    { name: 'episodeId', label: '剧集ID', type: 'number' },
    { name: 'userId', label: '用户ID', type: 'number' },
    { name: 'name', label: '形态名称', required: true, maxLength: 64, placeholder: '常服/战损版/侧脸等' },
    { name: 'promptText', label: 'AI提示词', type: 'textarea', span: 24 },
    { name: 'isUse', label: '是否启用', type: 'dict', dictType: 'one_or_zero', initialValue: '1' },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ],
  dictTypes: ['one_or_zero']
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
