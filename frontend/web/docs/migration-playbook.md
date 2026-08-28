# Nuxt(aid-pc) → Next(aid-pc-reset) 迁移手册

> 本文是本仓库所有 Vue→React 迁移工作的唯一转换规范。目标：业务行为与视觉 1:1，仅底层技术栈变更。
> 上位规范：`D:\aid_pro\aid-pc\rules\frontend-engineering-standards-next-react(1).md`（体量红线、复用规则以它为准）。

## 1. 目录与路径映射

| 原（aid-pc） | 新（aid-pc-reset） | 说明 |
|---|---|---|
| `pages/*.vue` | `app/**/page.tsx` | 路由组：`(home)` = home-new 壳；`login`/`mobile`/`case/[id]` 无壳 |
| `layouts/*.vue` | `app/(组)/layout.tsx` + `components/layout/*` | html class 由 `HtmlShellClass` 组件挂载 |
| `composables/useXxx.ts` | `hooks/useXxx.ts` | 别名 `~/composables/*` 已重定向到 `hooks/*` |
| `components/**/*.vue` | `components/**/*.tsx` | 同目录结构，PascalCase.tsx |
| `stores/*.ts`（Pinia） | `stores/*.ts`（Zustand） | persist key 与原 localStorage key 一致 |
| `utils/`、`types/`、`config/` | 同路径（已整体平移） | 引用写 `~/utils/...` 保持不动 |
| `assets/` | `assets/`（已平移） | 全局 CSS 已按原顺序在 `app/layout.tsx` 引入 |
| `static/` | `public/` | 绝对路径 `/tac/...`、`/media/...` 不变 |

别名：`@/*` 与 `~/*` 均指向项目根，`~/composables/*` → `./hooks/*`。

## 2. 语法差异硬规则

| Vue / Nuxt | React / Next |
|---|---|
| `import.meta.client` | `(typeof window !== 'undefined')` |
| `import.meta.env.DEV` | `(process.env.NODE_ENV === 'development')` |
| `ref(x)` / `reactive` | `useState` / `useRef`（不触发渲染的用 useRef） |
| `computed(...)` | 渲染期直接计算；昂贵计算才 `useMemo` |
| `watch(src, cb)` | `useEffect(() => {...}, [src])`；需要旧值时自存 ref |
| `onMounted`/`onBeforeUnmount` | `useEffect(() => { ...; return cleanup }, [])` |
| `nextTick(fn)` | `requestAnimationFrame` 或 `setTimeout(fn, 0)`（DOM 量取用前者） |
| `defineProps`/`defineEmits` | props 接口；`emit('update:x', v)` → `onXChange(v)` 回调 prop |
| `v-model:open="x"` | `open={x} onOpenChange/onCancel` 受控 |
| `defineExpose({...})` | `forwardRef` + `useImperativeHandle` |
| `provide/inject` | React Context（薄封装，命名 XxxContext） |
| `useRoute().path` | `usePathname()` |
| `useRoute().query.x` | `useSearchParams().get('x')` |
| `useRouter().push/replace` | `next/navigation` 的 `useRouter().push/replace` |
| `useHead({ htmlAttrs: { class } })` | `<HtmlShellClass classes="..." />`（components/app） |
| `<Transition>` | CSS 过渡（原过渡类已在全局 css）或 framer-motion 不引入，用简单 CSS |
| `<Teleport to="body">` | `createPortal(node, document.body)` |

## 3. 状态（Pinia → Zustand）

- 组件渲染读取：`const x = useXxxStore((s) => s.x)`（必须用 selector，防全量重渲）。
- 事件回调 / 非组件代码：`useXxxStore.getState().action()`。
- 原 `store.$patch({...})` → store 内 `set({...})`；嵌套字段必须不可变更新（整分支替换）。
- 已有 store：`user`、`app`、`creation`（分桶模型，见 stores/creation）。
- 派生 getter：优先 store 内已导出的 selector；没有就在组件里算。

## 4. Ant Design Vue → antd(React)

