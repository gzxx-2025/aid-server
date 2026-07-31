# AID 部署指南

本目录包含 AID 全部部署设施。**统一入口为管理脚本 `aid.sh`**（菜单式），支持两种部署方式，均可使用后台「一键在线升级」：

| 方式 | 适用场景 | 说明 |
|------|---------|------|
| Docker 部署（推荐） | 绝大多数用户 | 中间件全部容器化 |
| 手动部署 | 已有 MySQL/Redis 等基础设施 | systemd + Nginx 方式 |

统一约定：**全部数据默认放在 `/data/aid`**——程序产物（`app/`）、上传文件（`uploadPath/` 与私有归档 `uploadPath-private/`）、日志（`logs/`）、MySQL/Redis/RocketMQ 数据、备份（`backups/`）、发布包缓存（`packages/`）与部署配置（`aid-deploy.conf`）都在这一个目录下，备份或迁移整个目录即可。

## 目录说明

```text
deploy/
├── aid.sh                         # 统一部署管理脚本（菜单式，见下）
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

## 统一管理脚本 aid.sh

```bash
git clone https://gitee.com/gzxx-2025/aid-server.git
cd aid-server/deploy
sudo bash aid.sh
```

```text
==================== AID 部署管理 ====================
 部署方式: docker    脚本部署版本: 1.0.0（页面升级后以后台为准）
 数据目录: /data/aid
------------------------------------------------------
  1) 首次部署（Docker，推荐）
  2) 首次部署（手动 systemd）
  3) 更新/切换到指定版本（升级前自动完整备份）
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

- **自动判断每个环节**：自动识别部署方式与当前状态；数据库已初始化自动跳过；依赖缺失明确报错
- **配置真源清晰（两种方式同一模式）**：模板必须 `cp` 成正式配置文件才能部署，用户自行维护、脚本绝不重写——Docker 用 `deploy/docker/.env`（模板 `.env.example`），手动用 `/data/aid/aid-deploy.conf`（模板 `deploy/aid-deploy.conf.example`）；密码/密钥留空自动生成强随机值写回
- **资源全部可调**：后端 JVM、MySQL 缓冲池、Redis 内存上限、RocketMQ Broker/NameServer 内存（镜像默认 8G 大堆已按 1G 覆盖）在部署时逐项询问，回车用默认值
- **指定版本更新（菜单 3）**：输入任意版本号自动从发布页下载（也可指定本地包）；**升级前自动做完整备份**（程序产物 + 数据库全量 + 版本标记，保留最近 3 份）；包内增量 SQL 自动执行
- **回滚（菜单 4）**：从最近 3 份升级前备份中选择还原——程序产物直接还原；数据库默认不还原（避免丢失升级后产生的业务数据），需要时显式确认还原；回滚前还会对当前状态再做一份保护备份，误操作可救
- **在线升级器自动安装**：发布包内置升级器二进制，两种部署方式首次部署都会自动装好（Docker 为编排内 `aid-updater` 容器，手动为 systemd 服务），部署完成即可用后台页面一键升级；损坏时菜单 11（或 `sudo bash aid.sh setup-updater`）一键修复
- **密钥自动生成**：数据库密码、JWT 密钥留空自动生成强随机值
- 也支持直通子命令（便于 crontab 等）：`sudo bash aid.sh backup` / `restart` / `status` / `logs` / `update` / `rollback` / `setup-updater`

## 本地开发环境（面向开发者）

克隆代码后一条命令起齐后端所需环境，默认参数与后端开发配置完全对齐，IDE 直接启动即可连上：

```bash
cd deploy/docker
docker compose -f docker-compose.middleware.yml up -d                # MySQL + Redis
docker compose -f docker-compose.middleware.yml --profile mq up -d  # 需要联调 RocketMQ 时
```

MySQL 首次启动自动创建 `aid_test` 库并导入 `sql/` 初始化脚本（root/123456）；RocketMQ 的 Broker 已配置 `brokerIP1=127.0.0.1`，宿主机 IDE 里的后端可直接连接。该编排仅供开发，禁止用于生产。

## 配置要求（aid.sh 安装前自动校验，低于最低配置拒绝安装）

