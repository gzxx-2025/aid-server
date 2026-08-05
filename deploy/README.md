# AID 部署指南

本目录包含 AID 全部部署设施。**普通用户无需预先下载 `aid.sh`，复制一条官方命令即可安装或更新**：命令先从 Gitee 获取最新脚本，失败时可使用 GitHub 备用地址，再由 Bash 执行；脚本会读取签名版本清单，优先检查 Gitee 的三端版本标签，无法访问时整组切换到 GitHub，然后在服务器临时目录构建服务端、后台管理端、Web 用户端和升级器。构建全部成功并通过包结构校验后才进入安装或升级。两种部署方式均可使用脚本或后台「一键在线升级」。

| 方式 | 适用场景 | 说明 |
|------|---------|------|
| Docker 部署（推荐） | 绝大多数用户 | 中间件全部容器化 |
| 手动部署 | 不使用容器或对宿主机服务有明确要求 | systemd + Nginx；本机缺失依赖按版本自动准备 |

统一约定：**全部数据默认放在 `/data/aid`**——程序产物（`app/`）、受管安装器和 Docker 配置（`installer/`）、上传文件（`uploadPath/` 与私有归档 `uploadPath-private/`）、日志（`logs/`）、MySQL/Redis/RocketMQ 数据、备份（`backups/`）、本地源码构建包（`packages/`）、依赖缓存（`build-cache/`）与手动部署配置（`aid-deploy.conf`）都在这一个目录下，备份或迁移整个目录即可。

## 目录说明

```text
deploy/
├── aid.sh                         # 统一部署管理脚本（菜单式，见下）
├── build-release-from-source.sh   # 三端版本标签拉取、构建与本地组包
├── docker/                        # Docker 部署套件
│   ├── docker-compose.yml         # 生产编排（MySQL/Redis/后端/用户端/Nginx + 可选 RocketMQ）
│   ├── docker-compose.middleware.yml # 本地开发环境（仅中间件，配置与后端开发默认值对齐）
│   ├── .env.example               # 环境变量模板（aid.sh 自动生成 .env，一般无需手改）
│   ├── nginx/aid.conf             # 站点配置
│   └── rocketmq/                  # RocketMQ Broker 配置（broker.conf 生产 / broker-dev.conf 开发）
├── updater/                       # aid-updater 在线升级器源码（Go）
├── install-updater.sh             # 升级器安装脚本
├── aid-updater.service            # 升级器 systemd 服务单元
└── aid-updater.config.example.json # 升级器配置模板
```

## 一键部署（推荐）

```bash
# Docker 首次部署；curl 不可用时自动改用 wget
if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; elif command -v wget >/dev/null 2>&1; then wget -O aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; else echo '请先安装 curl 或 wget'; false; fi && sudo env AID_REMOTE_BOOTSTRAP=1 bash aid-install.sh install
```

这条命令不会把网络响应直接送入 Shell：只有 `curl/wget` 成功保存 `aid-install.sh` 后才执行。`AID_REMOTE_BOOTSTRAP=1` 表示本次必须使用刚下载的最新控制逻辑；在已部署服务器上不会被旧的受管脚本接管。`install` 是自动入口：未部署时执行推荐的 Docker 首次安装；检测到已经部署时自动改为检查并升级，不会重复初始化数据库。部署成功后还会安全创建 `sudo aid` 管理命令（如果系统已有同名命令则不覆盖）。

Gitee 原始文件访问失败时，可改用 GitHub 备用地址：

```bash
if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o aid-install.sh https://raw.githubusercontent.com/gzxx-2025/aid-server/master/deploy/aid.sh; elif command -v wget >/dev/null 2>&1; then wget -O aid-install.sh https://raw.githubusercontent.com/gzxx-2025/aid-server/master/deploy/aid.sh; else echo '请先安装 curl 或 wget'; false; fi && sudo env AID_REMOTE_BOOTSTRAP=1 bash aid-install.sh install
```

首次执行会自动完成：读取官方签名版本清单 → 正式版/Beta 渠道判断 → 检测 Gitee 三仓标签 → 失败时整组回退 GitHub → 在独立临时目录构建三端与升级器 → 本地 SHA256 与包结构校验 → 提取受管安装器到 `/data/aid/installer` → 自动生成安全配置和强随机密钥 → 硬件检查 → 部署三端与中间件 → 初始化空数据库 → 安装在线升级器 → 健康检查。源码拉取或构建失败不会替换现有服务。

配置是首次部署的强制前置步骤：脚本先生成正式配置文件，要求管理员检查、校验并明确确认；在确认完成前不会检查或安装环境、拉取三端源码、构建程序、初始化数据库或启动服务。无人值守部署必须同时设置 `AID_ASSUME_YES=1` 与 `AID_CONFIG_CONFIRMED=1`，避免流水线误用默认配置直接上线。

生成配置文件不等于已经部署。即使管理员在最终确认处选择 `n`，再次执行 `sudo bash aid.sh install` 仍会继续走首次部署，不会误进入升级流程；只有服务健康检查成功后才记录已部署状态。

> 默认渠道为 `auto`：有正式版时安装正式版；尚无正式版时才选择最新 Beta，并用黄色/红色信息明确提醒。明确需要测试版时，在一行命令的 `sudo env` 后增加 `AID_RELEASE_CHANNEL=beta`。生产服务器不要长期使用 Beta 渠道。

### 使用最新远程脚本更新

Docker 与手动 systemd 使用同一条更新命令，脚本会从部署描述文件自动识别当前方式：

```bash
if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; elif command -v wget >/dev/null 2>&1; then wget -O aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; else echo '请先安装 curl 或 wget'; false; fi && sudo env AID_REMOTE_BOOTSTRAP=1 bash aid-install.sh update
```

测试渠道更新时，将命令最后一段改为：

```bash
sudo env AID_REMOTE_BOOTSTRAP=1 AID_RELEASE_CHANNEL=beta bash aid-install.sh update
```

更新时配置采用“本地为主、官方只补缺项”规则：

