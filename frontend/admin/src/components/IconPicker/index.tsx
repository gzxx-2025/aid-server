import React, { useMemo, useState } from 'react';
import { Input, Popover } from 'antd';
import * as AntIcons from '@ant-design/icons';

import './style.less';

interface Props {
  value?: string;
  onChange?: (v: string) => void;
  placeholder?: string;
}

/**
 * 图标选择器 - 使用 @ant-design/icons 所有 Outlined 图标
 */
export default function IconPicker({ value, onChange, placeholder }: Props) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');

  const iconNames = useMemo(() => {
    return Object.keys(AntIcons).filter(
      (n) => n.endsWith('Outlined') && n !== 'default' && !n.startsWith('$')
    );
  }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return iconNames;
    return iconNames.filter((n) => n.toLowerCase().includes(q));
  }, [iconNames, search]);

  const renderIcon = (name: string) => {
    const IconComp = (AntIcons as any)[name];
    return IconComp ? <IconComp /> : null;
  };

  const content = (
    <div style={{ width: 420 }}>
      <Input.Search
        placeholder="搜索图标"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        style={{ marginBottom: 8 }}
      />
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(10, 1fr)',
          gap: 4,
          maxHeight: 300,
          overflowY: 'auto'
        }}
      >
        {filtered.slice(0, 300).map((name) => (
          <div
            key={name}
            title={name}
            onClick={() => {
              onChange?.(name);
              setOpen(false);
            }}
            style={{
              padding: 6,
              textAlign: 'center',
              cursor: 'pointer',
              borderRadius: 4,
              background: value === name ? 'rgba(37, 99, 235, 0.12)' : undefined,
              fontSize: 16,
              border: '1px solid transparent',
              transition: 'all 0.15s'
            }}
            onMouseEnter={(e) => (e.currentTarget.style.borderColor = '#3b82f6')}
            onMouseLeave={(e) => (e.currentTarget.style.borderColor = 'transparent')}
          >
            {renderIcon(name)}
          </div>
        ))}
      </div>
    </div>
  );

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomLeft"
    >
      <Input
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        placeholder={placeholder || '点击选择图标'}
        prefix={value ? renderIcon(value) : undefined}
        readOnly
      />
    </Popover>
  );
}
