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

### 站点头部与 SEO

`components/app/PublicSiteHead.tsx` 在根布局中统一声明标题、SEO 和品牌图标；`utils/seoHead.ts` 合成路由规则与后台公开配置，并按路径合并 SEO 查询。静态预渲染保留默认标题、描述与索引规则，浏览器挂载后应用站点配置；未登记路径的 SEO 查询返回 404 时使用页面默认值。页面不得再直接增删 `document.head` 节点或重复声明同一组 Metadata，以免破坏 React 路由卸载。站点验证与 viewport 仍由 Next Metadata 管理。

上述处理为纯前端逻辑，无新增后端代码；复用 `/auth/public-config` 和 `/seo/public/meta`，不改变接口契约。

### 流程画布

从「我的作品」左上角的「流程画布」选择作品，或从创作流程顶部进入。电影使用作品级流程，电视剧集需选择具体分集。

- `app/create/studio/`：作品与分集路由入口。
- `components/studio/`、`hooks/studio/`：画布、节点编辑面板及现有创作流程的交互编排。
- `utils/studio/`：业务实体到节点/连线的映射、路由和布局持久化。
- `stores/studioUi.ts`、`types/studio.ts`：画布界面状态与类型。

业务内容继续通过现有创作接口保存；节点位置与画布视图按作品、分集分别保存在当前浏览器。