- Docker 读取实际配置路径，单文件部署默认是 `/data/aid/config/docker.env`；手动部署默认是 `/data/aid/aid-deploy.conf`。
- 先读取当前最新 `aid.sh` 内置模板，再读取目标版本发布包中的正式模板；只追加本地完全不存在的参数名。
- 原配置中的值、注释、顺序、自定义参数和空值都不会被改写或删除。已经存在的参数即使与官方默认值不同，也始终保留本地值。
- 只有确实需要追加参数时才创建备份，备份与原文件同目录，例如 `docker.env.bak.20260804-120000` 或 `aid-deploy.conf.bak.20260804-120000`，权限保持为仅管理员可读写。
- 即使当前已是最新业务版本，远程脚本仍会先检查并补齐当前模板缺失项；没有缺项时不会改文件，也不会产生无意义备份。

直接执行 `sudo aid update` 仍然可用，它使用已经安装的受管脚本；需要优先取得官方最新安装/配置兼容逻辑时，推荐使用上面的远程更新命令。更新仍会在替换程序和执行增量 SQL 前完成数据库与三端产物备份，失败不会静默覆盖原配置。

### 自动化的安全边界

- 脚本只接受 HTTPS 版本清单，并只拉取 AID 官方 GitHub/Gitee 三个公开仓库；三端必须使用完全相同的 `v<版本>` 标签，禁止混用分支或平台。构建后的本地包仍会检查目录结构、路径穿越、特殊链接和内置脚本语法。
- OpenSSL 支持 Ed25519 `pkeyutl -rawin` 时会验证清单签名；较老的 OpenSSL 会以黄色信息明确提示。高安全环境建议升级 OpenSSL，后台在线升级器始终执行完整签名校验。
- 脚本不会静默开放防火墙、修改 DNS/域名、配置 HTTPS 证书、删除已有数据库，也不会自动导入大体积官方资产包。Docker 缺失或版本过低时会先单独提示对现有容器的风险，只有管理员明确同意后才配置软件源并安装/升级。
- 发现 `/data/aid` 已有非安装缓存内容、版本降级、数据库恢复、低于推荐硬件等情况时，默认拒绝或要求再次确认。`AID_ASSUME_YES=1` 只应在你已做好外部备份且明确授权的自动化环境使用。
- 本机备份不能替代异机备份。上线前应把数据库、`/data/aid/uploadPath` 和配置文件定期备份到另一台机器或对象存储。

## 统一管理脚本 aid.sh

```text
==================== AID 部署管理 ====================
 部署方式: docker    当前版本: 1.0.0    渠道: stable
 数据目录: /data/aid
------------------------------------------------------
  1) 一键首次部署（Docker，源码构建，推荐）
  2) 首次部署（手动 systemd，源码构建）
  3) 自动检查并升级到当前渠道最新版（升级前完整备份）
  4) 回滚到升级前备份（最近 3 份可选）
  5) 重启服务（配置变更后生效）
  6) 停止服务
  7) 查看状态
  8) 查看日志
  9) 修改配置（内存/端口/凭证等）
 10) 立即备份（数据库+上传文件）
 11) 安装/修复在线升级器
  0) 退出
------------------------------------------------------
```

- **自动判断每个环节**：自动识别部署方式与当前状态；组件存在且版本符合、数据库已经初始化时直接跳过；缺失项才下载、安装或启动
- **配置真源清晰**：首次部署自动从模板创建正式配置——单文件 Docker 部署默认使用 `/data/aid/config/docker.env`，手动部署使用 `/data/aid/aid-deploy.conf`；最终以脚本打印和 `/data/aid/config/deployment.json` 指向的绝对路径为准。密码和密钥留空时生成强随机值，后续只修改这一份正式配置
- **配置变更受控**：后台「项目升级配置 → 运行配置」只展示脱敏字段，可校验、应用或恢复上一份配置；升级器会先备份配置，原子写入后重启并健康检查，失败自动恢复。自定义配置文件只能位于 `/data/aid/config`，禁止软链接和任意路径读写
- **资源全部可调**：后端 JVM、MySQL 缓冲池、Redis 内存上限、RocketMQ Broker/NameServer 内存（镜像默认 8G 大堆已按 1G 覆盖）在部署时逐项询问，回车用默认值
- **自动更新（菜单 3）**：按已保存渠道读取最新版本；相同版本直接提示已是最新，远端版本较低时拒绝自动降级；确认后才拉取标签并构建，且**升级前自动做完整备份**（程序产物 + 数据库全量 + 版本标记，保留最近 3 份）；构建包内增量 SQL 自动执行
- **回滚（菜单 4）**：从最近 3 份升级前备份中选择还原——程序产物直接还原；数据库默认不还原（避免丢失升级后产生的业务数据），需要时显式确认还原；回滚前还会对当前状态再做一份保护备份，误操作可救
- **在线升级器自动安装**：源码构建包内置当前版本升级器二进制，两种部署方式首次部署都会自动装好（Docker 为编排内 `aid-updater` 容器，手动为 systemd 服务），部署完成即可用后台页面一键升级；损坏时菜单 11（或 `sudo bash aid.sh setup-updater`）一键修复
- **密钥自动生成**：数据库密码、JWT 密钥留空自动生成强随机值
- 也支持直通子命令：`sudo bash aid.sh install` / `update` / `backup` / `restart` / `status` / `logs` / `rollback` / `setup-updater`
- 明确授权的无人值守环境可设置 `AID_ASSUME_YES=1` 跳过部署或升级确认；该变量会绕过风险确认，**不要在交互式生产运维中长期配置**

## 本地开发环境（面向开发者）

克隆代码后一条命令起齐后端所需环境，默认参数与后端开发配置完全对齐，IDE 直接启动即可连上：

```bash
cd deploy/docker
docker compose -f docker-compose.middleware.yml up -d                # MySQL + Redis
docker compose -f docker-compose.middleware.yml --profile mq up -d  # 需要联调 RocketMQ 时
```

MySQL 首次启动自动创建 `aid_test` 库并导入 `sql/` 初始化脚本（root/123456）；RocketMQ 的 Broker 已配置 `brokerIP1=127.0.0.1`，宿主机 IDE 里的后端可直接连接。该编排仅供开发，禁止用于生产。

## 配置要求（aid.sh 安装前自动检查，低配时使用 y/n 确认）

| 部署内容 | 最低配置 | 推荐配置 |
|---------|---------|---------|
| 本机 Docker 全栈（不启用 RocketMQ） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| 本机 Docker 全栈（启用 RocketMQ） | 4核 4G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |
| 本机手动部署（中间件同机） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| 本机手动部署（启用 RocketMQ） | 4核 4G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |

