import React from 'react';
import { Button, Result } from 'antd';

/**
 * 数据监控（Druid）—— 后端提供的 /druid 独立页面，这里做一个跳转入口
 */
export default function DruidPage() {
  const url = (import.meta.env.VITE_APP_BASE_API || '/dev-api') + '/druid/index.html';
  return (
    <div style={{ padding: 40 }}>
      <Result
        status="info"
        title="数据监控（Druid）"
        subTitle="将在新窗口打开 Druid 管理页面"
        extra={
          <Button type="primary" onClick={() => window.open(url, '_blank')}>
            打开 Druid
          </Button>
        }
      />
    </div>
  );
}
