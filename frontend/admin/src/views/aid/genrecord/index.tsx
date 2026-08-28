import React from 'react';
import { Button, Image, Tag } from 'antd';
import { PlayCircleOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listGenrecord, getGenrecord
} from '@/api/aid/genrecord';
import { GEN_TYPE_OPTIONS, YES_NO_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';

const config: CrudConfig = {
  title: '生图/生视频记录',
  permPrefix: 'aid:genrecord',
  rowKey: 'id',
  viewable: true,
  modalWidth: 720,
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  api: {
    list: listGenrecord,
    get: getGenrecord,
    exportUrl: '/aid/genrecord/export'
  },
  searchFields: [
    { name: 'genType', label: '生成类型', type: 'select', options: GEN_TYPE_OPTIONS },
    { name: 'isSelected', label: '是否选中', type: 'select', options: YES_NO_OPTIONS }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户ID', dataIndex: 'userId', width: 100 },
    { title: '分镜ID', dataIndex: 'storyboardId', width: 100 },
    { title: '生成类型', dataIndex: 'genType', width: 100, render: (v: string) => getLabelByValue(GEN_TYPE_OPTIONS, v) },
    { title: '结果预览', dataIndex: 'fileUrl', width: 90, render: (v: string, r: any) => {
      if (!v) return '-';
      const isVideo = r.genType === 'i2v' || r.genType === 'multi' || r.genType === 'edge';
      if (isVideo) return <Button type="link" size="small" title={v} icon={<PlayCircleOutlined />} onClick={() => window.open(v)} />;
      return <Image src={v} alt="" width={40} height={40} style={{ objectFit: 'cover', borderRadius: 4 }} />;
    } },
    { title: '模型ID', dataIndex: 'modelId', width: 110 },
    { title: '视频时长', dataIndex: 'videoDuration', width: 100 },
    { title: '消耗积分', dataIndex: 'costCredits', width: 100 },
    { title: '是否选中', dataIndex: 'isSelected', width: 100, render: (v: string) => <Tag color={getAntdTagColor(YES_NO_OPTIONS, v)}>{getLabelByValue(YES_NO_OPTIONS, v)}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'userId', label: '用户ID' },
    { name: 'storyboardId', label: '分镜ID' },
    { name: 'genType', label: '生成类型' },
    { name: 'fileUrl', label: '结果文件', span: 24 },
    { name: 'modelId', label: '模型ID' },
    { name: 'promptText', label: '提示词', type: 'textarea', span: 24 },
    { name: 'userInputText', label: '补充文本', type: 'textarea', span: 24 },
    { name: 'baseImageId', label: '底图ID' },
    { name: 'firstImageId', label: '首图ID' },
    { name: 'lastImageId', label: '尾图ID' },
    { name: 'videoDuration', label: '视频时长' },
    { name: 'soundDesc', label: '音效描述', type: 'textarea', span: 24 },
    { name: 'costCredits', label: '消耗积分' },
    { name: 'isSelected', label: '是否选中' },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ]
};

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  return <CrudPage config={scopedConfig(config, scope)} />;
}
