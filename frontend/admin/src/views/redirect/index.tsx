import React, { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * /redirect/* 让 tab 页刷新：导航到临时 url 再回跳，触发组件重建
 */
export default function RedirectPage() {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const path = location.pathname.replace(/^\/redirect/, '') || '/index';
    const search = location.search;
    navigate(path + search, { replace: true });
  }, [location, navigate]);

  return null;
}
