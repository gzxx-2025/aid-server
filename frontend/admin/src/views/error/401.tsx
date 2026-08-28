import React from 'react';
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';

export default function UnauthorizedPage() {
  const navigate = useNavigate();
  return (
    <div style={{ padding: 60 }}>
      <Result
        status="403"
        title="401"
        subTitle="抱歉，您无权访问该页面。"
        extra={
          <Button type="primary" onClick={() => navigate('/login', { replace: true })}>
            重新登录
          </Button>
        }
      />
    </div>
  );
}