**推算依据**（各组件常驻内存估算，含 JVM 堆外与操作系统开销）：

| 组件 | 常驻内存（默认参数） | 说明 |
|------|-------------------|------|
| 后端 JVM | ~2.5G | 堆 1-2G + 元空间/线程栈/堆外 ~0.5G |
| MySQL | ~1.5G | 缓冲池 1G + 连接与内部缓存 |
| Redis | ~0.6G | maxmemory 512m + 进程开销 |
| 用户端 SSR（Node） | ~0.4G | Nuxt 渲染进程 |
| Nginx + 系统预留 | ~1G | 内核/页缓存/守护进程 |
| RocketMQ（启用时） | +2G | NameServer 256m + Broker 堆 1G + 堆外与页缓存 ~0.8G |

本机启用 RocketMQ 时，4核4G 只是收紧 JVM/MQ 参数后的功能验证下限；消息量较大、需要 MQ 派发时建议 6核12G 或更高配置。磁盘 40G 为程序+数据库+日志的底线，媒体文件强烈建议配置 OSS/COS 对象存储（本地盘会很快写满）。安装时 `aid.sh` 会显示当前本机配置、最低配置和推荐配置；低于任一标准只警告风险，不强制限制脚本，输入 `y` 继续安装，输入 `n` 取消。

## 一、版本标签源码构建

正常部署和更新都由 `aid.sh` 自动处理，不需要用户访问发布页。版本清单、源码标签和小型升级器均以 Gitee 为主源、GitHub 为备用源；源码平台会先实际检测 Gitee 的 `aid-server`、`aid-admin`、`aid-web` 三个仓库是否都存在目标标签，任一失败就整组切换到 GitHub。构建固定使用 `v<版本>` 标签，绝不拉取会继续变化的 `master`，也不会在同一次构建中混用两个平台。

Docker 构建固定使用 Node.js 22.22.0；后台管理端和 Web 用户端还必须在各自 `package.json` 的 `packageManager` 中固定完整 npm 版本，当前均为 npm 10.9.4。发布机与服务器源码构建都会通过引导 npm 执行项目声明的精确版本，再运行 `npm ci` 和生产构建，不依赖宿主机或 Node 镜像碰巧携带的 npm 版本；`package-lock.json` 与 `package.json` 不一致时会明确阻止发布。Java 构建与运行固定使用 Eclipse Temurin OpenJDK 17.0.20+8。JDK 按宿主机架构自动选择 x64 或 AArch64 压缩包，下载后核对 Adoptium 官方 SHA-256，不修改宿主机默认 Java。构建镜像与依赖缓存在 `/data/aid/build-cache`，后续升级会直接复用。

`DEPENDENCY_REGION=auto` 会在目标服务器运行时按公网出口地区自动选择下载线路，地区服务不可用时再按网络可达性判断：国内依赖优先使用国内镜像，国际线路优先使用上游官方地址。Maven 在两种线路下均固定优先使用阿里云公共仓库，失败时自动用原始 Maven Central 重新构建；可通过 `AID_MAVEN_MIRROR_URL` 与 `AID_MAVEN_FALLBACK_URL` 分别覆盖。其他首选线路失败也会自动回退，且可明确设置为 `cn` 或 `global`。

Docker Hub 代理由正式配置项 `DOCKER_MIRRORS` 管理，默认候选为 `docker.m.daocloud.io,dockerproxy.net`。脚本先对所有候选 Registry 的 `/v2/` 接口进行短连接测速，再对当前要下载的具体镜像执行限时 manifest 预检：清单可用的来源优先，预检失败的来源保留在末尾兜底，真实拉取失败仍会继续尝试下一来源。这样可以识别“Registry 首页可访问，但 MySQL 分层存储不可用”的假可用节点。代理按标签下载后还必须匹配发布脚本内固定的官方 RepoDigest，否则立即拒绝该内容并继续回退。多个地址使用英文逗号分隔，最多 8 个；支持云厂商分配的专属加速域名，不允许在该配置中放入账号、密码或 URL 查询参数。旧版 `AID_DOCKER_CN_MIRROR` 环境变量仍兼容，但新部署应使用 `DOCKER_MIRRORS`。

三端构建不再隐藏 Maven 输出，并会分别打印服务端、后台管理端、Web 用户端的依赖安装、编译和完成状态。通过 `aid.sh` 部署或更新时，终端实时显示同一份日志，并以 `source-build-v<版本>-<时间>.log` 保存到 `/data/aid/logs/`；后台一键升级时，相同输出会进入升级器的 `updater.log`。

只有离线部署或开发调试时才需要自行准备本地包，可通过 `sudo bash aid.sh install-docker /path/to/aid-vX.Y.Z.tar.gz` 使用。因为外部本地包不在在线签名链路中，脚本会以红色风险信息提示，只做结构校验。

服务器构建出的本地包 `/data/aid/packages/aid-vX.Y.Z.tar.gz` 布局：

```text
├── backend/aid-admin.jar    # 服务端
├── admin-dist/              # 管理端静态产物（Nginx 托管）
├── web-dist/                # 用户端 SSR 产物（Node 运行 server/index.mjs）
├── updater/                 # 当前版本 Linux amd64/arm64 在线升级器
├── installer/               # 单文件自举需要的部署配置、编排与初始化基线
├── build-info.json          # 当前版本与构建来源
└── sql/                     # 该版本增量 SQL（如有，不包含初始化基线）
```

## 二、Docker 部署（推荐）

### 前置要求

- Linux 服务器，4 核 8G 起步，磁盘 100G+
- curl、tar（宿主机有 Git 时直接使用；没有时使用隔离的 Git 容器，不会安装系统软件）
- Docker Engine 24+，compose 插件 v2.20+；如果缺失，脚本会询问是否按测速后的清华/阿里云/官方 Docker CE 软件源自动安装

Docker 一键部署固定使用 MySQL 5.7，因此服务器需要使用 `x86_64` 架构。官方 MySQL 5.7 镜像不提供 ARM64 版本，脚本检测到 ARM64 时会明确中止，不会擅自切换数据库大版本。

