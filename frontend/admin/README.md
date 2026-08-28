# AID Admin — AI 漫剧 · AI 电影 · AI 漫画运营管理端

<p>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/React-18-61DAFB.svg" alt="React">
  <img src="https://img.shields.io/badge/TypeScript-5.5-3178C6.svg" alt="TypeScript">
  <img src="https://img.shields.io/badge/Ant%20Design-5-0170FE.svg" alt="Ant Design">
  <img src="https://img.shields.io/badge/Vite-5-646CFF.svg" alt="Vite">
</p>

<h2 align="center">🌐 官方入口</h2>
<p align="center">
  <a href="https://www.aidstudio.com.cn/"><strong>官方运营站：https://www.aidstudio.com.cn/</strong></a>
  &nbsp;&nbsp;·&nbsp;&nbsp;
  <a href="https://gzxxaitdb.feishu.cn/docx/LZ5zdesEgo1z4Mxc7OWc7zTHnJc"><strong>📘 部署与使用教程</strong></a><br>
  在线体验 AID，了解 AI 漫剧、AI 电影、AI 漫画三大创作方向及官方运营服务。
</p>

AID 开源 AI 漫剧、AI 电影、AI 漫画创作平台的运营管理端，负责模型与供应商、智能体与提示词、用户与订单、内容与存储、系统配置以及在线升级等平台级能力。

## AID 平台介绍

AID 是一套面向 **AI 漫剧、AI 电影、AI 漫画** 的开源内容创作平台。它把创作过程拆成连续的业务链路：剧本创作、分集管理、角色/道具/场景资产、分镜拆解、图片生成、图生视频、配音合成和成片管理。创作者不需要在多个模型平台之间来回切换，也不需要手动维护大量素材与生成记录，平台会以项目为中心组织全部内容资产。

整个平台由三部分组成：`aid-server` 提供 Java 服务端、数据库、任务调度、计费和升级能力；`aid-web` 提供用户创作工作台；`aid-admin` 则是运营管理端，也就是本仓库。三端配合后，可以形成一套可私有化部署、可接入多模型、可运营计费、可在线升级的 AI 内容创作系统。

对运营人员来说，AID Admin 是平台的控制台。你可以维护 AI 供应商密钥、模型能力、价格 SKU、并发限制、用户资料、充值订单、内容审核和平台运营内容；也可以管理部署配置、域名与 HTTPS、官方资产、版本更新和回退。敏感字段按权限和脱敏规则展示，配置修改通过后端与独立升级器受控落盘。

## 三大核心创作方向

AID 将不同内容形态分别组织为清晰的创作方向。运营人员可以针对每个方向配置模型能力、生成价格、并发限制、内容审核和任务监控策略。

### AI 漫剧

面向连载化、分集化和角色驱动的动态内容生产。后台可以管理分集与分镜内容、图生视频、首尾帧视频、多镜头片段、角色配音、TTS 音频、视频时长与清晰度能力，以及相应的任务状态和计费规则。适合运营竖屏漫剧、AI 短剧、漫改短视频、剧情账号和连续更新的 IP 内容。

### AI 电影

面向更强调电影叙事、镜头语言和视觉统一的影像创作。后台可以维护电影化创作所需的文本、图片、视频与语音模型，配置分辨率、比例、时长、参考图、并发和价格能力，并统一查看多镜头生成任务与成片素材。适合运营 AI 短片、概念预告片、品牌故事片和电影化剧情内容。

### AI 漫画

面向以静态画面承载故事的连续视觉创作。后台可以管理角色、道具、场景、分镜图、参考图、内容审核、首页展示、图片模型能力和生成价格，让创作者稳定产出条漫、页漫、故事漫画、绘本、广告分镜和 IP 角色内容。

本仓库适合关注这些工作的开发者：

- 配置和运营 AI 漫剧、AI 电影、AI 漫画三类创作业务
- 管理多家 AI 厂商、多种模型、多种生成能力和计费规则
- 维护用户、订单、余额流水、内容审核和后台权限
- 通过页面管理部署配置、检查环境、上传 HTTPS 证书、执行在线升级与回退

## 交流与反馈

