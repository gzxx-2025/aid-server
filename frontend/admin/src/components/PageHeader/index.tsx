import React from 'react';

interface Props {
  /** 页面标题（可带图标） */
  title: React.ReactNode;
  /** 标题下方的辅助描述 */
  desc?: React.ReactNode;
  /** 右侧操作区 */
  extra?: React.ReactNode;
  style?: React.CSSProperties;
}

/**
 * 统一的页面标题区：左侧标题+描述，右侧操作按钮组，
 * 替代各页面散落的 flex 内联布局
 */
export default function PageHeader({ title, desc, extra, style }: Props) {
  return (
    <div className="page-header" style={style}>
      <div>
        <h3 className="page-header__title">{title}</h3>
        {desc && <div className="page-header__desc">{desc}</div>}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  );
}