如果没有安装 Docker 或版本不符合，脚本会先打印变更范围与“可能影响本机已有容器”的风险，再询问是否自动安装/升级；默认答案是 `no`。同意后才使用 HTTPS 软件源、核对 Docker 官方 GPG 指纹、安装 Engine/Compose 并启动服务；拒绝则不修改系统。已有合格版本时整段跳过。

**国内服务器注意**：AID 已内置多 Registry 测速与真实拉取回退。全新自动安装 Docker 且 `/etc/docker/daemon.json` 不存在时，脚本会把测速后的 `DOCKER_MIRRORS` 写入全局加速配置；检测到管理员已有 daemon.json 时绝不覆盖，AID 仍通过自己的候选前缀拉取镜像。需要手工配置时可使用：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.net"
  ]
}
EOF
sudo systemctl daemon-reload && sudo systemctl restart docker
```

阿里云/腾讯云服务器建议把控制台提供的专属加速地址同时放在 `registry-mirrors` 和 AID 的 `DOCKER_MIRRORS` 首位。公开代理属于外部服务，可能临时限流或不可用；多候选和官方回退用于提高成功率，不承诺任何第三方来源永久可用。

### 部署步骤（自动拉取源码构建、自动创建安全配置）

```bash
if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; elif command -v wget >/dev/null 2>&1; then wget -O aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; else echo '请先安装 curl 或 wget'; false; fi && sudo env AID_REMOTE_BOOTSTRAP=1 bash aid-install.sh install
```

脚本会先从版本标签源码构建本地包，再从本地包提取部署套件，并由 `.env.example` 自动生成正式配置；数据库密码、JWT 密钥等空值会生成强随机值写回。单文件首次部署的配置真源是 `/data/aid/config/docker.env`，完成后脚本也会明确打印实际路径。默认采用内置 MySQL + Redis、关闭 RocketMQ 与 HTTPS 的保守组合。需要改端口、HTTPS、外部 MySQL/Redis 或 RocketMQ 时，编辑实际配置文件后执行 `sudo aid restart` 生效。

Docker 模式不会要求宿主机另装 Git、JDK、Node、Go、Nginx 或 Redis：Node.js 22.22.0 与 Maven/Go 使用一次性隔离构建容器；OpenJDK 17.0.20 从校验后的压缩包生成本地固定运行镜像；HTTP Nginx 为运行容器；MySQL、Redis、RocketMQ 与 HTTPS 由 `COMPOSE_PROFILES` 决定是否启动对应容器。宿主机只需要 Docker Engine 24+ 与 Compose v2；缺失时经管理员确认可由脚本安装。

依赖处理由正式配置中的 `DEPENDENCY_INSTALL_MODE` 控制：

| 值 | Docker 部署行为 | 手动 systemd 部署行为 |
|----|-----------------|------------------------|
| `auto`（默认） | 缺失镜像按 `DEPENDENCY_REGION` 自动下载并校验；Docker 缺失/过旧仍需单独确认 | JDK 17.0.20、Node 22.22.0、Maven 3.9.9、Go 1.22.12 下载到隔离缓存并校验；Git/Nginx/本机 MySQL5.7/Redis 等按需安装 |
| `manual` | 缺镜像立即停止并打印准确的 `docker pull` 命令 | 只检查并列出缺失或版本不合格项，不修改系统 |

例如使用三个候选镜像时，在 `/data/aid/config/docker.env`（Docker）或 `/data/aid/aid-deploy.conf`（systemd）中写入：

```dotenv
DEPENDENCY_REGION=auto
DOCKER_MIRRORS=专属加速域名,docker.m.daocloud.io,dockerproxy.net
```

留空会使用安装器内置候选；原配置升级时会先在同目录备份，只补充缺少的 `DOCKER_MIRRORS`，不会覆盖已有值。部署后也可在后台管理的“系统升级 → 部署配置”中维护该列表。

Docker Engine/Compose 缺失或版本过旧时由管理员单独确认是否自动安装；现有合格版本直接跳过。外部 MySQL、Redis、RocketMQ 只做配置、认证和连通性校验，绝不会在宿主机安装同名服务或覆盖外部配置。手动模式配置本机地址且未发现数据库时，会安装隔离的 Oracle MySQL 5.7.44 通用二进制，下载文件必须匹配官方归档摘要，绝不会用发行版默认的 MySQL 8/MariaDB 冒充。

脚本自动完成：依赖预检 → 三仓同标签源码拉取与隔离构建 → `.env` 校验（缺失密钥自动生成）→ 硬件校验（按 `.env` 实际配置评估）→ 本地构建包摆位到 `/data/aid/app` → 自动安装升级器 → 启动编排 → 首次启动自动建库导入 `sql/` 全部脚本 → 健康等待（最长 5 分钟）→ 成功摘要 / 失败诊断。

后续所有配置调整都编辑 `.env` 后执行菜单「重启服务」生效（菜单 9 也提供快捷编辑入口）。

Docker Nginx 不是写进宿主机 `/etc/nginx`：受管 HTTP 配置位于 `/data/aid/installer/deploy/docker/nginx/aid.conf`，Compose 只读挂载到容器 `/etc/nginx/conf.d/default.conf`；HTTPS 模板位于同目录的 `aid-https.conf.template`，挂载到 `/etc/nginx/templates/default.conf.template`。这些文件属于当前程序版本，升级时可能被替换；自定义反向代理规则应放在 AID 外部的独立 Nginx/网关中，避免修改受管文件后升级丢失。

访问地址：

- 用户端：`http://服务器IP/`（`HTTP_PORT` 可配，默认 80）
- 管理端：`http://服务器IP:8089/<随机访问码>`（首次部署生成12位随机访问码并打印完整地址），默认账号 `admin / admin123`，**登录后立即修改密码**

没有域名不影响 HTTP 部署：必须保留 `HTTP_PORT` 和 `ADMIN_PORT`，直接用服务器 IP 访问即可。Docker 保持 `COMPOSE_PROFILES=mysql,redis`（不要加入 `https`）；手动部署保持 `HTTPS_ENABLED=false`。此时 `HTTPS_PUBLIC_DOMAIN`、`HTTPS_ADMIN_DOMAIN`、证书和私钥路径只是占位值，不会读取或校验。不要把 `HTTP_PORT` 改成 443，443 端口本身不代表已启用 TLS。

### Docker 内置 HTTPS（可选 Profile）

