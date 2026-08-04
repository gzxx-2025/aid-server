# AID — 开源 AI 漫剧 · AI 电影 · AI 漫画创作平台

<p>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Java-17-orange.svg" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/MySQL-5.7%2B-4479A1.svg" alt="MySQL">
  <img src="https://img.shields.io/badge/MyBatis--Plus-3.5-red.svg" alt="MyBatis-Plus">
</p>

<h2 align="center">🌐 官方入口</h2>
<p align="center">
  <a href="https://www.aidstudio.com.cn/"><strong>官方运营站：https://www.aidstudio.com.cn/</strong></a>
  &nbsp;&nbsp;·&nbsp;&nbsp;
  <a href="https://gzxxaitdb.feishu.cn/docx/LZ5zdesEgo1z4Mxc7OWc7zTHnJc"><strong>📘 部署与使用教程</strong></a><br>
  在线体验 AID，了解 AI 漫剧、AI 电影、AI 漫画三大创作方向及官方运营服务。
</p>

AID 是一套面向 **AI 漫剧、AI 电影、AI 漫画** 的开源内容创作平台，覆盖 **剧本创作 → 角色/道具/场景管理 → 分镜设计 → 文生图 → 图生视频 → 配音 → 成片** 的完整生产流程，内置多 AI 厂商统一编排、任务调度、计费支付与运营管理能力。

本仓库为 **服务端（aid-server）**，同时是三端统一的发布入口（版本清单、源码构建脚本和小型升级器均从本仓库发布）。

## 交流与反馈

部署、模型配置、二次开发或创作流程接入遇到问题，可以扫码加入交流群。欢迎提交 Issue，也欢迎分享部署经验、模型适配与创作案例。

<p align="center">
  <a href="references/community-qr.png">
    <img src="references/community-qr.png" alt="AID 开源交流群二维码" width="220">
  </a><br>
  <sub>如果二维码未直接显示，可点击二维码区域查看原图。</sub>
</p>

## AID 用来做什么

AID 面向想把 AI 生成能力真正落到内容生产流程里的团队、创作者和开发者。它不是一个单点的“生图页面”，而是一套围绕 AI 漫剧、AI 电影、AI 漫画搭建的完整内容生产系统：从最开始的故事设定，到角色、道具、场景资产沉淀，再到分镜拆解、画面生成、视频生成、配音合成和最终成片管理，都可以在同一套工作流中完成。

在创作侧，AID 让用户以“项目”为单位管理作品。一个项目可以包含剧本、分集、角色、道具、场景、分镜、图片、视频和配音记录。平台会把 AI 生成过程拆成可追踪的任务，支持批量生成、失败重试、进度查看和结果回收，避免创作者在多个模型平台之间反复复制粘贴、手动整理素材。

在运营侧，AID 提供完整后台能力：模型供应商配置、模型能力开关、价格 SKU、并发限制、内容审核、用户管理、订单充值、系统配置和在线升级。你可以把它当作一个可私有化部署的 AI 内容创作 SaaS 底座，也可以按自己的业务继续扩展模型、页面和计费策略。

在开发侧，AID 将多厂商 AI 能力统一封装为图片、视频、文本、语音等 Provider，通过统一任务系统、计费系统和回调机制对外提供稳定能力。新增厂商或模型时，优先复用已有 Provider 编排、模型配置、任务记录和扣费链路，适合二次开发、私有化部署与行业化改造。

## 三大核心创作方向

AID 将不同内容形态分别组织为清晰的创作方向。每个方向都可以独立建立项目、管理资产和执行生成任务，不只是把图片与视频能力简单堆叠在同一个页面中。

### AI 漫剧

面向连载化、分集化和角色驱动的动态内容生产。创作者可以从故事设定与分集剧本开始，持续维护角色、道具、场景和分镜资产，再将分镜图推进为图生视频、首尾帧视频或多镜头片段，并完成角色配音、TTS 音频和成片预览。适合竖屏漫剧、AI 短剧、漫改短视频、剧情账号和连续更新的 IP 内容。

### AI 电影

面向更强调电影叙事、镜头语言和视觉统一的影像创作。平台支持围绕完整剧本建立人物与场景资产，进行电影化分镜、景别与构图设计、视觉风格控制、镜头组拆解、多镜头视频生成、对白配音和成片素材管理。适合 AI 短片、概念预告片、品牌故事片、电影化剧情内容及长叙事影像实验。

### AI 漫画

