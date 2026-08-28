import React from 'react';
import { Card } from 'antd';
import './style.less';

interface Props {
  title?: React.ReactNode;
  extra?: React.ReactNode;
  bordered?: boolean;
  children: React.ReactNode;
  className?: string;
  bodyStyle?: React.CSSProperties;
}

/**
 * 统一的页面卡片样式
 */
export default function PageCard({
  title,
  extra,
  bordered = false,
  children,
  className = '',
  bodyStyle
}: Props) {
  return (
    <Card
      title={title}
      extra={extra}
      bordered={bordered}
      className={`page-card ${className}`}
      bodyStyle={bodyStyle}
    >
      {children}
    </Card>
  );
}