`HTTP_PORT=443` 只会把明文 HTTP 映射到 443，并不会启用 TLS，禁止这样配置。标准 HTTPS 使用独立 `https` Profile：用户端和管理端使用两个不同域名，共用一张覆盖两个域名的 SAN 或通配符证书。

```bash
mkdir -p /data/aid/config/ssl
cp /安全来源/fullchain.pem /data/aid/config/ssl/fullchain.pem
cp /安全来源/privkey.pem /data/aid/config/ssl/privkey.pem
chmod 600 /data/aid/config/ssl/fullchain.pem /data/aid/config/ssl/privkey.pem
```

配置示例：

```dotenv
COMPOSE_PROFILES=mysql,redis,https
HTTPS_PORT=443
HTTPS_PUBLIC_DOMAIN=www.example.com
HTTPS_ADMIN_DOMAIN=admin.example.com
HTTPS_CERT_PATH=/data/aid/config/ssl/fullchain.pem
HTTPS_KEY_PATH=/data/aid/config/ssl/privkey.pem
```

证书文件必须位于 `DATA_ROOT/config/ssl` 且不能是软链接。用户端访问 `https://www.example.com/`，管理端地址为 `https://admin.example.com/<随机访问码>`。证书续期后复制覆盖这两个文件并执行 `sudo aid restart`。未加入 `https` Profile 时不会启动 HTTPS 容器，也不会占用 443。部署完成后脚本会读取数据库中的实际访问码并输出完整登录地址。

### 内置或外部 MySQL、Redis、RocketMQ

**MySQL**：默认 `COMPOSE_PROFILES=mysql,redis`，启动内置 MySQL 5.7。使用外部 MySQL 5.7 时，从 `COMPOSE_PROFILES` 中移除 `mysql`，并配置：

```dotenv
COMPOSE_PROFILES=redis
DB_HOST=10.0.0.20
DB_PORT=3306
DB_NAME=aid
DB_USERNAME=aid
DB_PASSWORD=请填写真实强密码
```

外部地址必须同时能被 AID 业务容器和临时数据库客户端访问；数据库在 Docker 宿主机时可使用 `host.docker.internal`，远程数据库建议使用内网 IP 或内部 DNS。外部账号应对目标库具备建表、索引、数据读写、事务、视图、触发器及备份/恢复所需权限；若账号没有建库权限，请先由 DBA 创建 UTF-8 MB4 的目标库并授权。

- 全新安装且目标库为空：脚本验证 MySQL 必须为 5.7 后，自动导入该版本的初始化与扩展 SQL。
- 从内置 MySQL 切换到外部 MySQL：必须先迁移数据；检测到旧内置容器但外部库为空时会拒绝切换，防止“成功启动但业务数据消失”。
- 外部库通过连接、版本及 AID 核心表校验后，`aid-mysql` 才会被停止并移除，`${DATA_ROOT}/mysql-data` 保留用于人工回退；后续 Compose、重启和升级都不会再次启动内置 MySQL。
- 备份、恢复和增量 SQL 使用一次性的 `mysql:5.7` 客户端容器，执行后自动删除；它不是数据库服务，不保存业务数据，宿主机无需安装 MySQL 客户端。

不要只修改 `DB_HOST` 而保留 `mysql` Profile：这种矛盾配置会被安装脚本和升级器拒绝。后台「项目升级配置」页面同样支持切换，但只接受已经完成数据迁移且包含 AID 核心表的外部库。

**Redis**：默认 `COMPOSE_PROFILES` 包含 `redis`，因此启动内置容器；使用外部实例时去掉 `redis`，并配置 `REDIS_HOST/REDIS_PORT/REDIS_USERNAME/REDIS_PASSWORD/REDIS_DATABASE`。用户名和密码均可留空；Redis 6+ ACL 填用户名与密码，传统 `requirepass` 只填密码。外部地址不能写 `127.0.0.1`，应使用容器可访问的内网 IP 或 DNS。

**RocketMQ 三态**（`.env` 内注释有完整组合示例）：
- **不启用（默认）**：`ROCKETMQ_ENABLED=false`，系统走本地任务模式，功能完整，MQ 组件完全不加载
- **内置容器**：`COMPOSE_PROFILES=mysql,redis,mq` + `ROCKETMQ_ENABLED=true`；Broker/NameServer 内存用 `MQ_BROKER_JAVA_OPTS`/`MQ_NAMESRV_JAVA_OPTS` 调整（镜像默认 8G 大堆已覆盖为 1G/256m）。如同时填写 `ROCKETMQ_ACCESS_KEY` 与 `ROCKETMQ_SECRET_KEY`，内置 Broker 会自动开启 ACL；两项同时留空则只适合可信内网。启用后到后台「消息队列配置」开启 MQ 派发并测试连接
- **宿主机已有实例**：`COMPOSE_PROFILES` 不含 `mq`，设置 `ROCKETMQ_ENABLED=true`、`ROCKETMQ_NAMESERVER=host.docker.internal:9876`。Docker 中的 `127.0.0.1`/`localhost` 指向 AID 业务容器自身，因此会被安装器和升级器拒绝；宿主机 NameServer 必须监听 Docker 网桥可访问的地址
- **外部实例**（另一台机器的 MQ）：`ROCKETMQ_ENABLED=true` + `ROCKETMQ_NAMESERVER=192.168.1.10:9876`，本机不启动 MQ 容器、不占内存；外部服务启用 ACL 时同时填写 `ROCKETMQ_ACCESS_KEY` 与 `ROCKETMQ_SECRET_KEY`，未启用 ACL 时两项同时留空

外部 MySQL、外部 Redis、外部 RocketMQ，同时启用内置 HTTPS 的完整 Profile 写法是 `COMPOSE_PROFILES=https`；外部组件不加入 `mysql`/`redis`/`mq` Profile。RocketMQ ACL 凭证仅允许字母和数字，会同时注入生产者、声明式消费者和后台连接测试；使用内置 MQ 时还会在容器运行期生成 Broker ACL 文件。页面只显示“已配置”，不会回传原文；真实凭证只保存在权限为 600 的正式运行配置中，不会写入仓库或配置模板。

