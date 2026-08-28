import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listAudioRecord, getAudioRecord
} from '@/api/aid/audio';
import { AUDIO_SOURCE_OPTIONS, YES_NO_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';

const config: CrudConfig = {
  title: '分镜配音业务记录',
  permPrefix: 'aid:audiorecord',
  rowKey: 'id',
  modalWidth: 720,
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  api: {
    list: listAudioRecord,
    get: getAudioRecord,
    exportUrl: '/aid/audiorecord/export'
  },
  searchFields: [
    { name: 'userId', label: '用户ID', type: 'input' },
    { name: 'storyboardId', label: '分镜ID', type: 'input' },
    { name: 'audioSource', label: '配音来源', type: 'select', options: AUDIO_SOURCE_OPTIONS.map((o) => ({ label: o.label, value: o.value })) },
    { name: 'enableLipSync', label: '开启对口型', type: 'select', options: YES_NO_OPTIONS }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '分镜ID', dataIndex: 'storyboardId', width: 100 },
    { title: '配音来源', dataIndex: 'audioSource', width: 120, render: (v: number) => getLabelByValue(AUDIO_SOURCE_OPTIONS, v) },
    { title: '配音文件', dataIndex: 'audioUrl', ellipsis: true, width: 200, render: (v: string) => v ? <audio src={v} controls style={{ width: 180, height: 28 }} /> : '-' },
    { title: '配音文字', dataIndex: 'ttsText', ellipsis: true, width: 200 },
    { title: '配音模型ID', dataIndex: 'voiceModelId', width: 110 },
    { title: '音色编码', dataIndex: 'timbreCode', width: 140, ellipsis: true },
    { title: '开启对口型', dataIndex: 'enableLipSync', width: 110, render: (v: string) => <Tag color={getAntdTagColor(YES_NO_OPTIONS, v)}>{getLabelByValue(YES_NO_OPTIONS, v)}</Tag> },
    { title: '对口型视频', dataIndex: 'syncVideoUrl', ellipsis: true, width: 200 },
    { title: '业务状态', dataIndex: 'status', width: 110 },
    { title: '失败原因', dataIndex: 'errorMessage', width: 110 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: []
};

export default function Page() {
  return <CrudPage config={config} />;
}
