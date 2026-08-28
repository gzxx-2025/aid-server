import React from 'react';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listStoryboard, getStoryboard, addStoryboard, updateStoryboard, delStoryboard
} from '@/api/aid/storyboard';

const config: CrudConfig = {
  title: '分镜时间轴',
  permPrefix: 'aid:storyboard',
  rowKey: 'id',
  viewable: true,
  modalWidth: 900,
  api: {
    list: listStoryboard,
    get: getStoryboard,
    add: addStoryboard,
    update: updateStoryboard,
    remove: delStoryboard,
    exportUrl: '/aid/storyboard/export'
  },
  searchFields: [
    { name: 'title', label: '分镜标题', type: 'input' }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 90 },
    { title: '剧集ID', dataIndex: 'episodeId', width: 90 },
    { title: '分镜序号', dataIndex: 'sortOrder', width: 90 },
    { title: '标题', dataIndex: 'title', width: 200, ellipsis: true },
    { title: '分镜图ID', dataIndex: 'finalImageId', width: 110 },
    { title: '视频ID', dataIndex: 'finalVideoId', width: 110 },
    { title: '配音ID', dataIndex: 'finalAudioId', width: 110 },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'projectId', label: '项目ID', type: 'number', required: true },
    { name: 'episodeId', label: '剧集ID', type: 'number' },
    { name: 'sortOrder', label: '分镜序号', type: 'number' },
    { name: 'title', label: '分镜标题', span: 24 },
    { name: 'sceneIds', label: '场景IDs（逗号分隔）' },
    { name: 'characterIds', label: '角色IDs（逗号分隔）' },
    { name: 'propIds', label: '道具IDs（逗号分隔）' },
    { name: 'poseIds', label: '姿态IDs（逗号分隔）' },
    { name: 'expressionIds', label: '表情IDs（逗号分隔）' },
    { name: 'effectIds', label: '特效IDs（逗号分隔）' },
    { name: 'sketchIds', label: '手绘IDs（逗号分隔）' },
    { name: 'shotSize', label: '景别', placeholder: '特写/全景/近景等' },
    { name: 'cameraAngle', label: '拍摄角度', placeholder: '平视/俯拍/第三人称等' },
    { name: 'focalLength', label: '焦距', placeholder: '50mm 等' },
    { name: 'colorTone', label: '色彩色调' },
    { name: 'lighting', label: '光线', placeholder: '逆光/顶光等' },
    { name: 'exposureBlur', label: '曝光虚化', placeholder: '长曝光/浅景深' },
    { name: 'imagePrompt', label: '画面描述', type: 'textarea', span: 24 },
    { name: 'cameraMovement', label: '运镜', placeholder: '推拉摇移/航拍/360滚动等' },
    { name: 'shootingTechnique', label: '拍摄手法', placeholder: '希区柯克变焦/延时等' },
    { name: 'videoPrompt', label: '动作描述', type: 'textarea', span: 24 },
    { name: 'finalImageId', label: '最终分镜图ID', type: 'number' },
    { name: 'finalVideoId', label: '最终视频ID', type: 'number' },
    { name: 'finalAudioId', label: '最终配音ID', type: 'number' },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