安装、更新和 `sudo aid restart` 都会从临时 AID 容器的网络视角探测外部 NameServer，而不是只检查宿主机端口。探测失败会打印实际配置文件路径（单文件 Docker 部署默认 `/data/aid/config/docker.env`）以及关闭、内置、宿主机、其他服务器四种修复写法。NameServer 可达仍不代表 Broker 地址正确：外部 Broker 的 `brokerIP1` 必须填写 AID 容器可访问的宿主机网桥地址或内网 IP，不能发布 `127.0.0.1`。

内置 Broker 通过 `ROCKETMQ_FLUSH_DISK_TYPE` 选择刷盘策略：`ASYNC_FLUSH`（默认）性能优先，`SYNC_FLUSH` 在 Broker 返回成功前同步刷盘、持久性更高但延迟更大。AID 的生成任务本身仍通过 MQ 异步消费；生产者发送会等待 Broker 确认，不提供“发送后不看结果”的无确认模式，避免任务未入队却被业务误判为成功。外部或手动安装的 RocketMQ 必须在其 Broker 配置中设置 `flushDiskType`，AID 侧字段不会远程改写外部服务。

**跨机共享本机内置 MQ**（本机 compose 里的 MQ 给其他机器用）：修改 `docker/rocketmq/broker.conf` 的 `brokerIP1` 为本机对外 IP，并在 `docker-compose.yml` 的 `rocketmq-broker` 服务上开放 `10909/10911` 端口映射后重启。

### 必做的安全项

- 修改 admin 默认密码
- 生产环境启用 `https` Profile，证书和私钥放在受限证书目录，并只开放 80/443 所需端口
- 备份好脚本最终打印的 Docker 配置文件（单文件部署默认 `/data/aid/config/docker.env`）或 `/data/aid/aid-deploy.conf`（含数据库密码与密钥，权限已限制 600）

### 生产参数调优（部署时逐项询问 / 菜单 9 修改，默认按 4核8G 标定）

| 参数 | 默认值 | 调节建议 |
|------|--------|---------|
| `JAVA_OPTS` | `-Xms1g -Xmx2g` + G1GC + OOM 堆转储 | 8G 服务器 `-Xms2g -Xmx4g`；16G `-Xms4g -Xmx8g` |
| `MYSQL_BUFFER_POOL` | `1G` | 物理内存的 40%~50% |
| `MYSQL_MAX_CONNECTIONS` | `500` | 一般无需调整 |
| `REDIS_MAXMEMORY` | `512mb` | 按缓存量调节；策略默认 `noeviction`（系统用 Redis 存分布式锁，禁止静默淘汰） |
| `WEB_NODE_OPTIONS` | 空 | SSR 内存不足时 `--max-old-space-size=1024` |
| `MQ_NAMESRV_JAVA_OPTS` | `-Xms256m -Xmx256m` | NameServer 很轻，一般不调 |
| `MQ_BROKER_JAVA_OPTS` | `-Xms1g -Xmx1g -Xmn512m` | 消息量大时 `-Xms2g -Xmx2g -Xmn1g` 以上（镜像默认 8G 堆已被覆盖，小服务器可直接启动） |

所有容器已统一配置日志轮转（单文件 50MB × 3 个），Docker 日志不会打满磁盘；MySQL 慢查询日志（>1s）自动记录在数据目录 `slow.log`。

### 备份与恢复

菜单 10（或 `sudo bash aid.sh backup`）备份数据库全量 + 上传文件 + 部署配置到 `/data/aid/backups/<时间戳>/`，自动清理 7 天前的备份。每日自动备份：

```bash
crontab -e   # 追加：
# 0 3 * * * /usr/local/bin/aid backup >> /var/log/aid-backup.log 2>&1
```

恢复：

```bash
# 恢复数据库（会覆盖现有数据，先确认！Docker 部署示例）
gunzip < /data/aid/backups/<时间戳>/db.sql.gz | docker exec -i aid-mysql mysql -uroot -p<root密码> aid
# 恢复上传文件
tar -xzf /data/aid/backups/<时间戳>/uploadPath.tar.gz -C /data/aid
sudo aid restart
```

一键升级前升级器还会自动做一次独立备份（含数据库），双保险。

### 故障排查（Runbook）

| 现象 | 定位 | 处理 |
|------|------|------|
| `docker compose ps` 某容器反复重启 | `docker logs --tail 100 <容器名>` | 按日志报错处理；内存不足（exit 137）用菜单 9 调低内存参数 |
| aid-mysql 启动失败 | `docker logs aid-mysql` | 首次导入 SQL 报错时：删除 `/data/aid/mysql-data` 后重跑首次部署（会清空数据库，仅限首次部署） |
| aid-server unhealthy | `docker logs --tail 100 aid-server` | 常见为数据库密码不一致（配置改过但数据目录是旧密码初始化的）——首次部署期可删数据目录重来 |
| aid-web unhealthy | `docker logs aid-web` | web-dist 未部署（保活等待态）属正常提示；已部署则看 Node 报错 |
| 页面 502 | Nginx 到后端/用户端不通 | 状态确认 aid-server / aid-web healthy 后 `docker restart aid-nginx` |
| 端口冲突（启动即失败） | `ss -tlnp \| grep <端口>` | 菜单 9 修改端口配置后重启 |
| 磁盘写满 | `df -h`、`du -sh /data/aid/uploadPath` | 媒体文件建议配置 OSS/COS 对象存储；清理 `/data/aid/backups` 过期备份 |
| 一键升级失败 | 升级页「最近任务」+ `journalctl -u aid-updater` | 升级器已自动回滚到原版本；按任务失败原因处理后重试 |

停机与重启语义：后端已启用优雅停机（先拒新请求、等处理中请求最多 25 秒），`docker restart aid-server` / 升级器停服不会打断进行中的请求；MySQL 停机宽限 1 分钟保证数据页完整落盘。

## 三、手动部署

### 环境要求与自动安装边界

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | Temurin OpenJDK 17.0.20+8 | 脚本按架构下载到数据目录，不切换系统默认 Java |
| Git | 1.8.3+ | 拉取三个公开仓库的固定版本标签；启动时校验最低版本 |
| Maven | 3.9.9 | 下载到隔离缓存，用于服务端源码构建 |
| MySQL | 5.7 | 业务数据库（本机或远程均可） |
| Redis | 6.x+ | 缓存与分布式锁 |
| Node.js | 22.22.0 | 后台/Web 构建与用户端 SSR 运行 |
| npm | 由各端 `packageManager` 精确锁定（当前 10.9.4） | 后台管理端与 Web 用户端源码构建；不使用宿主机的漂移版本 |
| Go | 1.22.12 | 下载到隔离缓存，用于在线升级器源码构建 |
| Nginx | 1.18+ | 静态托管与反向代理；启动时校验最低版本 |
| mysql 客户端 + curl | - | 数据库初始化与健康检查 |
| RocketMQ | 5.x | 可选（不装则走本地任务模式，功能完整） |

