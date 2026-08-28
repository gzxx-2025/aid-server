import React from 'react';
import { Button, Space, Tooltip } from 'antd';
import {
  SearchOutlined,
  ReloadOutlined,
  SettingOutlined
} from '@ant-design/icons';

interface Props {
  showSearch?: boolean;
  searchVisible?: boolean;
  onToggleSearch?: () => void;
  onRefresh?: () => void;
  extra?: React.ReactNode;
}

/**
 * 通用右侧工具条（搜索/刷新等）
 */
export default function RightToolbar({
  showSearch = true,
  searchVisible = true,
  onToggleSearch,
  onRefresh,
  extra
}: Props) {
  return (
    <Space size={4}>
      {extra}
      {showSearch && onToggleSearch && (
        <Tooltip title={searchVisible ? '隐藏搜索' : '显示搜索'}>
          <Button
            type="text"
            icon={<SearchOutlined />}
            onClick={onToggleSearch}
          />
        </Tooltip>
      )}
      {onRefresh && (
        <Tooltip title="刷新">
          <Button type="text" icon={<ReloadOutlined />} onClick={onRefresh} />
        </Tooltip>
      )}
    </Space>
  );
}