部署、模型配置、二次开发或创作流程接入遇到问题，可以前往服务端仓库 [aid-server](https://gitee.com/gzxx-2025/aid-server) README 顶部扫码加入交流群，也欢迎提交 Issue。

## 仓库矩阵

| 端 | 说明 | Gitee | GitHub |
|----|------|-------|--------|
| aid-server | Java 服务端（统一发布入口） | [gitee](https://gitee.com/gzxx-2025/aid-server) | [github](https://github.com/gzxx-2025/aid-server) |
| aid-admin | 运营管理端（本仓库） | [gitee](https://gitee.com/gzxx-2025/aid-admin) | [github](https://github.com/gzxx-2025/aid-admin) |
| aid-web | 用户创作端 | [gitee](https://gitee.com/gzxx-2025/aid-web) | [github](https://github.com/gzxx-2025/aid-web) |

## 官方资产包

官方资产包 `aid-official-assets_<版本>.tar.gz` 用于补齐首次部署后的平台展示与创作示例素材，包括角色、场景、道具、光影、景别/焦距、姿态、表情、特效、分镜示例、智能体与供应商图标、语音头像、MP3 试听、首页图片及演示视频。

资产包只包含 `aid_init` 初始化库实际引用的官方文件，不包含用户生成内容、账号、密钥或日志。程序不会在安装或升级时静默下载大体积资产，也不会替部署方写入 OSS/COS。管理员取得与程序匹配的资源包后，可在「项目升级配置 → 官方资源」上传并初始化到本地存储；对象存储用户也可以按包内 `files/aid/...` 原始对象键导入。获取入口与校验值由[官方运营站](https://www.aidstudio.com.cn/)和版本公告统一提供。

## 主要功能

| 能力域 | 主要内容 |
|--------|---------|
| AI 能力中心 | 模型与供应商、API 密钥、模型功能、参考图上限、分辨率/比例/时长、并发策略、价格 SKU 和健康监控 |
| 智能创作配置 | 智能体与基础提示词、官方/自定义风格、角色/道具/场景隐藏风格描述和项目快照查看 |
| 用户与财务 | 用户资料、认证、充值套餐、支付订单、余额流水、邀请关系和计费记录 |
| 内容运营 | 项目与生成记录、内容审核、首页 Banner、公告、FAQ、语音与官方素材维护 |
| 系统治理 | 组织权限、菜单、字典、定时任务、日志、文件存储、登录认证和全局业务配置 |
| 部署与升级 | 版本说明、升级器健康、实时升级终端、回退、运行配置、域名/HTTPS、证书上传、连接测试和官方资源初始化 |

## 部署与在线升级

AID Admin 不需要单独在生产服务器安装。服务端统一安装器会按同一版本标签拉取并构建三端代码，把管理端静态产物交给 Nginx 托管；Docker 和 systemd 两种部署方式共用同一套后台页面与升级协议。完整安装命令、配置项和风险说明见 [aid-server 部署指南](https://gitee.com/gzxx-2025/aid-server/blob/master/deploy/README.md)。

生产部署完成后，管理端默认使用独立端口和随机访问码，例如：

```text
http://服务器IP:8089/<12位随机访问码>
```

完整公网/内网登录地址以服务器执行 `sudo aid default` 的输出为准。数据库初始化管理员为 `admin / admin123`，首次登录后必须立即修改密码；随机访问码不等于管理员密码。

「系统管理 → 项目升级配置」集中提供以下能力：

- 查看当前版本、线上版本、双语版本说明、升级器状态和允许回退的版本
- 在页面发起升级或回退，并在首屏黑色终端中查看阶段、百分比和实时日志
- 任务执行期间阻止重复提交；服务器低于 4 核 4G 时在升级前显示高风险确认
- 读取 Docker 或 systemd 的实际配置真源，按分组编辑并测试 MySQL、Redis、RocketMQ、DNS、证书和 HTTPS
- 上传完整证书链与私钥；私钥仅进入受控配置目录，不会回显到页面
- 上传并初始化官方资产包，校验清单、文件摘要和解压路径

升级会先检查独立升级器；升级器版本落后时必须先升级升级器。正式任务会执行配置与数据库备份、三端同标签源码构建、增量 SQL、健康检查和失败恢复。命令行可使用 `sudo aid update`、`sudo aid progress`、`sudo aid rollback` 执行或查看同一任务。

## 技术栈

React 18 · TypeScript 5 · Ant Design 5 · Vite 5 · React Router v6 · Zustand · Axios · Less

## 快速开始

要求 Node.js 22.13+ 与 npm 10。锁文件存在时使用 `npm ci` 保证依赖可复现：

```bash
npm ci
npm run dev          # 开发（端口 5173，代理到本地后端 8080）
npm run build        # 生产构建（产物 dist/）
npm run typecheck    # TypeScript 检查
npm run lint         # ESLint 检查并修复
```

开发代理与接口前缀由 `.env.development` 控制：

- `VITE_APP_BASE_API=/dev-api`（开发）/ `/prod-api`（生产）
- `VITE_BACKEND_HOST=http://127.0.0.1:8080`（真实后端地址，仅开发代理使用）

## 与后端对接

- Token 存储于 Cookie（`Admin-Token`），请求头 `Authorization: Bearer <token>`
- 响应体统一 `{ code, msg, data }`，`code=401` 自动弹出重新登录
- 生产环境由 Nginx 将 `/prod-api/` 反代到后端 8080（配置示例见服务端仓库的《上线部署指南》）

## 目录结构

```text
src/
├── api/           业务 API（按模块分目录，TypeScript 类型化）
├── components/    通用组件（CrudPage / DictTag / ImageUpload ...）
├── hooks/         useAuth / useDict / useTheme
├── layouts/       整体布局（侧边栏含系统版本状态入口）
├── router/        常量路由 + 后端动态路由
├── store/         Zustand 全局状态
├── utils/         request 封装 / 鉴权 / 校验
└── views/         页面（aid 业务 / aidconfig 配置 / system 系统管理）
```

## 独立静态部署

只有二次开发或已有自建发布体系时，才需要单独部署本仓库。`npm run build` 生成 `dist/`，由 Nginx 托管，并将 `/prod-api/` 反向代理到 AID 服务端。默认根路径为 `/`；需要子路径部署时，先调整 `.env.production` 中的 `VITE_APP_CONTEXT_PATH` 再重新构建。生产环境还必须保留服务端生成的随机后台访问码路由与鉴权逻辑，不能只暴露固定 `/login` 地址。

## 开源协议

本项目基于 [MIT License](LICENSE) 开源，版权归光子讯息(杭州)科技有限公司所有。

后台管理框架部分基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)（MIT License）二次开发，特此致谢。
