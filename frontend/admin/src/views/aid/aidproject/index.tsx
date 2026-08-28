import React from 'react';
import { Tag } from 'antd';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import HiddenStylePromptJsonField from '@/components/HiddenStylePromptJsonField';
import {
  listAidproject, getAidproject, addAidproject, updateAidproject, delAidproject
} from '@/api/aid/aidproject';
import {
  PROJECT_TYPE_OPTIONS, SCRIPT_TYPE_OPTIONS, ASPECT_RATIO_OPTIONS,
  GEN_MODE_OPTIONS, STORYBOARD_MODE_OPTIONS, CREATION_MODE_OPTIONS, PROJECT_STATUS_OPTIONS,
  YES_NO_OPTIONS, CURRENT_STEP_WITH_MOVIE_OPTIONS, getLabelByValue, getAntdTagColor
} from '@/utils/enums';

const config: CrudConfig = {
  title: '漫剧项目',
  permPrefix: 'aid:aidproject',
  rowKey: 'id',
  modalWidth: 760,
  viewable: true,
  hideAdd: true,
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
    { name: 'defaultGenMode', label: '生成模式', type: 'select', options: GEN_MODE_OPTIONS },
    { name: 'status', label: '项目状态', type: 'select', options: PROJECT_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value })) }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户ID', dataIndex: 'userId', width: 90 },
    { title: '项目名称', dataIndex: 'projectName', width: 160, ellipsis: true },
    { title: '项目类型', dataIndex: 'projectType', width: 100, render: (v: string) => getLabelByValue(PROJECT_TYPE_OPTIONS, v) },
    { title: '封面', dataIndex: 'coverUrl', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 56, objectFit: 'cover', borderRadius: 4 }} /> : '-' },
    { title: '画面比例', dataIndex: 'aspectRatio', width: 100 },
    { title: '剧本类型', dataIndex: 'scriptType', width: 110, render: (v: string) => getLabelByValue(SCRIPT_TYPE_OPTIONS, v) },
    { title: '风格名称', dataIndex: 'videoStyleType', width: 140, ellipsis: true },
    { title: '生成模式', dataIndex: 'defaultGenMode', width: 100, render: (v: string) => getLabelByValue(GEN_MODE_OPTIONS, v) },
    { title: '分镜模式', dataIndex: 'defaultStoryboardMode', width: 100, render: (v: string) => getLabelByValue(STORYBOARD_MODE_OPTIONS, v) },
    { title: '创作模式', dataIndex: 'defaultCreationMode', width: 110, render: (v: string) => getLabelByValue(CREATION_MODE_OPTIONS, v) },
    { title: '当前步骤', dataIndex: 'currentStep', width: 120, render: (v: number) => <Tag color={getAntdTagColor(CURRENT_STEP_WITH_MOVIE_OPTIONS, v)}>{getLabelByValue(CURRENT_STEP_WITH_MOVIE_OPTIONS, v)}</Tag> },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: number) => <Tag color={getAntdTagColor(PROJECT_STATUS_OPTIONS, v)}>{getLabelByValue(PROJECT_STATUS_OPTIONS, v)}</Tag> },
    { title: '是否公开', dataIndex: 'isPublic', width: 100, render: (v: string) => <Tag color={getAntdTagColor(YES_NO_OPTIONS, v)}>{getLabelByValue(YES_NO_OPTIONS, v)}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'userId', label: '所属用户ID', type: 'number', required: true },
    { name: 'projectName', label: '项目名称', required: true, span: 24 },
    { name: 'projectDesc', label: '项目描述', type: 'textarea', span: 24 },
    { name: 'projectType', label: '项目类型', type: 'select', options: PROJECT_TYPE_OPTIONS },
    { name: 'aspectRatio', label: '画面比例', type: 'select', options: ASPECT_RATIO_OPTIONS },
    { name: 'coverUrl', label: '封面图', type: 'image', span: 24 },
    { name: 'scriptType', label: '剧本类型', type: 'select', options: SCRIPT_TYPE_OPTIONS },
    { name: 'videoStyleType', label: '风格名称', disabled: true },
    { name: 'videoStyleValue', label: '公开风格描述快照', type: 'textarea', span: 24, disabled: true },
    {
      name: 'hiddenStylePromptJson',
      label: '隐藏风格提示词快照',
      type: 'custom',
      span: 24,
      render: () => <HiddenStylePromptJsonField readOnly />,
      viewRender: (value: string | null) => <HiddenStylePromptJsonField value={value} readOnly />
    },
    { name: 'defaultGenMode', label: '默认生成模式', type: 'select', options: GEN_MODE_OPTIONS },
    { name: 'defaultStoryboardMode', label: '默认分镜模式', type: 'select', options: STORYBOARD_MODE_OPTIONS },
    { name: 'defaultCreationMode', label: '默认创作模式', type: 'select', options: CREATION_MODE_OPTIONS },
    { name: 'currentStep', label: '当前步骤', type: 'select', options: CURRENT_STEP_WITH_MOVIE_OPTIONS.map((o) => ({ label: o.label, value: o.value })) },
    { name: 'statusReason', label: '状态原因', type: 'textarea', span: 24 },
    { name: 'isPublic', label: '是否公开', type: 'select', options: YES_NO_OPTIONS },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ],
  beforeSubmit: (data: any) => {
    const payload = { ...data };
    // 项目公开风格与隐藏模板共同组成创建/切换时的快照，后台普通编辑不得覆盖。
    delete payload.videoStyleType;
    delete payload.videoStyleValue;
    delete payload.hiddenStylePromptJson;
    return payload;
  }
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
