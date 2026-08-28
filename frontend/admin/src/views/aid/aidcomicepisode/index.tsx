import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listAidcomicepisode, getAidcomicepisode, addAidcomicepisode, updateAidcomicepisode, delAidcomicepisode
} from '@/api/aid/aidcomicepisode';
import {
  GEN_MODE_OPTIONS, STORYBOARD_MODE_OPTIONS, CREATION_MODE_OPTIONS,
  CURRENT_STEP_OPTIONS, EPISODE_STATUS_OPTIONS, YES_NO_OPTIONS,
  getLabelByValue, getAntdTagColor
} from '@/utils/enums';

const config: CrudConfig = {
  title: '剧集',
  permPrefix: 'aid:aidcomicepisode',
  rowKey: 'id',
  viewable: true,
  modalWidth: 720,
  api: {
    list: listAidcomicepisode,
    get: getAidcomicepisode,
    add: addAidcomicepisode,
    update: updateAidcomicepisode,
    remove: delAidcomicepisode,
    exportUrl: '/aid/aidcomicepisode/export'
  },
  searchFields: [
    { name: 'episodeNo', label: '第几集', type: 'input' },
    { name: 'comicTitle', label: '单集标题', type: 'input' },
    { name: 'genMode', label: '生成模式', type: 'select', options: GEN_MODE_OPTIONS },
    { name: 'storyboardMode', label: '分镜模式', type: 'select', options: STORYBOARD_MODE_OPTIONS },
    { name: 'creationMode', label: '创作模式', type: 'select', options: CREATION_MODE_OPTIONS },
    { name: 'status', label: '状态', type: 'select', options: EPISODE_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value })) }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 100 },
    { title: '第几集', dataIndex: 'episodeNo', width: 80 },
    { title: '单集标题', dataIndex: 'comicTitle', width: 180, ellipsis: true },
    { title: '封面', dataIndex: 'comicCoverUrl', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 56, objectFit: 'cover', borderRadius: 4 }} /> : '-' },
    { title: '生成模式', dataIndex: 'genMode', width: 100, render: (v: string) => getLabelByValue(GEN_MODE_OPTIONS, v) },
    { title: '分镜模式', dataIndex: 'storyboardMode', width: 100, render: (v: string) => getLabelByValue(STORYBOARD_MODE_OPTIONS, v) },
    { title: '创作模式', dataIndex: 'creationMode', width: 110, render: (v: string) => getLabelByValue(CREATION_MODE_OPTIONS, v) },
    { title: '步骤', dataIndex: 'currentStep', width: 120, render: (v: number) => <Tag color={getAntdTagColor(CURRENT_STEP_OPTIONS, v)}>{getLabelByValue(CURRENT_STEP_OPTIONS, v)}</Tag> },
    { title: '状态', dataIndex: 'status', width: 110, render: (v: number) => <Tag color={getAntdTagColor(EPISODE_STATUS_OPTIONS, v)}>{getLabelByValue(EPISODE_STATUS_OPTIONS, v)}</Tag> },
    { title: '是否公开', dataIndex: 'isPublic', width: 100, render: (v: string) => <Tag color={getAntdTagColor(YES_NO_OPTIONS, v)}>{getLabelByValue(YES_NO_OPTIONS, v)}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'projectId', label: '所属项目ID', type: 'number', required: true },
    { name: 'episodeNo', label: '第几集', type: 'number', required: true },
    { name: 'comicTitle', label: '单集标题', required: true, span: 24 },
    { name: 'comicDesc', label: '单集描述', type: 'textarea', span: 24 },
    { name: 'comicCoverUrl', label: '单集封面图', type: 'image', span: 24 },
    { name: 'genMode', label: '生成模式', type: 'select', options: GEN_MODE_OPTIONS },
    { name: 'storyboardMode', label: '分镜模式', type: 'select', options: STORYBOARD_MODE_OPTIONS },
    { name: 'creationMode', label: '创作模式', type: 'select', options: CREATION_MODE_OPTIONS },
    { name: 'currentStep', label: '当前步骤', type: 'select', options: CURRENT_STEP_OPTIONS.map((o) => ({ label: o.label, value: o.value })) },
    { name: 'statusReason', label: '状态原因', type: 'textarea', span: 24 },
    { name: 'isPublic', label: '是否公开', type: 'select', options: YES_NO_OPTIONS },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