面向以静态画面承载故事的连续视觉创作。创作者可以组织故事、角色、道具、场景和分镜，沉淀角色形象与参考图，生成分镜脚本、分镜图和连续画面，并在同一项目中维护画面资产与角色一致性。适合条漫、页漫、故事漫画、绘本、广告分镜和 IP 角色内容生产。

适合的使用场景包括：

- AI 漫剧、竖屏短剧、漫改视频、剧情账号与连续 IP 内容生产
- AI 电影、AI 短片、概念预告片、品牌故事片与电影化影像创作
- AI 漫画、条漫、页漫、故事绘本、分镜脚本与角色设定生产
- 创作者平台、内容工作室、MCN、教育培训和企业内部创意工具
- 多模型统一接入、统一计费、统一运营管理的私有化 AI 生成平台

## 仓库矩阵

| 端 | 说明 | Gitee | GitHub |
|----|------|-------|--------|
| aid-server | Java 服务端（本仓库） | [gitee](https://gitee.com/gzxx-2025/aid-server) | [github](https://github.com/gzxx-2025/aid-server) |
| aid-admin | 运营管理端（React） | [gitee](https://gitee.com/gzxx-2025/aid-admin) | [github](https://github.com/gzxx-2025/aid-admin) |
| aid-web | 用户创作端 | [gitee](https://gitee.com/gzxx-2025/aid-web) | [github](https://github.com/gzxx-2025/aid-web) |

## 官方资产包

AID 的初始化数据会引用一组官方媒体资源，用于首次部署后的平台展示和创作示例，包括角色、场景、道具、光影、景别/焦距、姿态、表情、特效、分镜示例、智能体与供应商图标、语音头像与 MP3 试听、首页图片及演示视频。该大体积资产包与程序源码分离，不会随 GitHub/Gitee Release 或一键部署自动下载，避免占用公共代码托管流量并防止误写部署方的 OSS/COS。

- 资产包只包含 `aid_init` 初始化库实际引用的官方文件，不包含用户生成内容、账号、密钥、日志或其他业务数据。
- 包内按 `files/aid/...` 保留数据库使用的原始对象键，可一次性导入本地存储、阿里云 OSS 或腾讯云 COS，无需批量改写初始化 SQL。
- 每个文件都记录在包内 `manifest.json` 和 `asset-checksums.txt` 中，可按路径、大小和 SHA-256 校验完整性。
- 获取入口与对应版本校验值由[官方运营站](https://www.aidstudio.com.cn/)及版本公告统一发布。请选择与程序版本一致的 `aid-official-assets_<版本>.tar.gz`，具体导入命令见包内 `README.md`。

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
| 数据库 | MySQL | 5.7 |
| 缓存 | Redis + Redisson | 3.x |
| 消息队列 | RocketMQ | 4.x/5.x |
| 定时任务 | Quartz | 2.5.x |
| 对象存储 | 阿里云 OSS / 腾讯云 COS | - |
| 接口文档 | SpringDoc OpenAPI | 2.8.x |
| 升级器 | Go | 1.22 |

## 快速开始

### 生产部署（推荐 Docker 一键部署）

普通用户只需下载并运行一个 `aid.sh` 文件，不需要去 Release 页面手工下载程序包：

```bash
curl -fL https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh -o aid.sh
sudo bash aid.sh install
```

脚本会自动发现当前渠道最新版本，优先检测 GitHub 三端的同一版本标签；GitHub 不通时整组切到 Gitee，在服务器临时目录完成服务端、后台管理端、Web 用户端和升级器构建，校验通过后再生成安全配置、部署中间件、初始化空数据库。同一条 `sudo bash aid.sh install` 命令在已部署机器上会自动改为检查更新，不会重复初始化数据库；部署成功后也可直接使用 `sudo aid update`。直接运行 `sudo bash aid.sh` 会进入统一管理菜单。完整流程、构建依赖与风险说明见[部署指南](deploy/README.md)。Docker 为推荐方式，手动 systemd 部署可使用 `sudo bash aid.sh install-manual`。

Docker 与手动 systemd 部署均支持可选 HTTPS：配置用户端与管理端两个域名、443 端口及 `DATA_ROOT/config/ssl` 下的证书即可启用 TLS；未启用时不会占用 443。Docker 可在内置 MySQL 5.7 与外部 MySQL 5.7 之间明确切换，外部模式不会启动内置数据库；Redis 支持可选 ACL 用户名、密码和数据库索引；内置及外部 RocketMQ 均支持可选 AccessKey/SecretKey，内置 Broker 还可选择同步/异步刷盘，所有密钥均由升级器脱敏管理。依赖默认按需处理：Docker 自动拉取缺失镜像，手动部署通过系统包管理器安装安全依赖，已有且版本合格的内容自动跳过；Docker Engine、MySQL 5.7 服务端和外部中间件不会被脚本擅自安装或覆盖。不要把 `HTTP_PORT` 直接设置为 443，这不会产生 HTTPS。完整配置、Nginx 路径与切换保护见[部署指南](deploy/README.md#内置或外部-mysqlredisrocketmq)。

**服务器配置要求**（安装脚本自动校验，低于最低配置拒绝安装）：

| 部署内容 | 最低配置 | 推荐配置 |
|---------|---------|---------|
| Docker 全栈（不启用 RocketMQ） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| Docker 全栈 + RocketMQ | 4核 6G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |
| 手动部署（中间件同机） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| 手动部署 + RocketMQ | 4核 6G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |

推算依据（各组件常驻内存：后端 JVM ~2.5G、MySQL ~1.5G、Redis ~0.6G、用户端 SSR ~0.4G、系统 ~1G，启用 RocketMQ 再 +2G）与调优方法见[部署指南](deploy/README.md)「配置要求」一节；媒体文件强烈建议配置 OSS/COS 对象存储，本地磁盘仅作兜底。

### 本地开发

要求：JDK 17+、Maven 3.6+、Docker（起中间件用）。

```bash
# 1. 一键启动开发环境（MySQL + Redis，自动导入 sql/ 初始化脚本）
cd deploy/docker
docker compose -f docker-compose.middleware.yml up -d
# 需要联调 RocketMQ 时改用：
# docker compose -f docker-compose.middleware.yml --profile mq up -d

# 2. 构建并启动后端（开发默认配置与上述环境完全对齐，无需修改任何配置）
cd ../..
mvn clean package -DskipTests
java -jar aid-admin/target/aid-admin.jar
```

访问 `http://localhost:8080` 验证服务；默认管理员 `admin / admin123`（首次登录后立即修改密码）。

后端全部环境参数支持环境变量覆盖：`DB_*`、`REDIS_*`、`TOKEN_SECRET`（**生产必须注入强随机值**）、`AID_PROFILE`、`ROCKETMQ_ENABLED`（未部署 RocketMQ 时设 `false` 可完全关闭 MQ 装配，系统走本地任务模式）、`ROCKETMQ_NAMESERVER`。

### 默认访问与后台访问码

生产部署完成后，默认访问地址如下：

| 入口 | 默认地址 | 说明 |
|------|---------|------|
| 用户端 | `http://服务器IP/` | 默认 80 端口，可通过 `HTTP_PORT` 调整 |
| 管理端 | `http://服务器IP:8090/` | 默认 8090 端口，可通过 `ADMIN_PORT` 调整 |
| 后端接口 | `http://服务器IP:8080/` | 默认 8080 端口，通常由 Nginx 反向代理访问 |

管理端默认账号为 `admin / admin123`。首次登录后请立即修改密码，并在后台进入「全局业务配置 → 登录与认证 → 后台登录入口」生成并保存 8 位访问码。

启用后台随机入口后，后台登录地址按下面规则拼接：

```text
http://服务器IP:8090/<访问码>
```

示例：如果服务器 IP 是 `192.168.1.10`，管理端端口是 `8090`，访问码是 `Ab12Cd34`，则后台登录地址为：

```text
http://192.168.1.10:8090/Ab12Cd34
```

如果配置了域名与 HTTPS，则同样把访问码追加到管理端站点根路径后：

```text
https://admin.example.com/Ab12Cd34
```

访问码不会预置固定值，需由管理员在后台生成；如遗忘，可在数据库 `aid_config` 表中查询 `category = 'admin_entry'`、`config_name = 'access_code'` 的记录，或重新生成后保存。

### 配置 AI 厂商

前端构建部署见 [aid-admin](https://gitee.com/gzxx-2025/aid-admin) 与 [aid-web](https://gitee.com/gzxx-2025/aid-web) 仓库。启动后在后台「AI模型配置」中配置至少一家厂商的密钥（或启用官方 API 统一网关）即可开始创作。

## 文档导航

| 文档 | 说明 |
|------|------|
| [部署指南](deploy/README.md) | Docker / 手动部署、生产参数调优、升级器安装、在线升级说明 |
| Swagger 接口文档 | 启动后访问 `http://localhost:8080/swagger-ui.html`（生产环境默认关闭） |

## 在线升级

系统内置完整升级体系：管理端左上角实时显示版本状态，检测到新版本后可页面一键升级；配套的独立升级器（`deploy/updater`，Go 实现）负责签名版本校验、同标签源码构建、自动备份、停服替换、增量 SQL 执行、健康检查与失败自动回滚，并支持回退到官方允许的历史版本。安装与使用详见[部署指南](deploy/README.md)。

## 产品预览

> README 中的图片均使用仓库相对路径引用，可在 Gitee / GitHub 两端直接渲染；如页面加载较慢，请稍等浏览器完成图片缓存。

### 用户端创作工作台

从项目创建、角色/场景资产管理，到分镜生成、画面调整、视频时间线与成片预览，用户端围绕“AI 漫画与漫剧创作”组织为连续工作流。

<p align="center">
  <img src="references/web/0.png" alt="AID 用户端登录入口" width="92%">
</p>

| 创作首页 | 项目工作台 |
|---------|-----------|
| <img src="references/web/1.png" alt="用户端创作首页" width="100%"> | <img src="references/web/2.png" alt="用户端项目列表" width="100%"> |

| 角色与素材选择 | 角色形象管理 |
|---------------|-------------|
| <img src="references/web/3.png" alt="角色与素材选择" width="100%"> | <img src="references/web/4.png" alt="角色形象管理" width="100%"> |

| 场景资产管理 | 模型与参数配置 |
|-------------|---------------|
| <img src="references/web/5.png" alt="场景资产管理" width="100%"> | <img src="references/web/6.png" alt="模型与参数配置" width="100%"> |

| 剧本分镜拆解 | 分镜任务列表 |
|-------------|-------------|
| <img src="references/web/7.png" alt="剧本分镜拆解" width="100%"> | <img src="references/web/8.png" alt="分镜任务列表" width="100%"> |

| 分镜画面编辑 | 分镜文本与镜头管理 |
|-------------|-------------------|
| <img src="references/web/9.png" alt="分镜画面编辑" width="100%"> | <img src="references/web/10.png" alt="分镜文本与镜头管理" width="100%"> |

| 分镜图生成结果 | 配音角色选择 |
|---------------|-------------|
| <img src="references/web/11.png" alt="分镜图生成结果" width="100%"> | <img src="references/web/12.png" alt="配音角色选择" width="100%"> |

<p align="center">
  <img src="references/web/13.png" alt="视频时间线与成片预览" width="92%">
</p>

### 管理端运营后台

管理端覆盖平台概览、用户与作品管理、AI 模型与供应商配置、任务监控、支付计费、系统升级等运营能力。

<p align="center">
  <img src="references/manager/0.png" alt="AID 管理端登录入口" width="92%">
</p>

| 数据概览 | 生成任务监控 |
|---------|-------------|
| <img src="references/manager/1.png" alt="管理端数据概览" width="100%"> | <img src="references/manager/2.png" alt="生成任务监控" width="100%"> |

| 用户管理 | 内容详情审核 |
|---------|-------------|
| <img src="references/manager/3.png" alt="用户管理" width="100%"> | <img src="references/manager/4.png" alt="内容详情审核" width="100%"> |

| AI 模型配置 | 模型功能配置 |
|------------|-------------|
| <img src="references/manager/5.png" alt="AI 模型配置" width="100%"> | <img src="references/manager/6.png" alt="模型功能配置" width="100%"> |

| 支付与运营配置 | 供应商配置 |
|---------------|-----------|
| <img src="references/manager/7.png" alt="支付与运营配置" width="100%"> | <img src="references/manager/8.png" alt="供应商配置" width="100%"> |

<p align="center">
  <img src="references/manager/9.png" alt="在线升级配置" width="92%">
</p>

## 参与贡献

欢迎 Issue 与 Pull Request：

1. Fork 本仓库并创建特性分支
2. 遵循仓库既有分层规范与编码约定（Controller 进 business-*，业务逻辑进 interface-main，实体与 Mapper 进 interface-system）
3. 提交 PR 并描述变更动机与影响范围

## 开源协议

本项目基于 [MIT License](LICENSE) 开源，版权归光子讯息(杭州)科技有限公司所有。

后台管理框架部分基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)（MIT License）二次开发，特此致谢。
