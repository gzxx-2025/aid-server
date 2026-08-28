import React from 'react';
import { AutoComplete, Col, DatePicker, Form, Input, InputNumber, Row, Select, Switch } from 'antd';
import type { FieldConfig } from './types';
import { useDict } from '@/hooks/useDict';
import ImageUpload from '@/components/ImageUpload';
import RichTextEditor from '@/components/RichTextEditor';
import type { FormInstance } from 'antd';

interface Props {
  fields: FieldConfig[];
  form: FormInstance;
  isEdit: boolean;
}

export default function FormRenderer({ fields, form, isEdit }: Props) {
  const dictTypes = Array.from(
    new Set(fields.filter((f) => f.type === 'dict' && f.dictType).map((f) => f.dictType!))
  );
  const dicts = useDict(...dictTypes);

  const renderField = (field: FieldConfig) => {
    const placeholder = field.placeholder || `请输入${field.label}`;
    const selectPlaceholder = field.placeholder || `请选择${field.label}`;

    switch (field.type) {
      case 'textarea': {
        // 根据 maxLength 智能调整高度：长文本（提示词/剧本）给更大的高度
        const long = (field.maxLength ?? 0) > 500;
        const autoSize = long
          ? { minRows: 8, maxRows: 18 }
          : { minRows: 3, maxRows: 6 };
        return (
          <Input.TextArea
            autoSize={autoSize}
            maxLength={field.maxLength}
            placeholder={placeholder}
            disabled={field.disabled}
            showCount={!!field.maxLength}
            style={long ? { fontFamily: 'Consolas, Menlo, Monaco, "Courier New", monospace', lineHeight: 1.7 } : undefined}
          />
        );
      }
      case 'richtext':
        return <RichTextEditor placeholder={placeholder} height={320} />;
      case 'image':
        return <ImageUpload maxCount={1} accept="image/*" />;
      case 'combobox':
        // 可输入可选择：用于「分类」等既要灵活自定义、又能复用历史值的场景
        return (
          <AutoComplete
            options={field.options}
            placeholder={placeholder}
            allowClear
            filterOption={(input, option) =>
              String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
            }
            disabled={field.disabled}
          />
        );
      case 'number':
        return (
          <InputNumber
            style={{ width: '100%' }}
            placeholder={placeholder}
            disabled={field.disabled}
          />
        );
      case 'select':
        return (
          <Select
            options={field.options}
            placeholder={selectPlaceholder}
            allowClear
            showSearch
            optionFilterProp="label"
            disabled={field.disabled}
          />
        );
      case 'dict': {
        const options = (dicts[field.dictType!] || []).map((d) => ({
          label: d.label,
          value: d.value
        }));
        return (
          <Select
            options={options}
            placeholder={selectPlaceholder}
            allowClear
            disabled={field.disabled}
          />
        );
      }
      case 'date':
        return (
          <DatePicker
            style={{ width: '100%' }}
            showTime
            placeholder={selectPlaceholder}
            disabled={field.disabled}
          />
        );
      case 'switch':
        return <Switch disabled={field.disabled} />;
      case 'custom':
        return field.render ? (field.render(form) as any) : null;
      case 'input':
      default:
        return (
          <Input
            placeholder={placeholder}
            maxLength={field.maxLength}
            allowClear
            disabled={field.disabled}
          />
        );
    }
  };

  const visible = fields.filter((f) => {
    if (isEdit && f.onlyAdd) return false;
    if (!isEdit && f.onlyEdit) return false;
    return true;
  });

  return (
    <Row gutter={16}>
      {visible.map((field) => {
        const rules = field.rules || [];
        if (field.required && !rules.some((r) => r.required)) {
          rules.unshift({ required: true, message: `${field.label}不能为空` });
        }
        const valuePropName = field.type === 'switch' ? 'checked' : 'value';
        return (
          <Col span={field.span ?? (field.type === 'textarea' || field.type === 'richtext' || field.type === 'custom' ? 24 : 12)} key={field.name}>
            <Form.Item
              name={field.name}
              label={field.label}
              rules={rules}
              initialValue={field.initialValue}
              valuePropName={valuePropName}
            >
              {renderField(field)}
            </Form.Item>
          </Col>
        );
      })}
    </Row>
  );
}
