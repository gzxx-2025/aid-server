# AID PC Web

`aid-pc-reset` 是 AID PC 端的 Next.js 重构项目。业务接口、交互流程和视觉表现与原 `aid-pc` 保持一致，运行栈为 Next.js App Router、React、Ant Design、TypeScript、Tailwind CSS 和 Zustand。

## 环境要求

- Node.js 20.9 或更高版本
- npm 10 或更高版本
- 可访问 AID 后端接口

## 本地开发

复制环境变量模板，并按本地后端地址修改 `NEXT_PROXY_TARGET`：

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

浏览器访问 `http://localhost:3000`。开发环境的业务请求统一通过 `/url` 同源代理转发，默认不启用第四步及后续生成流程的本地模拟。

## 生产静态发布

```powershell
npm run generate
```

静态产物位于 `dist/public/`，包含发布链路要求的 `index.html` 与 `200.html`，可直接交给现有 Nginx 静态站点托管。生产接口继续使用同源 `/aid/**`，由部署侧 Nginx 转发到后端。

## 质量检查

```powershell
npm run check
npm run lint
npm run build
```

- `check`：TypeScript 严格检查和 Vitest 单元测试
- `lint`：Next.js Core Web Vitals 与 TypeScript 规则检查
- `build`：生产构建和全部 App Router 页面预渲染检查
- `generate`：生成与原 Nuxt 项目发布目录一致的 `dist/public` 静态站点

## 目录约定

- `app/`：路由、布局与薄页面组装
- `components/`：按业务领域拆分的 React UI
- `hooks/`：SSE 跟随、任务恢复和复用交互流程
- `utils/`：API、纯函数和业务映射
- `stores/`：Zustand 跨路由业务状态
- `types/`：共享 TypeScript 类型
- `assets/`、`public/`：主题样式和静态资源

SSE 与后台任务必须通过统一 task stream/follow/restore 体系接入；页面和弹窗不得各自创建平行轮询或独立任务状态机。
