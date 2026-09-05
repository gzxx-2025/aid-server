import React from 'react';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listAssetuser, getAssetuser, addAssetuser, updateAssetuser, delAssetuser
} from '@/api/aid/assetuser';

const ASSET_TYPE = [
  { label: '人物参考图', value: 'reference_character' },
  { label: '场景参考图', value: 'reference_scene' },
  { label: '道具参考图', value: 'reference_prop' },
  { label: '风格', value: 'style' },
  { label: '姿势', value: 'pose' },
  { label: '表情', value: 'expression' },
  { label: '特效', value: 'effect' },
  { label: '文件', value: 'file' },
  { label: '情绪', value: 'mood' },
  { label: '摄影参数', value: 'camera' }
];

const SOURCE_TYPE = [
  { label: '用户创建', value: 'USER' },
  { label: '官方复制', value: 'OFFICIAL_COPY' },
  { label: 'AI生成', value: 'AI_GENERATED' }
];

const config: CrudConfig = {
  title: '用户参考资产',
  permPrefix: 'aid:assetuser',
  rowKey: 'id',
  viewable: true,
  modalWidth: 720,
  api: {
    list: listAssetuser,
    get: getAssetuser,
    add: addAssetuser,
    update: updateAssetuser,
    remove: delAssetuser,
    exportUrl: '/aid/assetuser/export'
  },
  searchFields: [
    { name: 'assetName', label: '资产名称', type: 'input' },
    { name: 'assetType', label: '资产类型', type: 'select', options: ASSET_TYPE },
    { name: 'status', label: '状态', type: 'dict', dictType: 'one_or_zero' }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '资产类型', dataIndex: 'assetType', width: 120, render: (v: string) => ASSET_TYPE.find((o) => o.value === v)?.label || v },
    { title: '资产名称', dataIndex: 'assetName', width: 160, ellipsis: true },
    { title: '主图', dataIndex: 'imageUrl', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 40, objectFit: 'cover', borderRadius: 4 }} /> : '-' },
    { title: '来源', dataIndex: 'sourceType', width: 110, render: (v: string) => SOURCE_TYPE.find((o) => o.value === v)?.label || v },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    { title: '状态', dataIndex: 'status', width: 100, dictType: 'one_or_zero' },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'userId', label: '用户ID', type: 'number', required: true },
    { name: 'assetType', label: '资产类型', type: 'select', options: ASSET_TYPE, required: true },
    { name: 'assetName', label: '资产名称', required: true, maxLength: 100 },
    { name: 'personalityDesc', label: '特征描述/生成约束', type: 'textarea', span: 24 },
    { name: 'promptText', label: '提示词内容', type: 'textarea', span: 24 },
    { name: 'imageUrl', label: '主图', type: 'image', span: 24 },
    { name: 'sourceType', label: '来源类型', type: 'select', options: SOURCE_TYPE, initialValue: 'USER' },
    { name: 'sortOrder', label: '排序值', type: 'number', initialValue: 0 },
    { name: 'status', label: '状态', type: 'dict', dictType: 'one_or_zero', initialValue: '0' },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ],
  dictTypes: ['one_or_zero']
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
