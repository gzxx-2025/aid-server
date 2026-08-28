import React from 'react';
import { Result, Button } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * 所有尚未具体实现的业务页面的通用占位页
 * - 访问动态路由后若匹配到了后端声明但未实现的 view，将 fallback 到此页面
 */
export default function Placeholder() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <div style={{ padding: 40 }}>
      <Result
        status="info"
        title="页面建设中"
        subTitle={
          <span>
            当前路径：<code>{location.pathname}</code> 对应的页面尚未迁移完成。
          </span>
        }
        extra={
          <Button type="primary" onClick={() => navigate('/index')}>
            返回首页
          </Button>
        }
      />
    </div>
  );
}