- `a-modal` → `Modal`：`:open`→`open`，`@cancel`→`onCancel`，`wrap-class-name`→`wrapClassName`（**必须保留原 class，全局主题 CSS 以它为契约**，如 `create-flow-modal`）。
- `a-button type="primary"` → `<Button type="primary">`；`a-input`→`Input`；`a-select`→`Select`（options 数组化）；`a-tooltip`→`Tooltip`；`a-spin`→`Spin`；`a-progress`→`Progress`。
- `message` / `Modal.confirm` 同名 API，直接用 `antd` 导出；确认弹窗必须带原 `wrapClassName`（`create-flow-modal home-confirm-wrap` 等）。
- 图标：`@ant-design/icons-vue` → `@ant-design/icons` 同名组件。
- **禁止**引入 ConfigProvider 主题 token 重配色——暗色视觉全部来自已平移的覆盖 CSS（`create-steps-ant-overrides.css` 等），DOM class 结构必须让这些选择器命中。antd v6 类名前缀仍为 `ant-`，如遇差异在共享覆盖层修，不要写组件内 `!important`。

## 5. 样式转换（视觉 1:1 优先）

优先级：**保留原语义 class（全局 CSS 契约）> Tailwind 工具类 > 共享 css**。

1. 元素上的**语义 class 一律保留**（`.home-new-sidebar`、`.create-workflow` 等），全局 CSS 靠它们生效。
2. 原组件 `<style scoped>` 的样式：布局/间距/字号/颜色用 Tailwind 工具类内联转换；颜色引用 CSS 变量写法 `bg-[var(--home-surface)]`、`text-[var(--home-muted)]`。
3. 复杂选择器、伪元素、动画、antd 深覆盖：放同名共享 css（`assets/css/` 或组件旁 `xxx.css` 全局引入），选择器原样保留。
4. px 值照抄（postcss-pxtorem 会按原规则转 rem；`12Px` 大写 P 表示物理像素，保留原样）。
5. 禁止改任何颜色值 / 圆角 / 间距，禁止"顺手美化"。

## 6. 请求与 SSE

- 业务请求：一律 `~/utils/businessApi`（已平移），禁止组件内裸 axios/fetch。
- 列表防重：businessApi 内置 inflight+burst 缓存，调用方无需再包防抖；新增高频入口先查 `runListDedupe` 是否已覆盖。
- SSE：`~/composables/useTaskStream` 的 `createTaskStream`（fetch+ReadableStream），跟随统一走 `waitUserTaskSseTerminal`（单 owner 槽位，见 `~/composables/useTaskSseFollow`）。**禁止**组件自建 EventSource / 轮询。
- 切作品/集/步骤：挂起用 `suspendAllTaskSseFollows()`（只断浏览器连接，不取消服务端任务，不清持久化快照）。
- Loading 恢复铁律：有 taskId 必须先跟 SSE 再亮 loading 状态（防止只亮不跟）；空列表禁止覆盖清空 generating 态；所有任务回调必须过 scope guard（projectId+episodeId 匹配才写当前 UI）。

## 7. 组件转换流程

1. 读原 `.vue` 全文（template + script + style）。
2. `'use client'` 起手（本项目页面均为交互型 SPA 页）。
3. 结构顺序：import → types/props → store/hooks → state/derived → effects（成对清理）→ 事件方法 → JSX。
4. template → JSX：`v-if`→条件渲染；`v-for`→map（key 保持原 key 语义）；`v-show`→style.display；class 绑定→模板串或 clsx 手写。
5. 校验：`npx tsc --noEmit` 不新增错误；文件 ≤800 行，超了拆子组件/hook。
6. **编码**：UTF-8 无 BOM；用 Read/Write/StrReplace 工具写文件，禁止 PowerShell 管道改文件。

## 8. 禁止事项

- 禁止改业务时序（请求参数、成功/失败/取消分支、toast 文案）。
- 禁止私自新增/修改视觉样式；禁止换文案。
- 禁止照搬 Vue 写法硬套（如用 useEffect 模拟 watch 深比较——需要时用 JSON 指纹或明确依赖）。
- 禁止新建平行的任务/SSE/防重机制。
- 禁止无意义 `any`；类型先查 `~/types/business-api`。
