import React, { useState } from 'react';
import { Image, Modal, Tag } from 'antd';
import { PlayCircleOutlined, PictureOutlined, VerticalAlignTopOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import ImageUpload from '@/components/ImageUpload';
import {
  listNotice, getNotice, addNotice, updateNotice, delNotice
} from '@/api/aid/notice';

/** 公告类型选项 */
const NOTICE_TYPE_OPTIONS = [
  { label: '系统公告', value: 'system' },
  { label: '活动公告', value: 'activity' },
  { label: '更新公告', value: 'update' }
];

/** 公告类型标签颜色 */
const NOTICE_TYPE_COLORS: Record<string, string> = {
  system: 'blue',
  activity: 'orange',
  update: 'green'
};

/** 是否选项（0否 1是） */
const YES_NO_OPTIONS = [
  { label: '否', value: '0' },
  { label: '是', value: '1' }
];

/** 状态选项 */
const STATUS_OPTIONS = [
  { label: '显示', value: '0' },
  { label: '隐藏', value: '1' }
];

function labelOf(options: Array<{ label: string; value: any }>, value: any) {
  return options.find((o) => o.value === value)?.label ?? value;
}

/** 媒体单元格：图片走灯箱预览；视频公告点击弹窗内联播放（imageUrl 作为封面） */
function NoticeMediaCell({ image, video, isVideo, large }: { image?: string; video?: string; isVideo?: boolean; large?: boolean }) {
  const [open, setOpen] = useState(false);

  const w = large ? 260 : 56;
  const h = large ? 150 : 34;

  if (isVideo && video) {
    return (
      <>
        <div
          onClick={() => setOpen(true)}
          style={{ width: w, height: h, borderRadius: large ? 8 : 6, border: '1px solid #f0f0f0', position: 'relative', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: image ? `center/cover no-repeat url(${image})` : '#1f2937' }}
          title="点击预览视频"
        >
          <PlayCircleOutlined style={{ color: '#fff', fontSize: large ? 40 : 18, textShadow: '0 1px 4px rgba(0,0,0,.6)' }} />
        </div>
        <Modal open={open} onCancel={() => setOpen(false)} footer={null} width={720} destroyOnClose title="视频预览" centered>
          <video src={video} controls autoPlay style={{ width: '100%', maxHeight: '70vh', borderRadius: 8, background: '#000' }} />
        </Modal>
      </>
    );
  }
  if (!image) return <span style={{ color: '#94a3b8' }}>-</span>;
  // 图片：缩略图 + 点击灯箱预览
  return (
    <Image
      src={image}
      width={w}
      height={h}
      style={{ objectFit: 'cover', borderRadius: large ? 8 : 6, border: '1px solid #f0f0f0' }}
      preview={{ mask: <PictureOutlined /> }}
      fallback="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI1NiIgaGVpZ2h0PSIzNCI+PHJlY3Qgd2lkdGg9IjU2IiBoZWlnaHQ9IjM0IiBmaWxsPSIjZjVmNWY1Ii8+PC9zdmc+"
    />
  );
}

export default function NoticePage() {
  const config: CrudConfig = {
    title: 'C端公告',
    permPrefix: 'aid:notice',
    rowKey: 'id',
    modalWidth: 820,
    viewable: true,
    api: {
      list: listNotice,
      get: getNotice,
      add: addNotice,
      update: updateNotice,
      remove: delNotice,
      exportUrl: '/aid/notice/export'
    },
    searchFields: [
      { name: 'title', label: '标题', type: 'input' },
      { name: 'noticeType', label: '公告类型', type: 'select', options: NOTICE_TYPE_OPTIONS },
      { name: 'isVideo', label: '是否视频', type: 'select', options: YES_NO_OPTIONS },
      { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS }
    ],
    columns: [
      { title: 'ID', dataIndex: 'id', width: 70 },
      {
        title: '标题', dataIndex: 'title', width: 200, ellipsis: true,
        render: (v: string, r: any) => (
          <span title={v} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, maxWidth: '100%' }}>
            {r.isTop === '1' && <VerticalAlignTopOutlined style={{ color: '#f5222d', flexShrink: 0 }} title="置顶" />}
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v}</span>
          </span>
        )
      },
      {
        title: '媒体',
        dataIndex: 'imageUrl',
        width: 90,
        render: (v: string, r: any) => <NoticeMediaCell image={v} video={r.videoUrl} isVideo={r.isVideo === '1'} />
      },
      { title: '类型', dataIndex: 'noticeType', width: 96, render: (v: string) => <Tag color={NOTICE_TYPE_COLORS[v]}>{labelOf(NOTICE_TYPE_OPTIONS, v)}</Tag> },
      { title: '是否视频', dataIndex: 'isVideo', width: 84, render: (v: string) => (v === '1' ? <Tag color="purple">视频</Tag> : <Tag>图文</Tag>) },
      { title: '描述', dataIndex: 'description', ellipsis: true, width: 180 },
      { title: '排序', dataIndex: 'sortOrder', width: 70 },
      { title: '浏览量', dataIndex: 'viewCount', width: 90 },
      {
        title: '状态', dataIndex: 'status', width: 80,
        render: (v: string) => <Tag color={v === '0' ? 'green' : 'default'}>{labelOf(STATUS_OPTIONS, v)}</Tag>
      },
      { title: '发布时间', dataIndex: 'publishTime', dateFormat: true, width: 160 },
      { title: '生效开始', dataIndex: 'startTime', dateFormat: true, width: 160 },
      { title: '生效结束', dataIndex: 'endTime', dateFormat: true, width: 160 },
      { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
    ],
    formFields: [
      { name: 'title', label: '公告标题', required: true, maxLength: 128 },
      {
        name: 'noticeType', label: '公告类型', type: 'select', options: NOTICE_TYPE_OPTIONS, initialValue: 'system',
        viewRender: (v: any) => <Tag color={NOTICE_TYPE_COLORS[v]}>{labelOf(NOTICE_TYPE_OPTIONS, v)}</Tag>
      },
      {
        name: 'imageUrl', label: '图片（视频公告时作为封面）', span: 12, type: 'custom',
        render: () => (
          <ImageUpload maxCount={1} maxSize={20} accept="image/*" />
        ),
        viewRender: (v: any) => <NoticeMediaCell image={v} large />
      },
      {
        name: 'videoUrl', label: '视频（仅视频公告需上传）', span: 12, type: 'custom',
        render: () => (
          <ImageUpload maxCount={1} maxSize={100} accept="video/*" />
        ),
        viewRender: (v: any, row: any) => (v ? <NoticeMediaCell image={row?.imageUrl} video={v} isVideo large /> : <span style={{ color: '#94a3b8' }}>-</span>),
        // 选择「视频公告」时必须上传视频
        rules: [
          ({ getFieldValue }: any) => ({
            validator(_: any, value: any) {
              if (getFieldValue('isVideo') === '1' && !value) {
                return Promise.reject(new Error('视频公告必须上传视频'));
              }
              return Promise.resolve();
            }
          })
        ]
      },
      {
        name: 'isVideo', label: '是否视频公告', type: 'select', options: YES_NO_OPTIONS, initialValue: '0',
        viewRender: (v: any) => (v === '1' ? <Tag color="purple">视频</Tag> : <Tag>图文</Tag>)
      },
      {
        name: 'isTop', label: '是否置顶', type: 'select', options: YES_NO_OPTIONS, initialValue: '0',
        viewRender: (v: any) => (v === '1' ? <Tag color="red">置顶</Tag> : <Tag>否</Tag>)
      },
      { name: 'description', label: '公告描述', type: 'textarea', span: 24, maxLength: 512 },
      { name: 'content', label: '公告内容', type: 'richtext', span: 24, required: true },
      { name: 'sortOrder', label: '排序', type: 'number', initialValue: 0 },
      {
        name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS, initialValue: '0',
        viewRender: (v: any) => <Tag color={v === '0' ? 'green' : 'default'}>{labelOf(STATUS_OPTIONS, v)}</Tag>
      },
      { name: 'publishTime', label: '发布时间', type: 'date' },
      { name: 'startTime', label: '生效开始时间', type: 'date' },
      { name: 'endTime', label: '生效结束时间', type: 'date' },
      { name: 'remark', label: '备注', type: 'textarea', span: 24, maxLength: 500 }
    ]
  };

  return <CrudPage config={config} />;
}
