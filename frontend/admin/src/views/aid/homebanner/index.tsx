import React, { useState } from 'react';
import { Image, Modal, Tag } from 'antd';
import { PlayCircleOutlined, PictureOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import ImageUpload from '@/components/ImageUpload';
import {
  listHomebanner, getHomebanner, addHomebanner, updateHomebanner, delHomebanner
} from '@/api/aid/homebanner';

/** 资源类型选项 */
const BANNER_TYPE_OPTIONS = [
  { label: '图片', value: 'image' },
  { label: '视频', value: 'video' },
  { label: '动图', value: 'gif' }
];

/** 跳转类型选项 */
const LINK_TYPE_OPTIONS = [
  { label: '无跳转', value: 'none' },
  { label: '外部链接', value: 'external' },
  { label: '内部页面', value: 'internal' }
];

/** 状态选项 */
const STATUS_OPTIONS = [
  { label: '显示', value: '0' },
  { label: '隐藏', value: '1' }
];

function labelOf(options: Array<{ label: string; value: any }>, value: any) {
  return options.find((o) => o.value === value)?.label ?? value;
}

/** 资源单元格：图片/动图走 antd Image 灯箱预览，视频弹窗内联播放 */
function BannerResourceCell({ value, bannerType, cover, large }: { value?: string; bannerType?: string; cover?: string; large?: boolean }) {
  const [open, setOpen] = useState(false);
  if (!value) return <span style={{ color: '#94a3b8' }}>-</span>;

  const w = large ? 260 : 56;
  const h = large ? 150 : 34;

  if (bannerType === 'video') {
    return (
      <>
        <div
          onClick={() => setOpen(true)}
          style={{ width: w, height: h, borderRadius: large ? 8 : 6, border: '1px solid #f0f0f0', position: 'relative', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: cover ? `center/cover no-repeat url(${cover})` : '#1f2937' }}
          title="点击预览视频"
        >
          <PlayCircleOutlined style={{ color: '#fff', fontSize: large ? 40 : 18, textShadow: '0 1px 4px rgba(0,0,0,.6)' }} />
        </div>
        <Modal open={open} onCancel={() => setOpen(false)} footer={null} width={720} destroyOnClose title="视频预览" centered>
          <video src={value} controls autoPlay style={{ width: '100%', maxHeight: '70vh', borderRadius: 8, background: '#000' }} />
        </Modal>
      </>
    );
  }
  // 图片 / 动图：缩略图 + 点击灯箱预览
  return (
    <Image
      src={value}
      width={w}
      height={h}
      style={{ objectFit: 'cover', borderRadius: large ? 8 : 6, border: '1px solid #f0f0f0' }}
      preview={{ mask: <PictureOutlined /> }}
      fallback="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI1NiIgaGVpZ2h0PSIzNCI+PHJlY3Qgd2lkdGg9IjU2IiBoZWlnaHQ9IjM0IiBmaWxsPSIjZjVmNWY1Ii8+PC9zdmc+"
    />
  );
}

export default function HomebannerPage() {
  const config: CrudConfig = {
    title: '首页Banner',
    permPrefix: 'aid:homebanner',
    rowKey: 'id',
    modalWidth: 760,
    viewable: true,
    api: {
      list: listHomebanner,
      get: getHomebanner,
      add: addHomebanner,
      update: updateHomebanner,
      remove: delHomebanner,
      exportUrl: '/aid/homebanner/export'
    },
    searchFields: [
      { name: 'title', label: '标题', type: 'input' },
      { name: 'bannerType', label: '资源类型', type: 'select', options: BANNER_TYPE_OPTIONS },
      { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS }
    ],
    columns: [
      { title: 'ID', dataIndex: 'id', width: 70 },
      { title: '标题', dataIndex: 'title', width: 160 },
      {
        title: '资源',
        dataIndex: 'resourceUrl',
        width: 90,
        render: (v: string, r: any) => <BannerResourceCell value={v} bannerType={r.bannerType} cover={r.coverUrl} />
      },
      {
        title: '封面',
        dataIndex: 'coverUrl',
        width: 90,
        render: (v: string) => <BannerResourceCell value={v} bannerType="image" />
      },
      { title: '类型', dataIndex: 'bannerType', width: 80, render: (v: string) => <Tag>{labelOf(BANNER_TYPE_OPTIONS, v)}</Tag> },
      { title: '简述', dataIndex: 'summary', ellipsis: true, width: 180 },
      { title: '跳转', dataIndex: 'linkType', width: 90, render: (v: string) => labelOf(LINK_TYPE_OPTIONS, v) },
      { title: '排序', dataIndex: 'sortOrder', width: 70 },
      {
        title: '状态', dataIndex: 'status', width: 80,
        render: (v: string) => <Tag color={v === '0' ? 'green' : 'default'}>{labelOf(STATUS_OPTIONS, v)}</Tag>
      },
      { title: '生效开始', dataIndex: 'startTime', dateFormat: true, width: 160 },
      { title: '生效结束', dataIndex: 'endTime', dateFormat: true, width: 160 },
      { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
    ],
    formFields: [
      { name: 'title', label: '标题', required: true, maxLength: 128 },
      { name: 'bannerType', label: '资源类型', type: 'select', options: BANNER_TYPE_OPTIONS, required: true, initialValue: 'image' },
      {
        name: 'resourceUrl', label: '资源（图片/视频/动图）', span: 24, required: true, type: 'custom',
        render: () => (
          <ImageUpload maxCount={1} maxSize={20} accept="image/*,video/*" />
        ),
        viewRender: (v: any, row: any) => <BannerResourceCell value={v} bannerType={row?.bannerType} cover={row?.coverUrl} large />
      },
      {
        name: 'coverUrl', label: '封面（视频/动图海报）', span: 24, type: 'custom',
        render: () => (
          <ImageUpload maxCount={1} maxSize={20} accept="image/*" />
        ),
        viewRender: (v: any) => <BannerResourceCell value={v} bannerType="image" large />
      },
      { name: 'summary', label: '简述', type: 'textarea', span: 24, maxLength: 512 },
      { name: 'linkType', label: '跳转类型', type: 'select', options: LINK_TYPE_OPTIONS, initialValue: 'none' },
      { name: 'linkUrl', label: '跳转地址', maxLength: 1024, placeholder: '外链 http(s):// 开头；站内填 / 开头的相对路径' },
      { name: 'sortOrder', label: '排序', type: 'number', initialValue: 0 },
      { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS, initialValue: '0' },
      { name: 'startTime', label: '生效开始时间', type: 'date' },
      { name: 'endTime', label: '生效结束时间', type: 'date' },
      { name: 'remark', label: '备注', type: 'textarea', span: 24, maxLength: 500 }
    ]
  };

  return <CrudPage config={config} />;
}
