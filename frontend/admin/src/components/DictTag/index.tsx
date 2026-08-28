import React from 'react';
import { Tag } from 'antd';
import type { DictOption } from '@/store/useDictStore';

interface Props {
  options: DictOption[];
  value?: string | number | Array<string | number>;
  separator?: string;
}

const colorMap: Record<string, string> = {
  primary: 'blue',
  success: 'green',
  warning: 'orange',
  danger: 'red',
  info: 'default'
};

/**
 * 字典标签：根据字典 options 渲染带颜色的 Tag
 */
export default function DictTag({ options, value, separator = ',' }: Props) {
  if (value === undefined || value === null) return null;
  const values = Array.isArray(value) ? value : String(value).split(separator);

  return (
    <>
      {values.map((v) => {
        const hit = options?.find((opt) => String(opt.value) === String(v));
        if (!hit) return <Tag key={String(v)}>{String(v)}</Tag>;
        const color = colorMap[hit.elTagType || ''] || (hit.elTagClass ? undefined : 'blue');
        return (
          <Tag key={String(v)} color={color} bordered={false}>
            {hit.label}
          </Tag>
        );
      })}
    </>
  );
}