默认 `DEPENDENCY_INSTALL_MODE=auto`：OpenJDK 17.0.20、Node、Maven、Go、MySQL 5.7.44 与 Redis 7.2.15 使用固定版本和官方摘要，缓存到 `/data/aid/build-cache/toolchains`；MySQL/Redis 的受管运行目录位于 `/data/aid/runtime`。Git、Nginx、编译器等通用工具才使用 `apt-get`、`dnf` 或 `yum` 安装。设置为 `manual` 后只检查和提示，不修改系统。

手动模式完全不依赖 Docker。配置本机 MySQL 且服务缺失时安装隔离的 5.7.44，并生成 `aid-mysql.service`；已有 5.7 或外部 5.7 只校验后跳过，检测到其他大版本立即停止。本机 Redis 缺失时编译固定的 7.2.15 并生成 `aid-redis.service`，支持空密码、默认用户密码或 Redis 6+ ACL 用户；已有 6+ 只启动和校验，旧版本不会被静默覆盖。外部 Redis 只校验。RocketMQ 永远不自动安装，启用后必须至少有一个外部 NameServer 可达。

### 部署步骤（配置真源 = aid-deploy.conf）

环境就绪后：

```bash
if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; elif command -v wget >/dev/null 2>&1; then wget -O aid-install.sh https://gitee.com/gzxx-2025/aid-server/raw/master/deploy/aid.sh; else echo '请先安装 curl 或 wget'; false; fi && sudo env AID_REMOTE_BOOTSTRAP=1 bash aid-install.sh install-manual
```

脚本会自动生成 `/data/aid/aid-deploy.conf`。全新本机 MySQL 的 root/业务密码会生成强随机值写回配置；已有或外部 MySQL 无法安全猜测凭证，因此会要求输入真实密码（不回显）并当场校验。`TOKEN_SECRET` 留空同样自动生成。主机、端口或外部中间件拓扑不是默认值时，先按脚本提示编辑该配置再重试。

手动部署始终使用宿主机隔离工具链，不因服务器碰巧存在 Docker 而改变构建方式。JDK、Node.js、Maven、Go 与 MySQL 归档先对候选 HTTPS 来源做短流量测速，再完整下载、校验官方摘要并缓存；Git、Nginx、Redis 客户端等使用发行版包管理器按需安装。Java 固定使用 Temurin OpenJDK 17.0.20，Web 固定使用 Node.js 22.22.0。配置调整后执行 `sudo aid restart` 只会重新校验现有版本和连通性，符合要求的组件全部跳过，不会重复安装或初始化。

脚本自动完成：依赖检测与按需安装 → 三仓同标签源码构建 → 配置文件校验 → 硬件校验 → 数据库连通性校验 → 空库自动导入基线（已有表跳过）→ 本地构建包摆位到 `/data/aid/app` → 注册 `aid` + `aid-web` 双 systemd 服务（环境变量含 `LOG_PATH=/data/aid/logs`，日志统一落数据目录）→ 生成 Nginx 站点 → 自动安装升级器 → 健康等待。

手动部署的 Nginx 站点标准路径是 `/etc/nginx/conf.d/aid.conf`。脚本写入前会备份现有同名文件，执行 `nginx -t` 成功后才重载；校验失败自动恢复旧文件。只有系统 Nginx 无法使用时，才把候选配置输出到 `/data/aid/aid-nginx.conf` 供管理员人工处理。

全部业务配置项通过环境变量注入 systemd 服务定义（`DB_*`、`REDIS_*`、`TOKEN_SECRET`、`AID_PROFILE`、`LOG_PATH`、`ROCKETMQ_*`），jar 内配置永不修改；后续调整都编辑 `/data/aid/aid-deploy.conf` 后执行菜单「重启服务」生效（服务定义自动重写）。

### 手动部署 HTTPS（可选）

没有域名时无需配置本节：保留 `HTTP_PORT`、`ADMIN_PORT` 和 `HTTPS_ENABLED=false`，通过服务器 IP 访问。域名完成 DNS 解析并准备好有效证书后，再按下列配置启用。

证书同样复制到 `/data/aid/config/ssl/fullchain.pem` 与 `/data/aid/config/ssl/privkey.pem`，然后在 `aid-deploy.conf` 中设置：

```dotenv
HTTPS_ENABLED=true
HTTPS_PORT=443
HTTPS_PUBLIC_DOMAIN=www.example.com
HTTPS_ADMIN_DOMAIN=admin.example.com
HTTPS_CERT_PATH=/data/aid/config/ssl/fullchain.pem
HTTPS_KEY_PATH=/data/aid/config/ssl/privkey.pem
```

执行 `sudo aid restart` 后，脚本会重新生成 Nginx 站点，先运行 `nginx -t` 校验证书和配置，再重载服务；校验失败会恢复原站点配置并中止。设置 `HTTPS_ENABLED=false` 即关闭脚本生成的 HTTPS Server。证书仍必须位于 `DATA_ROOT/config/ssl` 且不能使用软链接。

### RocketMQ（可选）内存配置

RocketMQ 发行包默认 JVM 堆极大（NameServer 4G、Broker 8G），中小服务器直接启动会失败或挤占业务内存。**推荐用 `JAVA_OPT_EXT` 环境变量覆盖堆参数**（发行包启动脚本会把它追加到 JVM 参数末尾，后者覆盖前者），不改发行包文件、升级 RocketMQ 也不丢配置：

```bash
# 临时启动（验证用）
JAVA_OPT_EXT="-Xms256m -Xmx256m -Xmn128m" nohup sh bin/mqnamesrv &
JAVA_OPT_EXT="-Xms1g -Xmx1g -Xmn512m"     nohup sh bin/mqbroker -c conf/broker.conf &
```

生产建议注册为 systemd 服务，内存写进服务定义（`rocketmq` 换成你的解压目录）：

