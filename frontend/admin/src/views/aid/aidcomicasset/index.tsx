import React, { useEffect, useMemo, useState } from 'react';
import { Form, Select, Switch, Tag } from 'antd';
import type { FormInstance } from 'antd';
import CrudPage, { type CrudConfig, type EmbeddedScope, scopedConfig } from '@/components/CrudPage';
import {
  listAidcomicasset, getAidcomicasset, addAidcomicasset, updateAidcomicasset, delAidcomicasset,
  listStyleCategories
} from '@/api/aid/aidcomicasset';
import { ASSET_TYPE_OPTIONS, getLabelByValue } from '@/utils/enums';

const STYLE_CATEGORY_OPTIONS = [
  { value: 'comic_drama', label: '漫剧' },
  { value: 'live_action', label: '真人剧' },
  { value: 'three_d', label: '3D' },
  { value: 'chinese', label: '国风' },
  { value: 'two_d', label: '2D' },
  { value: 'chibi', label: 'Q版' },
  { value: 'game', label: '游戏' },
  { value: 'japanese', label: '日漫' },
  { value: 'western', label: '欧美' },
  { value: 'korean', label: '韩流' }
];

type SelectOption = { value: string; label: string };

function StyleCategorySelect({ form, options, value, onChange }: {
  form: FormInstance;
  options: SelectOption[];
  value?: string[];
  onChange?: (value: string[]) => void;
}) {
  const selectedType = Form.useWatch('assetType', form);
  useEffect(() => {
    const missing = selectedType === 'style' && (!value || value.length === 0);
    form.setFields([{ name: 'categoryCodes', errors: missing ? ['风格分类不能为空'] : [] }]);
  }, [form, selectedType, value]);
  return (
    <Select
      mode="multiple"
      value={value}
      onChange={onChange}
      options={options}
      placeholder={selectedType === 'style' ? '请选择一个或多个分类' : '仅风格素材可配置'}
      disabled={selectedType !== 'style'}
      allowClear
      optionFilterProp="label"
    />
  );
}

function RecommendedSwitch({ form, value, onChange }: {
  form: FormInstance;
  value?: boolean;
  onChange?: (value: boolean) => void;
}) {
  const selectedType = Form.useWatch('assetType', form);
  return (
    <Switch
      checked={Boolean(value)}
      onChange={onChange}
      disabled={selectedType !== 'style'}
      checkedChildren="推荐"
      unCheckedChildren="普通"
    />
  );
}

function createConfig(styleCategoryOptions: SelectOption[]): CrudConfig {
  const searchCategoryOptions = [{ value: 'all', label: '全部' }, ...styleCategoryOptions];
  return {
  title: '项目提取资产',
  permPrefix: 'aid:aidcomicasset',
  rowKey: 'id',
  viewable: true,
  modalWidth: 720,
  defaultQuery: { assetType: 'style' },
  api: {
    list: listAidcomicasset,
    get: getAidcomicasset,
    add: addAidcomicasset,
    update: updateAidcomicasset,
    remove: delAidcomicasset,
    exportUrl: '/aid/aidcomicasset/export'
  },
  searchFields: [
    { name: 'assetName', label: '资产名称', type: 'input' },
    { name: 'assetType', label: '资产类型', type: 'select', options: ASSET_TYPE_OPTIONS, placeholder: '默认仅看风格' },
    { name: 'categoryCode', label: '风格分类', type: 'select', options: searchCategoryOptions },
    {
      name: 'isRecommended',
      label: '推荐状态',
      type: 'select',
      options: [{ value: true, label: '推荐' }, { value: false, label: '普通' }]
    }
  ],
  columns: [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '资产类型', dataIndex: 'assetType', width: 110, render: (v: string) => <Tag>{getLabelByValue(ASSET_TYPE_OPTIONS, v)}</Tag> },
    { title: '名称', dataIndex: 'assetName', width: 160, ellipsis: true },
    {
      title: '风格分类',
      dataIndex: 'categories',
      width: 240,
      render: (categories: Array<{ code: string; label: string }> = []) =>
        categories.length ? categories.map((item) => <Tag key={item.code} color="blue">{item.label}</Tag>) : '-'
    },
    {
      title: '推荐',
      dataIndex: 'isRecommended',
      width: 80,
      render: (value: boolean) => value ? <Tag color="gold">推荐</Tag> : <Tag>普通</Tag>
    },
    { title: '排序号', dataIndex: 'sortOrder', width: 90 },
    { title: '主图', dataIndex: 'imageUrl', width: 80, render: (v: string) => v ? <img src={v} alt="" style={{ width: 40, height: 40, objectFit: 'cover', borderRadius: 4 }} /> : '-' },
    { title: '创建时间', dataIndex: 'createTime', dateFormat: true, width: 160 }
  ],
  formFields: [
    { name: 'assetType', label: '资产类型', type: 'select', options: ASSET_TYPE_OPTIONS, required: true },
    { name: 'assetName', label: '资产名称', required: true, maxLength: 100 },
    {
      name: 'categoryCodes',
      label: '风格分类（可多选）',
      type: 'custom',
      span: 24,
      render: (form) => <StyleCategorySelect form={form} options={styleCategoryOptions} />,
      viewRender: (_value: string[], row: any) =>
        row.categories?.length
          ? row.categories.map((item: { code: string; label: string }) => <Tag key={item.code} color="blue">{item.label}</Tag>)
          : '-'
    },
    {
      name: 'isRecommended',
      label: '推荐风格',
      type: 'custom',
      initialValue: false,
      render: (form) => <RecommendedSwitch form={form} />,
      viewRender: (value: boolean) => value ? <Tag color="gold">推荐</Tag> : <Tag>普通</Tag>
    },
    {
      name: 'sortOrder',
      label: '排序号',
      type: 'number',
      initialValue: 1000,
      required: true,
      rules: [{ type: 'integer', min: 0, max: 999999, message: '排序号须为0至999999的整数' }]
    },
    { name: 'personalityDesc', label: '性格/特征描述', type: 'textarea', span: 24 },
    { name: 'promptText', label: '提示词', type: 'textarea', span: 24 },
    { name: 'imageUrl', label: '主图', type: 'image', span: 24 },
    { name: 'remark', label: '备注', type: 'textarea', span: 24 }
  ],
  afterFetch: (data) => ({
    ...data,
    categoryCodes: data.categoryCodes || data.categories?.map((item: { code: string }) => item.code) || [],
    isRecommended: Boolean(data.isRecommended),
    sortOrder: data.sortOrder ?? 1000
  }),
  beforeSubmit: (data) => data.assetType === 'style'
    ? data
    : { ...data, categoryCodes: [], isRecommended: false }
  };
}

export default function Page({ scope }: { scope?: EmbeddedScope } = {}) {
  const [styleCategoryOptions, setStyleCategoryOptions] = useState<SelectOption[]>(STYLE_CATEGORY_OPTIONS);

  useEffect(() => {
    listStyleCategories()
      .then((res: any) => {
        const items = res.data ?? res;
        if (Array.isArray(items) && items.length > 0) {
          setStyleCategoryOptions(items.map((item) => ({ value: item.code, label: item.label })));
        }
      })
      .catch(() => {
        // 后端字典暂不可用时保留同源固定代码兜底，页面仍可维护。
      });
  }, []);

  const config = useMemo(() => createConfig(styleCategoryOptions), [styleCategoryOptions]);
  return <CrudPage config={scopedConfig(config, scope)} />;
}
