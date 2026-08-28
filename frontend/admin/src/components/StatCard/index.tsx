import React from 'react';

interface Props {
  /** 指标名 */
  label: React.ReactNode;
  /** 指标值 */
  value: React.ReactNode;
  /** 图标 */
  icon?: React.ReactNode;
  /** 语义色（图标与数值强调色），默认品牌蓝 */
  color?: string;
  /** 点击事件（如跳转） */
  onClick?: () => void;
  style?: React.CSSProperties;
}

/**
 * 统一的统计卡片：图标色块 + 指标名 + 数值，
 * 替代各页面自定义的彩色渐变 Statistic 卡片
 */
export default function StatCard({ label, value, icon, color = '#2563eb', onClick, style }: Props) {
  return (
    <div
      className="stat-card"
      style={{ cursor: onClick ? 'pointer' : undefined, ...style }}
      onClick={onClick}
    >
      {icon && (
        <span
          className="stat-card__icon"
          style={{ color, background: `${color}14` /* 8% 透明度同色底 */ }}
        >
          {icon}
        </span>
      )}
      <div className="stat-card__meta">
        <div className="stat-card__label">{label}</div>
        <div className="stat-card__value" style={{ color }}>{value}</div>
      </div>
    </div>
  );
}