```ini
# /etc/systemd/system/rocketmq-namesrv.service 的 [Service] 段
Environment=JAVA_OPT_EXT=-Xms256m -Xmx256m -Xmn128m
ExecStart=/opt/rocketmq/bin/mqnamesrv

# /etc/systemd/system/rocketmq-broker.service 的 [Service] 段
Environment=JAVA_OPT_EXT=-Xms1g -Xmx1g -Xmn512m
ExecStart=/opt/rocketmq/bin/mqbroker -c /opt/rocketmq/conf/broker.conf
```

内存参考：轻量使用 NameServer 256m + Broker 1G 起步；消息量大再逐步调到 Broker 2G~4G。也可以直接修改 `bin/runserver.sh`、`bin/runbroker.sh` 里的 `-Xms -Xmx`（传统方式，升级发行包时需重新修改）。

RocketMQ 起好后，在部署配置（或菜单 9）里「启用 RocketMQ」并填 NameServer 地址（如 `127.0.0.1:9876`），再到后台「消息队列配置」开启 MQ 派发并测试连接。

## 四、在线升级器（随部署自动安装，无需手工操作）

aid-updater 让后台「项目升级配置」页具备一键升级/回退能力。**服务器构建出的本地包内置升级器二进制，`aid.sh` 首次部署时两种方式都会自动安装并启动**，部署完成即可在后台页面看到升级器「运行正常」：

- **Docker 部署**：升级器以编排内 `aid-updater` 容器运行（通过 docker.sock 控制业务容器起停；内置库经 `docker exec` 执行增量 SQL 与备份，外部库使用执行即销毁的 MySQL 5.7 客户端容器，**宿主机无需安装 MySQL 客户端**）
- **手动部署**：升级器以 `aid-updater` systemd 服务运行（配置自动生成，含数据库凭证，SQL 与备份直连本地客户端）

配置文件 `/etc/aid-updater/config.json` 与数据目录 `/var/lib/aid-updater/`（任务/健康/日志/备份）由脚本自动生成，正常情况下不需要手工修改。

**升级器异常时的修复方式**（后台页面「安装升级器 / 修复引导」弹窗也会提示同样的命令，并展示升级器运行日志辅助排查）：

```bash
sudo bash /data/aid/installer/deploy/aid.sh setup-updater
# 或重新执行最初下载的 aid.sh，它会自动切换到受管脚本
```

命令自动识别部署方式，重新放置二进制、重写配置并重启升级器，幂等可反复执行。

> 老环境/离线场景仍可用 `install-updater.sh` 手工安装（把 Release 附件里的
> `aid-updater_<版本>_linux_amd64.tar.gz` 解压到 deploy 目录后 `sudo bash install-updater.sh`），
> 之后按需编辑 `/etc/aid-updater/config.json`。

## 五、在线升级说明

发布方发布新版本后，**全程页面操作、无需输入任何命令**：

1. 后台左上角自动提示新版本（每天自动感知一次，手动「检查更新」立即感知）
2. 如果「升级器」卡片提示存在新版本，先完成升级器在线升级并确认其恢复为「运行正常」；再点击「一键升级」。这是从旧版大包交付切换到源码标签构建时的兼容步骤，后续版本会保持同步
3. 「项目升级配置 → 一键升级」：升级器自动完成 签名版本校验 → GitHub/Gitee 同标签源码构建 → 本地包校验 → 备份 → **执行增量 SQL（服务运行中）** → 停服 → 替换三端产物 → 启动 → 健康检查，失败自动回滚；停机窗口只有「替换文件 + 启动」
4. SQL 脚本由执行记录表 `aid_schema_history` 自动判重（Flyway 机制）：已执行过的自动跳过、失败的允许重试，重复升级不会重复执行
5. Docker 与手动部署的升级动作完全一致（唯一区别是重启方式 `docker restart` / `systemctl restart`，由升级器配置决定，页面「部署方式」一栏自动显示）
6. 「版本回退」可回退到清单允许的历史版本

升级器自身也支持在线升级（升级页「升级器」卡片）。

系统升级与升级器升级有严格优先级：当两者同时存在新版本，后端会拒绝主程序升级，管理端也会禁用「一键升级」，必须先完成升级器升级并等待状态恢复正常。这保证新的清单协议、源码构建方式、配置校验和 SQL 迁移能力先就位，再由匹配的升级器处理业务版本。

### 后台管理运行配置

Docker 与手动 systemd 部署都可以在后台「项目升级配置 → 运行配置」维护当前实际生效的配置：

1. 页面读取升级器上报的脱敏快照，数据库密码、Redis 密码和 JWT 密钥只显示“已配置”，绝不回显原文。
2. 「校验配置」只检查字段、端口与 Docker Compose 语法，不写文件、不重启服务。
3. 「保存并应用」先保存旧配置，再原子写入唯一配置真源并重启；Docker 重新执行编排，systemd 重新加载 `EnvironmentFile`。健康检查失败会自动恢复旧配置并再次启动。
4. 「恢复上次配置」用于主动撤销最近一次后台配置应用。部署目录、数据库数据目录和已初始化的 Docker 数据库账号在页面中锁定，避免把配置修改误当成数据迁移。
5. 管理员也可以直接编辑配置文件后执行 `sudo aid restart`；脚本会按 `deployment.json` 指向的文件加载，并同步刷新升级器的数据库连接。

页面同时管理 `DEPENDENCY_INSTALL_MODE` 与内置 Broker 的 `ROCKETMQ_FLUSH_DISK_TYPE`。保存后，依赖模式用于下一次安装补全、源码构建或升级；刷盘模式在 Docker 内置 MQ 重建 Broker 容器时生效。外部 MQ 的刷盘策略仍由外部 Broker 管理。

**正式版与测试版**：官方发布分两个渠道——正式版（完整验证，推送全部用户）与测试版（新功能抢先体验，稳定性略低，版本号带 `-beta/-rc` 后缀）。默认只接收正式版；愿意尝鲜的用户在「项目升级配置 → 升级配置 → 接收版本渠道」中选择「正式版 + 测试版」后，检查更新会同时对比两个渠道并按版本更高者提示，页面版本号旁会标注「正式版/测试版」。正式版发布后版本高于测试版时会自动提示升回正式渠道。
