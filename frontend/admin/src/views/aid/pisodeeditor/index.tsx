import React from 'react';
import { Progress, Tag } from 'antd';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listPisodeeditor, getPisodeeditor, addPisodeeditor, updatePisodeeditor, delPisodeeditor
} from '@/api/aid/pisodeeditor';

const EXPORT_STATUS = [
  { label: '未导出/JSON已修改', value: 0, color: 'default' },
  { label: '正在合成中', value: 1, color: 'processing' },
  { label: '导出成功', value: 2, color: 'success' },
  { label: '导出失败', value: 3, color: 'error' }
];

const config: CrudConfig = {
  title: '视频剪辑成片',
  permPrefix: 'aid:pisodeeditor',
  rowKey: 'id',
  viewable: true,
  modalWidth: 760,
  api: {
    list: listPisodeeditor,
    get: getPisodeeditor,
    add: addPisodeeditor,
    update: updatePisodeeditor,
    remove: delPisodeeditor,
    exportUrl: '/aid/pisodeeditor/export'
  },
  searchFields: [
    { name: 'exportStatus', label: '导出状态', type: 'select', options: EXPORT_STATUS.map((o) => ({ label: o.label, value: o.value })) }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 100 },
    { title: '剧集ID', dataIndex: 'episodeId', width: 100 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '封面', dataIndex: 'coverUrl', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 56, objectFit: 'cover', borderRadius: 4 }} /> : '-' },
    { title: '导出状态', dataIndex: 'exportStatus', width: 130, render: (v: number) => {
      const hit = EXPORT_STATUS.find((o) => o.value === v);
      return hit ? <Tag color={hit.color}>{hit.label}</Tag> : '-';
    } },
    { title: '进度', dataIndex: 'exportProgress', width: 120, render: (v: number) => v != null ? <Progress percent={Number(v)} size="small" /> : '-' },
    { title: '任务ID', dataIndex: 'exportTaskId', width: 150, ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'projectId', label: '所属项目ID', type: 'number', required: true },
    { name: 'episodeId', label: '所属剧集ID', type: 'number', required: true },
    { name: 'userId', label: '最后修改用户ID', type: 'number' },
    { name: 'timelineJson', label: '工程配置JSON', type: 'textarea', span: 24 },
    { name: 'finalVideoUrl', label: '成片URL', span: 24 },
    { name: 'coverUrl', label: '封面图', type: 'image', span: 24 },
    { name: 'exportStatus', label: '导出状态', type: 'select', options: EXPORT_STATUS.map((o) => ({ label: o.label, value: o.value })) },
    { name: 'exportProgress', label: '导出进度(0-100)', type: 'number' },
    { name: 'exportTaskId', label: '导出任务ID' },
    { name: 'errorMsg', label: '错误信息', type: 'textarea', span: 24 },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
