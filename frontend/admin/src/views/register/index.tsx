import React from 'react';
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import './style.less';

export default function RegisterPage() {
  const navigate = useNavigate();
  const siteName = useAdminBrandStore((s) => s.resolvedSiteName);
  return (
    <div className="register-page">
      <Result
        title={`${siteName} 注册功能暂未开放`}
        subTitle="请联系管理员开通账号"
        extra={
          <Button type="primary" onClick={() => navigate('/login')}>
            返回登录
          </Button>
        }
      />
    </div>
  );
}
