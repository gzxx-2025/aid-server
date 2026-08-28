import React from 'react';
import { Button, DatePicker, Form, Input, InputNumber, Select, Space } from 'antd';
import { SearchOutlined, RedoOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';

import { useDict } from '@/hooks/useDict';
import type { SearchConfig } from './types';

interface Props {
  fields: SearchConfig[];
  loading?: boolean;
  onSearch: (values: Record<string, any>) => void;
  onReset: () => void;
}

export default function SearchBar({ fields, loading, onSearch, onReset }: Props) {
  const [form] = Form.useForm();
  const dictTypes = Array.from(
    new Set(fields.filter((f) => f.type === 'dict' && f.dictType).map((f) => f.dictType!))
  );
  const dicts = useDict(...dictTypes);

  const handleSubmit = (values: Record<string, any>) => {
    const params: Record<string, any> = {};
    fields.forEach((f) => {
      const val = values[f.name];
      if (val === undefined || val === null || val === '') return;
      if (f.type === 'dateRange' && Array.isArray(val) && val.length === 2) {
        const propName = f.rangePropName || '';
        const begin = (val[0] as Dayjs)?.format('YYYY-MM-DD 00:00:00');
        const end = (val[1] as Dayjs)?.format('YYYY-MM-DD 23:59:59');
        if (propName) {
          params[`begin${propName}`] = begin;
          params[`end${propName}`] = end;
        } else {
          params.params = { ...(params.params || {}), beginTime: begin, endTime: end };
        }
      } else {
        params[f.name] = val;
      }
    });
    onSearch(params);
  };

  const handleReset = () => {
    form.resetFields();
    onReset();
  };

  const renderField = (f: SearchConfig) => {
    switch (f.type) {
      case 'select':
        return (
          <Select
            options={f.options}
            placeholder={f.placeholder || `请选择${f.label}`}
            allowClear
            style={{ width: 180 }}
          />
        );
      case 'dict': {
        const options = (dicts[f.dictType!] || []).map((d) => ({ label: d.label, value: d.value }));
        return (
          <Select
            options={options}
            placeholder={f.placeholder || `请选择${f.label}`}
            allowClear
            style={{ width: 180 }}
          />
        );
      }
      case 'date':
        return <DatePicker style={{ width: 180 }} />;
      case 'dateRange':
        return <DatePicker.RangePicker />;
      case 'number':
        return <InputNumber placeholder={f.placeholder || `请输入${f.label}`} style={{ width: 180 }} />;
      case 'input':
      default:
        return (
          <Input
            placeholder={f.placeholder || `请输入${f.label}`}
            allowClear
            style={{ width: 180 }}
          />
        );
    }
  };

  return (
    <Form form={form} layout="inline" onFinish={handleSubmit} style={{ rowGap: 8 }}>
      {fields.map((f) => (
        <Form.Item key={f.name} name={f.name} label={f.label}>
          {renderField(f)}
        </Form.Item>
      ))}
      <Form.Item>
        <Space>
          <Button type="primary" icon={<SearchOutlined />} htmlType="submit" loading={loading}>
            搜索
          </Button>
          <Button icon={<RedoOutlined />} onClick={handleReset}>
            重置
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
}
