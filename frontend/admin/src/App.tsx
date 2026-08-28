import React, { useEffect } from 'react';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

import AppRouter from '@/router';
import { useAppTheme } from '@/hooks/useTheme';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';

dayjs.locale('zh-cn');

export default function App() {
  const theme = useAppTheme();
  const loadBrand = useAdminBrandStore((s) => s.load);

  // 启动时拉取品牌图配置，尽早替换页签图标
  useEffect(() => {
    loadBrand();
  }, [loadBrand]);

  return (
    <ConfigProvider locale={zhCN} theme={theme as any} componentSize="middle">
      <AntApp>
        <AppRouter />
      </AntApp>
    </ConfigProvider>
  );
}
