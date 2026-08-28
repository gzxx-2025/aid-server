import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listExtracttask, getExtracttask
} from '@/api/aid/extracttask';
import { TASK_STATUS_OPTIONS, getAntdTagColor, getLabelByValue } from '@/utils/enums';

const config: CrudConfig = {
  title: '资产提取任务',
  permPrefix: 'aid:extracttask',
  rowKey: 'id',
  viewable: true,
  modalWidth: 720,
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  api: {
    list: listExtracttask,
    get: getExtracttask,
    exportUrl: '/aid/extracttask/export'
  },
  searchFields: [
    {
      name: 'status',
      label: '状态',
      type: 'select',
      options: TASK_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value }))
    },
    { name: 'modelName', label: 'AI模型名称', type: 'input' }
  ],
  columns: [
    { title: '主键', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 100 },
    { title: '剧集ID', dataIndex: 'episodeId', width: 100 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '任务类型', dataIndex: 'taskType', width: 120 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v: string, r: any) => {
        if (!v) return '-';
        const tag = <Tag color={getAntdTagColor(TASK_STATUS_OPTIONS, v)}>{getLabelByValue(TASK_STATUS_OPTIONS, v, v)}</Tag>;
        // 排队中：附带排队位次（后端 C 端 detail 返回 queuePosition，列表若带上则展示）
        if (v === 'QUEUED' && r.queuePosition != null) {
          return <span>{tag}<span style={{ color: '#94a3b8', marginLeft: 4 }}>第 {r.queuePosition} 位</span></span>;
        }
        return tag;
      }
    },
    { title: 'AI模型名称', dataIndex: 'modelName', width: 180, ellipsis: true },
    { title: '提取总数', dataIndex: 'totalCount', width: 90 },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: []
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
