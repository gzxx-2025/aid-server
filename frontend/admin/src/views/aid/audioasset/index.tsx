import React, { useState } from 'react';
import { Button, Descriptions, Drawer, Tag, message } from 'antd';
import { EyeOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listAudioAsset, getAudioAsset, delAudioAsset
} from '@/api/aid/audioasset';
import { parseTime } from '@/utils/ruoyi';
import { resolveEmotionLabel } from '../voicelibrary/constants';

export default function AudioAssetPage({ scope }: { scope?: EmbeddedScope } = {}) {
  const [detail, setDetail] = useState<any | null>(null);

  const config: CrudConfig = {
    title: '音频资产',
    perms: {
      remove: 'aid:audio-asset:remove',
      export: 'aid:audio-asset:export',
      query: 'aid:audio-asset:query'
    },
    rowKey: 'id',
    hideAdd: true,
    hideEdit: true,
    api: {
      list: listAudioAsset,
      get: getAudioAsset,
      remove: delAudioAsset
    },
    searchFields: [
      { name: 'projectId', label: '项目', type: 'input' },
      { name: 'episodeId', label: '剧集', type: 'input' },
      { name: 'storyboardId', label: '分镜', type: 'input' },
      { name: 'voiceName', label: '音色', type: 'input', placeholder: '音色名称' },
      { name: 'assetTitle', label: '标题', type: 'input' },
      { name: 'audioSource', label: '来源', type: 'select', options: [
        { label: 'AI生成', value: 1 },
        { label: '用户上传', value: 2 }
      ] }
    ],
    columns: [
      { title: 'ID', dataIndex: 'id', width: 80 },
      { title: '用户ID', dataIndex: 'userId', width: 100 },
      { title: '项目/剧集/分镜', key: 'triple', width: 160, render: (_: any, r: any) => (
        <div style={{ fontSize: 12 }}>
          <div>项目：{r.projectId || '-'}</div>
          <div>剧集：{r.episodeId || '-'}</div>
          <div>分镜：{r.storyboardId || '-'}</div>
        </div>
      ) },
      { title: '标题', dataIndex: 'assetTitle', ellipsis: true, width: 200 },
      { title: '音色', key: 'voice', width: 180, render: (_: any, r: any) => (
        <div style={{ fontSize: 12 }}>
          <div style={{ fontWeight: 500 }}>{r.voiceName || '-'}</div>
          <div style={{ color: '#94a3b8' }}>编码：{r.voiceCode || '-'}</div>
        </div>
      ) },
      { title: '来源', dataIndex: 'audioSource', width: 90, render: (v: number) => v === 1 ? <Tag color="blue">AI生成</Tag> : v === 2 ? <Tag color="green">用户上传</Tag> : '-' },
      { title: '音频', dataIndex: 'audioUrl', width: 320, render: (v: string) => v ? <audio src={v} controls preload="none" style={{ width: 300, height: 32 }} /> : '-' },
      { title: '格式', dataIndex: 'audioFormat', width: 80 },
      { title: '创建时间', dataIndex: 'createTime', width: 160 }
    ],
    rowActions: [
      {
        label: '详情',
        icon: <EyeOutlined />,
        perm: 'aid:audio-asset:query',
        onClick: async (row) => {
          const res: any = await getAudioAsset(row.id);
          setDetail(res.data || res);
        }
      }
    ]
  };

  const numDash = (v: any) => (v === null || v === undefined ? '-' : v);

  return (
    <>
      <CrudPage config={scopedConfig(config, scope)} />
      <Drawer open={!!detail} width={560} title={`音频资产详情 #${detail?.id || ''}`} onClose={() => setDetail(null)}>
        {detail && (
          <>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="资产ID">{detail.id}</Descriptions.Item>
              <Descriptions.Item label="标题">{detail.assetTitle || '-'}</Descriptions.Item>
              <Descriptions.Item label="归属用户">{detail.userId || '-'}</Descriptions.Item>
              <Descriptions.Item label="项目 / 剧集 / 分镜">{detail.projectId || '-'} / {detail.episodeId || '-'} / {detail.storyboardId || '-'}</Descriptions.Item>
              <Descriptions.Item label="音色">{detail.voiceName || '-'} (编码: {detail.voiceCode || '-'})</Descriptions.Item>
              <Descriptions.Item label="情感 / 语速 / 音量 / 音调">{detail.emotion ? resolveEmotionLabel(detail.emotion) : '-'} / {numDash(detail.speechRate)} / {numDash(detail.loudnessRate)} / {numDash(detail.pitch)}</Descriptions.Item>
              <Descriptions.Item label="格式 / 采样率">{detail.audioFormat || '-'} / {detail.sampleRate || '-'}</Descriptions.Item>
              <Descriptions.Item label="文件大小(字节)">{detail.fileSize || '-'}</Descriptions.Item>
              <Descriptions.Item label="来源">{detail.audioSource === 1 ? <Tag color="blue">AI生成</Tag> : detail.audioSource === 2 ? <Tag color="green">用户上传</Tag> : '-'}</Descriptions.Item>
              <Descriptions.Item label="关联任务">任务：{detail.audioTaskId || '-'} / 媒体：{detail.mediaTaskId || '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{parseTime(detail.createTime)}</Descriptions.Item>
              <Descriptions.Item label="更新时间">{parseTime(detail.updateTime)}</Descriptions.Item>
            </Descriptions>
            {detail.ttsText && (
              <div style={{ marginTop: 16 }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>配音文字</div>
                <pre style={{ background: '#f5f7fa', padding: 12, borderRadius: 6, whiteSpace: 'pre-wrap', maxHeight: 200, overflow: 'auto' }}>{detail.ttsText}</pre>
              </div>
            )}
            {detail.audioUrl && (
              <div style={{ marginTop: 16 }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>音频试听</div>
                <audio src={detail.audioUrl} controls style={{ width: '100%' }} />
              </div>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
