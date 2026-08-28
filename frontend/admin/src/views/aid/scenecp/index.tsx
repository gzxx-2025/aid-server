import React from 'react';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listScenecp, getScenecp, addScenecp, updateScenecp, delScenecp
} from '@/api/aid/scenecp';

const ASSET_TYPE_SHORT = [
  { label: '场景', value: 'scene' },
  { label: '角色', value: 'character' },
  { label: '道具', value: 'prop' }
];

const config: CrudConfig = {
  title: '角色道具场景',
  permPrefix: 'aid:scenecp',
  rowKey: 'id',
  viewable: true,
  api: {
    list: listScenecp,
    get: getScenecp,
    add: addScenecp,
    update: updateScenecp,
    remove: delScenecp,
    exportUrl: '/aid/scenecp/export'
  },
  searchFields: [
    { name: 'name', label: '名称', type: 'input' },
    { name: 'assetType', label: '类型', type: 'select', options: ASSET_TYPE_SHORT }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 100 },
    { title: '剧集ID', dataIndex: 'episodeId', width: 100 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '名称', dataIndex: 'name', width: 160, ellipsis: true },
    { title: '类型', dataIndex: 'assetType', width: 100, render: (v: string) => ASSET_TYPE_SHORT.find((o) => o.value === v)?.label || v },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'projectId', label: '项目ID', type: 'number', required: true },
    { name: 'episodeId', label: '剧集ID', type: 'number' },
    { name: 'userId', label: '用户ID', type: 'number' },
    { name: 'name', label: '名称', required: true },
    { name: 'assetType', label: '类型', type: 'select', options: ASSET_TYPE_SHORT, required: true },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
