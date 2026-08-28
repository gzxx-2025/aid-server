import React from 'react';
import { Tag } from 'antd';
import { LoginOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import {
  listAidproject, getAidproject, addAidproject, updateAidproject, delAidproject
} from '@/api/aid/aidproject';
import {
  PROJECT_TYPE_OPTIONS, PROJECT_STATUS_OPTIONS, CURRENT_STEP_WITH_MOVIE_OPTIONS,
  getLabelByValue, getAntdTagColor
} from '@/utils/enums';

interface Props {
  onEnter: (project: any) => void;
}

/**
 * 项目列表视图（工作台入口）：聚焦项目本身，列表精简，提供「进入工作台」。
 */
export default function ProjectListView({ onEnter }: Props) {
  const config: CrudConfig = {
    title: '漫剧项目',
    permPrefix: 'aid:aidproject',
    rowKey: 'id',
    viewable: true,
    modalWidth: 760,
    api: {
      list: listAidproject,
      get: getAidproject,
      add: addAidproject,
      update: updateAidproject,
      remove: delAidproject,
      exportUrl: '/aid/aidproject/export'
    },
    searchFields: [
      { name: 'userId', label: '用户ID', type: 'input' },
      { name: 'projectName', label: '项目名称', type: 'input' },
      { name: 'projectType', label: '项目类型', type: 'select', options: PROJECT_TYPE_OPTIONS },
      { name: 'status', label: '项目状态', type: 'select', options: PROJECT_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value })) }
    ],
    columns: [
      { title: 'ID', dataIndex: 'id', width: 70 },
      {
        title: '封面', dataIndex: 'coverUrl', width: 70,
        render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 56, objectFit: 'cover', borderRadius: 6 }} /> : <span style={{ color: '#cbd5e1' }}>无</span>
      },
      { title: '项目名称', dataIndex: 'projectName', width: 200, ellipsis: true, render: (v: string) => <span style={{ fontWeight: 600 }}>{v}</span> },
      { title: '用户ID', dataIndex: 'userId', width: 90 },
      { title: '类型', dataIndex: 'projectType', width: 90, render: (v: string) => <Tag color={v === 'movie' ? 'purple' : 'blue'}>{getLabelByValue(PROJECT_TYPE_OPTIONS, v)}</Tag> },
      { title: '当前步骤', dataIndex: 'currentStep', width: 130, render: (v: number) => <Tag color={getAntdTagColor(CURRENT_STEP_WITH_MOVIE_OPTIONS, v)}>{getLabelByValue(CURRENT_STEP_WITH_MOVIE_OPTIONS, v)}</Tag> },
      { title: '状态', dataIndex: 'status', width: 100, render: (v: number) => <Tag color={getAntdTagColor(PROJECT_STATUS_OPTIONS, v)}>{getLabelByValue(PROJECT_STATUS_OPTIONS, v)}</Tag> },
      { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
    ],
    formFields: [
      { name: 'userId', label: '所属用户ID', type: 'number', required: true },
      { name: 'projectName', label: '项目名称', required: true, span: 24 },
      { name: 'projectDesc', label: '项目描述', type: 'textarea', span: 24 },
      { name: 'projectType', label: '项目类型', type: 'select', options: PROJECT_TYPE_OPTIONS },
      { name: 'coverUrl', label: '封面图', type: 'image', span: 24 },
      { name: 'remark', label: '备注', type: 'textarea', span: 24 }
    ],
    rowActions: [
      {
        label: '进入工作台',
        icon: <LoginOutlined />,
        onClick: (row) => onEnter(row)
      }
    ]
  };

  return <CrudPage config={config} />;
}