| 部署内容 | 最低配置 | 推荐配置 |
|---------|---------|---------|
| Docker 全栈（不启用 RocketMQ） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| Docker 全栈 + RocketMQ | 4核 6G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |
| 手动部署（中间件同机） | 2核 4G / 40G 磁盘 | 4核 8G / 100G+ 磁盘 |
| 手动部署 + RocketMQ | 4核 6G / 40G 磁盘 | 6核 12G / 100G+ 磁盘 |

**推算依据**（各组件常驻内存估算，含 JVM 堆外与操作系统开销）：

| 组件 | 常驻内存（默认参数） | 说明 |
|------|-------------------|------|
| 后端 JVM | ~2.5G | 堆 1-2G + 元空间/线程栈/堆外 ~0.5G |
| MySQL | ~1.5G | 缓冲池 1G + 连接与内部缓存 |
| Redis | ~0.6G | maxmemory 512m + 进程开销 |
| 用户端 SSR（Node） | ~0.4G | Nuxt 渲染进程 |
| Nginx + 系统预留 | ~1G | 内核/页缓存/守护进程 |
| RocketMQ（启用时） | +2G | NameServer 256m + Broker 堆 1G + 堆外与页缓存 ~0.8G |

合计：无 MQ ≈ 6G 常驻（8G 推荐留出生成任务峰值余量）；最低 4G 需依赖默认参数收紧与交换分区兜底，仅适合功能验证。磁盘 40G 为程序+数据库+日志的底线，媒体文件强烈建议配置 OSS/COS 对象存储（本地盘会很快写满）。安装时 `aid.sh` 按「部署方式 + 是否启用 MQ」动态套用上表校验：低于最低配置**直接拒绝安装**，介于最低与推荐之间给出警告并需确认继续。

## 一、准备发布包

到发布页下载最新的统一发布包（两个下载源内容一致）：

- Gitee：https://gitee.com/gzxx-2025/aid-server/releases
- GitHub：https://github.com/gzxx-2025/aid-server/releases

发布包 `aid-vX.Y.Z.tar.gz` 内部布局：

```text
├── backend/aid-admin.jar    # 服务端
├── admin-dist/              # 管理端静态产物（Nginx 托管）
├── web-dist/                # 用户端 SSR 产物（Node 运行 server/index.mjs）
└── sql/                     # 该版本增量 SQL（如有）
```

## 二、Docker 部署（推荐）

### 前置要求

- Linux 服务器，4 核 8G 起步，磁盘 100G+
- Docker Engine 24+，compose 插件 v2.20+（`docker compose version` 能正常输出；可选依赖声明需要 2.20+）

**国内服务器注意**：Docker Hub 官方源在国内网络下通常无法直接拉取镜像，部署前先配置镜像加速（任选其一：云厂商容器镜像加速地址，或公开加速站）：

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

阿里云/腾讯云服务器建议使用各自控制台提供的专属加速地址（更稳定）。

### 部署步骤（配置真源 = .env 文件，无交互问答）

```bash
git clone https://gitee.com/gzxx-2025/aid-server.git
cd aid-server/deploy/docker
cp .env.example .env      # 第一步（必须）：从模板复制出正式配置
vim .env                  # 第二步：按注释调整（端口/内存/Redis与MQ选择；密码留空自动生成）
cd ..
sudo bash aid.sh          # 第三步：菜单选 1，提供发布包路径
# 或直通：sudo bash aid.sh install-docker /path/to/aid-v1.0.0.tar.gz
```

`.env.example` 是只读模板（每项带注释与组合示例），**必须复制为 `.env` 才能部署**——脚本检测不到 `.env` 会明确提示并中止，绝不代替你做配置选择；`.env` 里密码/密钥留空的项会自动生成强随机值写回。

脚本自动完成：依赖预检 → `.env` 校验（缺失密钥自动生成）→ 硬件校验（按 `.env` 实际配置评估）→ 解包摆位到 `/data/aid/app` → 自动安装升级器 → 启动编排 → 首次启动自动建库导入 `sql/` 全部脚本 → 健康等待（最长 5 分钟）→ 成功摘要 / 失败诊断。

后续所有配置调整都编辑 `.env` 后执行菜单「重启服务」生效（菜单 9 也提供快捷编辑入口）。

