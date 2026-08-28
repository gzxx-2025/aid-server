import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import '@/assets/styles/index.less';
import 'virtual:svg-icons-register';

// 初始化 user store，注册 401 登出钩子
import '@/store/useUserStore';

const rootEl = document.getElementById('root')!;
// 清掉 index.html 的 preloader
rootEl.innerHTML = '';

ReactDOM.createRoot(rootEl).render(<App />);
