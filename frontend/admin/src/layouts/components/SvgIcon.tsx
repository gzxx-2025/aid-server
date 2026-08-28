import React from 'react';

interface Props {
  icon?: string;
  className?: string;
  style?: React.CSSProperties;
  size?: number;
}

/**
 * 轻量 SVG 图标组件：
 *  - 支持 dashboard / user 等 svg-sprite 图标（由 vite-plugin-svg-icons 注入 <symbol>）
 *  - 支持 # 开头的自定义 id
 *  - 支持 http(s) 外链（用 <img> 呈现）
 */
export default function SvgIcon({ icon, className = '', style, size = 16 }: Props) {
  if (!icon) return null;
  if (icon.startsWith('http://') || icon.startsWith('https://')) {
    return <img src={icon} alt="" className={className} style={{ width: size, height: size, ...style }} />;
  }
  const sym = icon.startsWith('#') ? icon : `#icon-${icon}`;
  return (
    <svg
      className={`svg-icon ${className}`}
      aria-hidden
      width={size}
      height={size}
      style={{ verticalAlign: '-0.15em', fill: 'currentColor', overflow: 'hidden', ...style }}
    >
      <use xlinkHref={sym} />
    </svg>
  );
}