访问地址：

- 用户端：`http://服务器IP/`（`HTTP_PORT` 可配，默认 80）
- 管理端：`http://服务器IP:8090/`（独立端口、根路径托管，`ADMIN_PORT` 可配），默认账号 `admin / admin123`，**登录后立即修改密码**

### Redis 与 RocketMQ 的三种用法（.env 两行配置）

**Redis**：默认内置容器（`COMPOSE_PROFILES=redis`，零维护）；使用外部实例时去掉 `redis` 并把 `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD` 改为外部地址，内置 Redis 容器不会启动。

**RocketMQ 三态**（`.env` 内注释有完整组合示例）：
- **不启用（默认）**：`ROCKETMQ_ENABLED=false`，系统走本地任务模式，功能完整，MQ 组件完全不加载
- **内置容器**：`COMPOSE_PROFILES=redis,mq` + `ROCKETMQ_ENABLED=true`；Broker/NameServer 内存用 `MQ_BROKER_JAVA_OPTS`/`MQ_NAMESRV_JAVA_OPTS` 调整（镜像默认 8G 大堆已覆盖为 1G/256m）；启用后到后台「消息队列配置」开启 MQ 派发并测试连接
- **外部实例**（另一台机器的 MQ）：`ROCKETMQ_ENABLED=true` + `ROCKETMQ_NAMESERVER=192.168.1.10:9876`，本机不启动 MQ 容器、不占内存

**跨机共享本机内置 MQ**（本机 compose 里的 MQ 给其他机器用）：修改 `docker/rocketmq/broker.conf` 的 `brokerIP1` 为本机对外 IP，并在 `docker-compose.yml` 的 `rocketmq-broker` 服务上开放 `10909/10911` 端口映射后重启。

### 必做的安全项

- 修改 admin 默认密码
- 生产环境为 Nginx 配置 HTTPS（修改 `docker/nginx/aid.conf` 增加 443 监听与证书）
- 备份好 `/data/aid/aid-deploy.conf`（含数据库密码与密钥，权限已限制 600）

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
# 0 3 * * * bash /path/to/aid-server/deploy/aid.sh backup >> /var/log/aid-backup.log 2>&1
```

恢复：

```bash
# 恢复数据库（会覆盖现有数据，先确认！Docker 部署示例）
gunzip < /data/aid/backups/<时间戳>/db.sql.gz | docker exec -i aid-mysql mysql -uroot -p<root密码> aid
# 恢复上传文件
tar -xzf /data/aid/backups/<时间戳>/uploadPath.tar.gz -C /data/aid
sudo bash aid.sh restart
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

### 环境要求（全部由部署方自行安装维护，脚本只做版本检查、不代装）

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 后端运行 |
| MySQL | 5.7（8.x 兼容） | 业务数据库（本机或远程均可） |
| Redis | 6.x+ | 缓存与分布式锁 |
| Node.js | 18+ | 用户端 SSR 运行 |
| Nginx | 1.20+ | 静态托管与反向代理 |
| mysql 客户端 + curl | - | 数据库初始化与健康检查 |
| RocketMQ | 5.x | 可选（不装则走本地任务模式，功能完整） |

以上环境按你团队的运维习惯安装（发行版包管理器 / 二进制包 / 已有实例均可），脚本启动时逐项检查，缺失或版本不足会明确报错并中止，不会擅自改动你的环境。

### 部署步骤（配置真源 = aid-deploy.conf 文件，无交互问答）

环境就绪后：

```bash
cd aid-server/deploy
mkdir -p /data/aid
cp aid-deploy.conf.example /data/aid/aid-deploy.conf   # 第一步（必须）：从模板复制出正式配置
vim /data/aid/aid-deploy.conf                          # 第二步：至少填写数据库密码，按需调整连接/端口/MQ
sudo bash aid.sh                                       # 第三步：菜单选 2，提供发布包路径
# 或直通：sudo bash aid.sh install-manual /path/to/aid-v1.0.0.tar.gz
```

