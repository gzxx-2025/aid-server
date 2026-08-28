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

AID 是一套面向 **AI 漫剧、AI 电影、AI 漫画** 的开源内容生产平台，覆盖 **故事与剧本 → 分集 → 角色/道具/场景 → 分镜 → 图片 → 视频 → 配音 → 成片** 的完整工作流，并提供多 AI 厂商编排、任务调度、计费支付、运营管理、生产部署和在线升级能力。

本仓库是 AID 的**统一公开源码仓（aid-server）**和部署发布入口。服务端位于仓库根目录，运营管理端位于 `frontend/admin`，用户创作端位于 `frontend/web`；公开版本清单、一键部署脚本、增量 SQL 和独立升级器也由本仓库提供。一个版本只对应一个仓库标签，从源头避免三端版本不一致。

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

## 公开源码目录

公开源码统一发布到 [Gitee aid-server](https://gitee.com/gzxx-2025/aid-server) 和 [GitHub aid-server](https://github.com/gzxx-2025/aid-server)，两个平台使用相同提交和标签。

| 路径 | 内容 |
|------|------|
| `/` | Java 服务端、初始化 SQL、部署脚本与升级器 |
| `frontend/admin/` | 运营管理端（React） |
| `frontend/web/` | 用户创作端 |

`v1.0.0` 及更早版本仍保留原三仓标签和 Release，供既有环境兼容、历史源码获取与人工恢复使用；从 `v1.0.1` 起，新版本统一使用本仓库的同名标签。源码构建版本的一键升级失败会自动恢复升级前备份，但不提供跨版本的一键源码回退入口。

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

- 多厂商编排：文本、图片、视频和语音模型通过统一 Provider 接入，模型能力、参考图数量、分辨率、时长和比例均可配置
- 官方 API 统一网关：一个地址 + 一个 Key 替代全部厂商配置，支持按模型设置例外
- 统一任务系统：生成任务排队、并发调度、进度推送、失败重试、补偿和结果回收
- 视觉一致性：角色、道具、场景资产复用，官方/自定义风格与项目风格快照，参考图按模型能力安全编排
- 计费体系：按模型/SKU 计费、余额冻结与结算、充值套餐、支付宝/微信支付
- 用户体系：账号/短信/邮箱/微信扫码登录、实名认证、邀请激励
- 运营管理：模型、供应商、智能体、提示词、内容、订单、用户、存储和平台配置统一管理
- 生产运维：Docker 与 systemd 两种部署方式、内外部中间件、HTTPS、配置备份、状态诊断和完整卸载
- 在线升级：页面与命令行均可检查更新、查看实时进度和执行回退，配套独立升级器 `aid-updater`

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
├── deploy/updater   独立升级器（Go）
└── frontend
    ├── admin        运营管理端（React）
    └── web          用户创作端
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

### 生产部署

推荐在一台全新的 64 位 Linux 服务器上使用统一安装器。下面的命令会把脚本保存到 `/root/aid-install.sh`，优先从 Gitee 下载，失败后自动切换 GitHub；下载成功后才执行脚本，不会把网络响应直接通过管道交给 Shell。

```bash
cd /root && if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 --connect-timeout 15 -o /root/aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh || curl -fL --retry 3 --connect-timeout 15 -o /root/aid-install.sh https://raw.githubusercontent.com/gzxx-2025/aid-server/master/deploy/aid.sh; elif command -v wget >/dev/null 2>&1; then wget -O /root/aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh || wget -O /root/aid-install.sh https://raw.githubusercontent.com/gzxx-2025/aid-server/master/deploy/aid.sh; else echo '请先安装 curl 或 wget'; exit 1; fi && sudo env AID_REMOTE_BOOTSTRAP=1 AID_RELEASE_CHANNEL=beta bash /root/aid-install.sh install
```

`install` 是智能入口：未部署时默认进入 Docker 首次安装，已经部署时转入更新检查。也可以明确选择部署方式：

```bash
sudo env AID_REMOTE_BOOTSTRAP=1 AID_RELEASE_CHANNEL=beta bash /root/aid-install.sh install-docker
sudo env AID_REMOTE_BOOTSTRAP=1 AID_RELEASE_CHANNEL=beta bash /root/aid-install.sh install-manual
```

| 部署方式 | 适合场景 | 配置真源 | 运行方式 |
|---------|---------|---------|---------|
| Docker（推荐） | 新服务器、希望中间件与运行环境隔离 | `/data/aid/config/docker.env` | Docker Compose |
| 手动部署 | 已有主机环境、宝塔或 systemd 运维体系 | `/data/aid/aid-deploy.conf` | systemd + Nginx |

首次部署会先生成配置并要求管理员检查确认；**配置未确认前不会拉取统一源码、构建程序、初始化数据库或启动服务**。部署器会优先使用国内源码、依赖和镜像线路，失败时回退官方地址；服务端、管理端、Web 端与升级器从同一个版本标签构建，完整日志写入 `/data/aid/logs/`。已存在且版本符合的依赖会直接复用，未完整下载的缓存会重新校验和下载。

Docker 模式支持内置或外部 MySQL 5.7、内置或外部 Redis、可选 RocketMQ、可选 HTTPS；配置外部 MySQL 后不会启动内置 MySQL。Redis 用户名、密码和数据库索引均可为空，RocketMQ 关闭时不会启动或校验 MQ，启用后可配置外部 NameServer 与 ACL。手动模式会按需准备 JDK、Git、Maven、Node.js、Go、Nginx、MySQL 5.7 和 Redis；外部中间件只做连通性校验，RocketMQ 由管理员自行准备。

HTTPS 需要用户域名、管理域名、443 端口以及完整证书链和私钥。证书默认放在 `/data/aid/config/ssl/`，也可在管理端「项目升级配置 → 运行配置」中上传证书、配置域名并分别测试 DNS、证书和 HTTPS。仅设置 `HTTP_PORT=443` 不会自动启用 TLS。

部署完成后使用统一命令管理两种安装方式：

| 命令 | 作用 |
|------|------|
| `sudo aid` | 打开交互式管理菜单 |
| `sudo aid default` | 查看公网/内网用户端、管理端地址及初始化账号说明 |
| `sudo aid status` | 检查服务、中间件和升级器状态 |
| `sudo aid logs` | 查看运行日志 |
| `sudo aid config` | 编辑当前部署配置并按提示生效 |
| `sudo aid restart` | 重新加载配置并重启服务 |
| `sudo aid update` | 检查并执行当前渠道更新 |
| `sudo aid progress` | 查看升级、升级器更新或回退的实时进度 |
| `sudo aid rollback` | 选择官方允许回退的历史版本 |
| `sudo aid backup` | 创建部署备份 |
| `sudo aid mysql` | 查看当前 MySQL 连接信息 |
| `sudo aid uninstall --keep` | 卸载程序但保留数据与配置 |
| `sudo aid uninstall --purge` | 经二次确认后完整清除 AID 数据 |

**服务器配置要求**（安装脚本自动检查，低于配置时提示当前配置并使用 `y/n` 确认）：

| 部署内容 | 最低配置 | 推荐配置 |
|---------|---------|---------|
| 本机 Docker 全栈（不启用 RocketMQ） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| 本机 Docker 全栈（启用 RocketMQ） | 4核 4G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |
| 本机手动部署（中间件同机） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| 本机手动部署（启用 RocketMQ） | 4核 4G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |

启用 RocketMQ 的 4核4G 最低值仅用于本机搭建和功能验证，需要使用收紧后的 JVM/MQ 参数；消息量较大、需要 MQ 派发时建议使用 6核12G 或更高配置。调优方法见[部署指南](deploy/README.md)「配置要求」一节；媒体文件强烈建议配置 OSS/COS 对象存储，本地磁盘仅作兜底。

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

访问 `http://localhost:8080` 验证服务；数据库初始化管理员为 `admin / admin123`，首次登录后必须立即修改密码。

后端全部环境参数支持环境变量覆盖：`DB_*`、`REDIS_*`、`TOKEN_SECRET`（**生产必须注入强随机值**）、`AID_PROFILE`、`ROCKETMQ_ENABLED`（未部署 RocketMQ 时设 `false` 可完全关闭 MQ 装配，系统走本地任务模式）、`ROCKETMQ_NAMESERVER`。

### 默认访问与后台访问码

生产部署完成后，默认访问地址如下（实际端口和随机访问码以 `sudo aid default` 输出为准）：

| 入口 | 默认地址 | 说明 |
|------|---------|------|
| 用户端 | `http://服务器IP/` | 默认 80 端口，可通过 `HTTP_PORT` 调整 |
| 管理端 | `http://服务器IP:8089/<随机访问码>` | 默认 8089 端口，首次部署生成12位随机访问码并打印完整地址 |
| 后端接口 | `http://服务器IP:8080/` | 默认 8080 端口，通常由 Nginx 反向代理访问 |

管理端数据库初始化账号为 `admin / admin123`。首次部署不会使用公开的固定入口，安装器会生成 12 位随机访问码；首次登录后请立即修改管理员密码，并可在「全局业务配置 → 登录与认证 → 后台登录入口」重新生成访问码。`sudo aid default` 只展示初始化账号说明，不会反查或重置已经修改的管理员密码。

启用后台随机入口后，后台登录地址按下面规则拼接：

```text
http://服务器IP:8089/<访问码>
```

示例（实际访问码以部署完成后的输出为准）：

```text
http://localhost:8089/Ab12Cd34Ef56
```

如果配置了域名与 HTTPS，则同样把访问码追加到管理端站点根路径后：

```text
https://admin.example.com/Ab12Cd34Ef56
```

部署完成后，安装脚本会明确输出当前数据库中的完整后台登录地址。如遗忘访问码，可在数据库 `aid_config` 表中查询 `category = 'admin_entry'`、`config_name = 'access_code'` 的记录，或登录后重新生成并保存。

### 配置 AI 厂商

运营管理端和用户创作端源码分别位于本仓库的 [`frontend/admin`](frontend/admin) 与 [`frontend/web`](frontend/web)。启动后在后台「AI模型配置」中配置至少一家厂商的密钥（或启用官方 API 统一网关）即可开始创作。

## 文档导航

| 文档 | 说明 |
|------|------|
| [部署指南](deploy/README.md) | Docker / systemd 部署、配置项、HTTPS、中间件、升级、回退与卸载 |
| Swagger 接口文档 | 启动后访问 `http://localhost:8080/swagger-ui.html`（生产环境默认关闭） |

## 在线升级

管理端「系统管理 → 项目升级配置」和命令行使用同一套独立升级器。页面会展示当前版本、线上版本、双语版本说明、升级器状态、可回退版本以及黑色实时终端；任务执行期间禁止重复提交，低于 4 核 4G 时会在确认升级前给出高风险提醒。

升级流程会先确认升级器版本，升级器落后时必须先升级升级器；随后校验签名清单和统一仓库版本标签，完成配置与数据库备份、本机三端源码构建、增量 SQL、程序替换和健康检查。新增配置只会补入缺失项，原有配置值保持不变并在同目录生成备份；失败时按安全边界自动恢复程序与配置。升级期间可在页面或执行 `sudo aid progress` 查看同一份实时日志，按 `q` 退出查看不会中断后台任务。

更新会进行三端编译、数据库备份和健康检查，短时间内可能明显占用 CPU、内存和磁盘 I/O。生产环境应先做异机备份并在业务低峰执行；Beta 版本建议先在测试环境验证。完整升级、SQL 与回退规则见[部署指南](deploy/README.md)。

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
