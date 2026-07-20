# AID — 开源 AI 漫画·漫剧·视频创作平台

<p>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Java-17-orange.svg" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/MySQL-8.x-4479A1.svg" alt="MySQL">
  <img src="https://img.shields.io/badge/MyBatis--Plus-3.5-red.svg" alt="MyBatis-Plus">
</p>

AID 是一套开源的 AI 漫画、漫剧与视频创作平台，覆盖 **剧本创作 → 角色/道具/场景管理 → 分镜设计 → 文生图 → 图生视频 → 配音 → 成片** 的完整创作流程，内置多 AI 厂商统一编排、任务调度、计费支付与运营管理能力。

本仓库为 **服务端（aid-server）**，同时是三端统一的发布入口（版本清单、升级包、升级器均从本仓库发布）。

## 仓库矩阵

| 端 | 说明 | Gitee | GitHub |
|----|------|-------|--------|
| aid-server | Java 服务端（本仓库） | [gitee](https://gitee.com/gzxx-2025/aid-server) | [github](https://github.com/gzxx-2025/aid-server) |
| aid-admin | 运营管理端（React） | [gitee](https://gitee.com/gzxx-2025/aid-admin) | [github](https://github.com/gzxx-2025/aid-admin) |
| aid-web | 用户创作端 | [gitee](https://gitee.com/gzxx-2025/aid-web) | [github](https://github.com/gzxx-2025/aid-web) |

## 功能特性

**AI 创作全流程**

- 剧本与分集：项目/剧本/分集管理，AI 辅助剧本创作与场景资产提取
- 角色/道具/场景：形象资产库、参考图管理、角色配音绑定
- 分镜工作台：分镜脚本生成、分镜图生成、镜头组拆分、视频提示词生成
- 图像生成：文生图、图生图、多图融合，参考图占位协议统一治理
- 视频生成：图生视频、首尾帧、多镜头批量出片，清晰度/时长/比例按模型能力校验
- 配音合成：TTS 多音色、音色库管理、对口型

**平台能力**

- 多厂商编排：DashScope、火山方舟、即梦、Vidu、Gemini、MiniMax 等厂商统一接入，新增厂商实现 `ImageProviderClient` / `VideoProviderClient` 即插即用
- 官方 API 统一网关：一个地址 + 一个 Key 替代全部厂商配置，支持按模型设置例外
- 统一任务系统：生成任务排队、并发调度、SSE 进度推送、失败补偿与自愈
- 计费体系：按模型/SKU 计费、余额冻结与结算、充值套餐、支付宝/微信支付
- 用户体系：账号/短信/邮箱/微信扫码登录、实名认证、邀请激励
- 运营管理：模型/供应商/提示词/内容/订单/用户全后台管理
- **在线升级**：内置版本检查、页面一键升级、版本回退，配套独立升级器 aid-updater

## 系统架构

```text
aid-server（Maven 多模块单体）
├── aid-admin        Spring Boot 启动入口与配置
├── aid-common       公共组件（安全/缓存/存储/支付/短信适配）
├── aid-business     Web 层
│   ├── business-framework   Web框架、数据源、过滤器、AOP
│   ├── business-system      后台管理接口（/system /aid /aidconfig）
│   ├── business-main        C端接口（/auth /api/user /recharge）
│   ├── business-quartz      定时任务
│   └── business-generator   代码生成
├── aid-interface    领域层
│   ├── interface-core       核心接口
│   ├── interface-system     实体、Mapper、系统服务
│   └── interface-main       业务服务（媒体/分镜/计费/升级等）
├── aid-consumer     MQ 消费者
└── deploy/updater   独立升级器（Go）
```

调用链：`Controller → Service → Mapper → MySQL`，媒体生成经统一编排层路由到各厂商 Provider。

## 技术栈

| 维度 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.x |
| ORM | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.x |
| 缓存 | Redis + Redisson | 3.x |
| 消息队列 | RocketMQ | 4.x/5.x |
| 定时任务 | Quartz | 2.5.x |
| 对象存储 | 阿里云 OSS / 腾讯云 COS | - |
| 接口文档 | SpringDoc OpenAPI | 2.8.x |
| 升级器 | Go | 1.22 |

## 快速开始

### 环境要求

JDK 17+、Maven 3.6+、MySQL 8.x、Redis 6.x+、RocketMQ、Node.js 18+（构建前端）

### 1. 初始化数据库

```sql
CREATE DATABASE aid DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

导入 `sql/` 目录下的基线脚本与增量脚本（其中 `system_upgrade_official_gateway_init.sql` 为升级模块初始化，必须执行）。

### 2. 修改配置

`aid-admin/src/main/resources/`：

- `application-druid.yml`：MySQL 连接
- `application.yml`：Redis、RocketMQ（环境变量 `ROCKETMQ_NAMESERVER`）、上传目录 `aid.profile`、JWT 密钥 `token.secret`（**必须替换为强随机值**）

### 3. 构建并启动

```bash
mvn clean package -DskipTests
java -jar aid-admin/target/aid-admin.jar
```

访问 `http://localhost:8080` 验证服务；默认管理员 `admin`（首次登录后立即修改密码）。

### 4. 部署前端与配置 AI 厂商

前端构建部署见 [aid-admin](https://gitee.com/gzxx-2025/aid-admin) 与 [aid-web](https://gitee.com/gzxx-2025/aid-web) 仓库；完整生产部署（Nginx、HTTPS、回调地址）见 [上线部署指南](doc/上线部署指南.md)。

启动后在后台「AI模型配置」中配置至少一家厂商的密钥（或启用官方 API 统一网关）即可开始创作。

## 文档导航

| 文档 | 说明 |
|------|------|
| [上线部署指南](doc/上线部署指南.md) | 生产环境完整部署流程 |
| [系统升级指南](doc/系统升级指南.md) | 版本检查、一键升级、升级器安装、版本回退 |
| [发布流程指南](doc/发布流程指南.md) | 发布方：版本发布与清单维护（含发布工具） |
| [Business-API](doc/Business-API.md) | C 端业务接口文档 |

Swagger：`http://localhost:8080/swagger-ui.html`（生产环境默认关闭）

## 在线升级

系统内置完整升级体系：管理端左上角实时显示版本状态，检测到新版本后可页面一键升级；配套的独立升级器（`deploy/updater`，Go 实现）负责下载校验、自动备份、停服替换、增量 SQL 执行、健康检查与失败自动回滚，并支持回退到官方发布的历史版本。详见[系统升级指南](doc/系统升级指南.md)。

## 参与贡献

欢迎 Issue 与 Pull Request：

1. Fork 本仓库并创建特性分支
2. 遵循仓库既有分层规范与编码约定（Controller 进 business-*，业务逻辑进 interface-main，实体与 Mapper 进 interface-system）
3. 提交 PR 并描述变更动机与影响范围

## 开源协议

本项目基于 [MIT License](LICENSE) 开源，版权归光子讯息(杭州)科技有限公司所有。

后台管理框架部分基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)（MIT License）二次开发，特此致谢。