与 Docker 部署完全一致的模式：`aid-deploy.conf.example` 是只读模板（每项带注释），**必须复制为 `/data/aid/aid-deploy.conf` 才能部署**——脚本检测不到配置文件会明确提示并中止；`TOKEN_SECRET` 留空自动生成强随机值写回，数据库密码必填（脚本会当场校验连通性）。

脚本自动完成：依赖检查（JDK17+/Node18+/mysql 客户端，版本不足明确报错）→ 配置文件校验 → 硬件校验 → 数据库连通性校验 → 空库自动导入基线（已有表跳过）→ 解包摆位到 `/data/aid/app` → 注册 `aid` + `aid-web` 双 systemd 服务（环境变量含 `LOG_PATH=/data/aid/logs`，日志统一落数据目录）→ 生成 Nginx 站点（已装 nginx 直接生效并备份旧配置）→ 自动安装升级器 → 健康等待。

全部业务配置项通过环境变量注入 systemd 服务定义（`DB_*`、`REDIS_*`、`TOKEN_SECRET`、`AID_PROFILE`、`LOG_PATH`、`ROCKETMQ_*`），jar 内配置永不修改；后续调整都编辑 `/data/aid/aid-deploy.conf` 后执行菜单「重启服务」生效（服务定义自动重写）。

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

aid-updater 让后台「项目升级配置」页具备一键升级/回退能力。**发布包内置升级器二进制，`aid.sh` 首次部署时两种方式都会自动安装并启动**，部署完成即可在后台页面看到升级器「运行正常」：

- **Docker 部署**：升级器以编排内 `aid-updater` 容器运行（通过 docker.sock 控制业务容器起停；增量 SQL 与数据库备份经 `docker exec` 在 MySQL 容器内执行，**宿主机无需安装 MySQL 客户端**）
- **手动部署**：升级器以 `aid-updater` systemd 服务运行（配置自动生成，含数据库凭证，SQL 与备份直连本地客户端）

配置文件 `/etc/aid-updater/config.json` 与数据目录 `/var/lib/aid-updater/`（任务/健康/日志/备份）由脚本自动生成，正常情况下不需要手工修改。

**升级器异常时的修复方式**（后台页面「安装升级器 / 修复引导」弹窗也会提示同样的命令，并展示升级器运行日志辅助排查）：

```bash
cd aid-server/deploy
sudo bash aid.sh setup-updater     # 或 sudo bash aid.sh 选择菜单 11
```

命令自动识别部署方式，重新放置二进制、重写配置并重启升级器，幂等可反复执行。

> 老环境/离线场景仍可用 `install-updater.sh` 手工安装（把 Release 附件里的
> `aid-updater_<版本>_linux_amd64.tar.gz` 解压到 deploy 目录后 `sudo bash install-updater.sh`），
> 之后按需编辑 `/etc/aid-updater/config.json`。

## 五、在线升级说明

发布方发布新版本后，**全程页面操作、无需输入任何命令**：

1. 后台左上角自动提示新版本（每天自动感知一次，手动「检查更新」立即感知）
2. 「项目升级配置 → 一键升级」：升级器自动完成 下载 → 校验 → 备份 → **执行增量 SQL（服务运行中）** → 停服 → 替换三端产物 → 启动 → 健康检查，失败自动回滚；停机窗口只有「替换文件 + 启动」
3. SQL 脚本由执行记录表 `aid_schema_history` 自动判重（Flyway 机制）：已执行过的自动跳过、失败的允许重试，重复升级不会重复执行
4. Docker 与手动部署的升级动作完全一致（唯一区别是重启方式 `docker restart` / `systemctl restart`，由升级器配置决定，页面「部署方式」一栏自动显示）
5. 「版本回退」可回退到清单允许的历史版本

升级器自身也支持在线升级（升级页「升级器」卡片）。

**正式版与测试版**：官方发布分两个渠道——正式版（完整验证，推送全部用户）与测试版（新功能抢先体验，稳定性略低，版本号带 `-beta/-rc` 后缀）。默认只接收正式版；愿意尝鲜的用户在「项目升级配置 → 升级配置 → 接收版本渠道」中选择「正式版 + 测试版」后，检查更新会同时对比两个渠道并按版本更高者提示，页面版本号旁会标注「正式版/测试版」。正式版发布后版本高于测试版时会自动提示升回正式渠道。
