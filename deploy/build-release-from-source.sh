#!/bin/sh
# AID 远程源码构建器：从同一版本标签拉取三端公开源码，在临时目录完成构建并组装本地安装包。
# GitHub 三仓均可访问时优先使用 GitHub；任一不可用则整组回退到 Gitee，禁止混用来源。

set -eu

VERSION=""
OUTPUT=""
WORK_DIR=""
FORGE="${AID_SOURCE_FORGE:-auto}"
DATA_ROOT="${AID_DATA_ROOT:-/data/aid}"
CACHE_DIR="${AID_BUILD_CACHE_DIR:-$DATA_ROOT/build-cache}"

GITHUB_BASE="https://github.com/gzxx-2025"
GITEE_BASE="https://gitee.com/gzxx-2025"
SERVER_REPO="aid-server"
ADMIN_REPO="aid-admin"
WEB_REPO="aid-web"
GIT_IMAGE="${AID_GIT_IMAGE:-alpine/git:2.47.2}"
MAVEN_IMAGE="${AID_MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-17}"
NODE_IMAGE="${AID_NODE_IMAGE:-node:20-bookworm-slim}"
GO_IMAGE="${AID_GO_IMAGE:-golang:1.22-bookworm}"
DEPENDENCY_INSTALL_MODE="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
MANIFEST_PUBLIC_KEY="${AID_MANIFEST_PUBLIC_KEY:-9Ez/VMofgjCU0CNmE6Jq8LKLNyfDQqbbvNTTGV5BYrk=}"
SCRIPT_NAME="$(basename "$0")"
SCRIPT_HOME="$(dirname "$0")"
SCRIPT_HOME="$(CDPATH='' cd "$SCRIPT_HOME" && pwd)"
SCRIPT_PATH="$SCRIPT_HOME/$SCRIPT_NAME"

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '[%s] [提示] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die() { printf '[%s] [失败] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法: build-release-from-source.sh --version <版本> --output <tar.gz> [--work-dir <目录>] [--forge auto|github|gitee]

环境变量:
  AID_DATA_ROOT               数据目录，默认 /data/aid
  AID_NPM_REGISTRY            npm 镜像；Gitee 回退时默认使用 npmmirror
  AID_MAVEN_MIRROR_URL        Maven 镜像；Gitee 回退时默认使用阿里云公共仓库
  AID_GO_PROXY                Go 模块代理；Gitee 回退时默认使用 goproxy.cn
  AID_*_IMAGE                 覆盖 Docker 构建镜像
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version) [ "$#" -ge 2 ] || die '--version 缺少值'; VERSION="$2"; shift 2 ;;
    --output) [ "$#" -ge 2 ] || die '--output 缺少值'; OUTPUT="$2"; shift 2 ;;
    --work-dir) [ "$#" -ge 2 ] || die '--work-dir 缺少值'; WORK_DIR="$2"; shift 2 ;;
    --forge) [ "$#" -ge 2 ] || die '--forge 缺少值'; FORGE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

case "$VERSION" in
  ''|*[!0-9A-Za-z.-]*) die "版本号非法: ${VERSION:-空}" ;;
esac
case "$VERSION" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) die "版本号不符合 SemVer: $VERSION" ;;
esac
case "$FORGE" in auto|github|gitee) ;; *) die "源码平台仅支持 auto、github 或 gitee" ;; esac
case "$DEPENDENCY_INSTALL_MODE" in auto|manual) ;; *) die 'AID_DEPENDENCY_INSTALL_MODE 仅支持 auto 或 manual' ;; esac
[ -n "$OUTPUT" ] || die '--output 不能为空'
case "$OUTPUT" in /*) ;; *) OUTPUT="$(pwd)/$OUTPUT" ;; esac

TAG="v$VERSION"
if [ -z "$WORK_DIR" ]; then
  WORK_DIR="$DATA_ROOT/source-build/$TAG"
fi
case "$WORK_DIR" in /*) ;; *) WORK_DIR="$(pwd)/$WORK_DIR" ;; esac

command -v tar >/dev/null 2>&1 || die '未检测到 tar'
command -v sha256sum >/dev/null 2>&1 || die '未检测到 sha256sum'

USE_DOCKER=no
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  USE_DOCKER=yes
fi
if ! command -v git >/dev/null 2>&1 && [ "$USE_DOCKER" != yes ]; then
  die '未检测到 Git，且 Docker 不可用；请先安装 Git'
fi

ensure_docker_image() {
  image="$1"; label="$2"
  if docker image inspect "$image" >/dev/null 2>&1; then
    log "$label 镜像已存在，跳过下载: $image"
    return 0
  fi
  if [ "$DEPENDENCY_INSTALL_MODE" = manual ]; then
    die "缺少 $label 镜像 $image；请执行 docker pull $image，或把部署配置 DEPENDENCY_INSTALL_MODE 改为 auto"
  fi
  log "下载 $label 镜像: $image"
  docker pull "$image" || die "$label 镜像下载失败: $image"
}

if [ "$USE_DOCKER" = yes ]; then
  command -v git >/dev/null 2>&1 || ensure_docker_image "$GIT_IMAGE" 'Git源码拉取'
  ensure_docker_image "$MAVEN_IMAGE" 'JDK17/Maven构建'
  ensure_docker_image "$NODE_IMAGE" 'Node.js构建'
  ensure_docker_image "$GO_IMAGE" 'Go构建'
fi

# 删除范围必须严格位于 source-build 或升级器 work 目录下，避免配置错误导致误删。
safe_reset_work_dir() {
  case "$WORK_DIR" in
    "$DATA_ROOT"/source-build/*|/var/lib/aid-updater/work/*) ;;
    *) die "构建目录不在允许范围内: $WORK_DIR" ;;
  esac
  rm -rf -- "$WORK_DIR"
  mkdir -p "$WORK_DIR/repos" "$WORK_DIR/staging" "$CACHE_DIR"
}

git_probe() {
  repo_url="$1"
  if command -v git >/dev/null 2>&1; then
    if command -v timeout >/dev/null 2>&1; then
      GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1 GIT_HTTP_LOW_SPEED_TIME=12 \
        timeout 20 git ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
    else
      GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1 GIT_HTTP_LOW_SPEED_TIME=12 \
        git ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
    fi
    return $?
  fi
  if command -v timeout >/dev/null 2>&1; then
    timeout 30 docker run --rm "$GIT_IMAGE" -c http.lowSpeedLimit=1 -c http.lowSpeedTime=12 \
      ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
  else
    docker run --rm "$GIT_IMAGE" -c http.lowSpeedLimit=1 -c http.lowSpeedTime=12 \
      ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
  fi
}

probe_forge() {
  base="$1"
  for repo in "$SERVER_REPO" "$ADMIN_REPO" "$WEB_REPO"; do
    git_probe "$base/$repo.git" >/dev/null || return 1
  done
  return 0
}

select_forge() {
  case "$FORGE" in
    github)
      probe_forge "$GITHUB_BASE" || die "GitHub 三端源码标签 $TAG 不完整或网络不可达"
      SOURCE_BASE="$GITHUB_BASE"; SOURCE_FORGE=github ;;
    gitee)
      probe_forge "$GITEE_BASE" || die "Gitee 三端源码标签 $TAG 不完整或网络不可达"
      SOURCE_BASE="$GITEE_BASE"; SOURCE_FORGE=gitee ;;
    auto)
      log "检测 GitHub 三端源码标签 $TAG"
      if probe_forge "$GITHUB_BASE"; then
        SOURCE_BASE="$GITHUB_BASE"; SOURCE_FORGE=github
        log 'GitHub 可用，使用 GitHub 源码'
      else
        warn 'GitHub 不可用或标签不完整，整组切换到 Gitee'
        probe_forge "$GITEE_BASE" || die "GitHub 与 Gitee 均无法提供完整的三端标签 $TAG"
        SOURCE_BASE="$GITEE_BASE"; SOURCE_FORGE=gitee
        log 'Gitee 可用，使用 Gitee 源码'
      fi ;;
  esac
}

clone_repo() {
  repo="$1"
  dest="$2"
  url="$SOURCE_BASE/$repo.git"
  if command -v git >/dev/null 2>&1; then
    if command -v timeout >/dev/null 2>&1; then
      GIT_TERMINAL_PROMPT=0 timeout 300 git clone --depth 1 --single-branch --branch "$TAG" "$url" "$dest"
    else
      GIT_TERMINAL_PROMPT=0 git clone --depth 1 --single-branch --branch "$TAG" "$url" "$dest"
    fi
  else
    parent="$(dirname "$dest")"; name="$(basename "$dest")"
    if command -v timeout >/dev/null 2>&1; then
      timeout 300 docker run --rm --user "$(id -u):$(id -g)" -v "$parent:/work" -w /work "$GIT_IMAGE" \
        clone --depth 1 --single-branch --branch "$TAG" "$url" "$name"
    else
      docker run --rm --user "$(id -u):$(id -g)" -v "$parent:/work" -w /work "$GIT_IMAGE" \
        clone --depth 1 --single-branch --branch "$TAG" "$url" "$name"
    fi
  fi
}

clone_release_set() {
  rm -rf -- "$WORK_DIR/repos/server" "$WORK_DIR/repos/admin" "$WORK_DIR/repos/web"
  clone_repo "$SERVER_REPO" "$WORK_DIR/repos/server" || return 1
  clone_repo "$ADMIN_REPO" "$WORK_DIR/repos/admin" || return 1
  clone_repo "$WEB_REPO" "$WORK_DIR/repos/web" || return 1
  return 0
}

repo_commit() {
  repo_dir="$1"
  if command -v git >/dev/null 2>&1; then
    git -C "$repo_dir" rev-parse HEAD
  else
    docker run --rm --user "$(id -u):$(id -g)" -v "$repo_dir:/repo" -w /repo "$GIT_IMAGE" rev-parse HEAD
  fi
}

prepare_dependency_mirrors() {
  if [ "$SOURCE_FORGE" = gitee ]; then
    NPM_REGISTRY="${AID_NPM_REGISTRY:-https://registry.npmmirror.com}"
    MAVEN_MIRROR_URL="${AID_MAVEN_MIRROR_URL:-https://maven.aliyun.com/repository/public}"
    GO_PROXY="${AID_GO_PROXY:-https://goproxy.cn,direct}"
    warn '已启用国内依赖镜像；镜像只用于依赖下载，三端源码仍来自同一 Gitee 版本标签'
  else
    NPM_REGISTRY="${AID_NPM_REGISTRY:-https://registry.npmjs.org}"
    MAVEN_MIRROR_URL="${AID_MAVEN_MIRROR_URL:-https://repo.maven.apache.org/maven2}"
    GO_PROXY="${AID_GO_PROXY:-https://proxy.golang.org,direct}"
  fi
  cat > "$WORK_DIR/maven-settings.xml" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aid-build-mirror</id>
      <mirrorOf>*</mirrorOf>
      <url>$MAVEN_MIRROR_URL</url>
    </mirror>
  </mirrors>
</settings>
EOF
}

require_local_build_tools() {
  command -v java >/dev/null 2>&1 || die '源码构建需要 JDK 17+'
  command -v mvn >/dev/null 2>&1 || die '源码构建需要 Maven 3.8+'
  command -v node >/dev/null 2>&1 || die '源码构建需要 Node.js 18+'
  command -v npm >/dev/null 2>&1 || die '源码构建需要 npm'
  command -v go >/dev/null 2>&1 || die '源码构建升级器需要 Go 1.22+'
}

build_with_docker() {
  uid_gid="$(id -u):$(id -g)"
  log "拉取/复用构建镜像并编译服务端（JDK 17 + Maven）"
  docker run --rm --user "$uid_gid" \
    -v "$SERVER_DIR:/workspace" -v "$CACHE_DIR/m2:/cache/m2" \
    -v "$WORK_DIR/maven-settings.xml:/tmp/settings.xml:ro" -w /workspace "$MAVEN_IMAGE" \
    mvn -s /tmp/settings.xml -Dmaven.repo.local=/cache/m2 clean package -DskipTests -q

  log '编译后台管理端（Node.js 20）'
  docker run --rm --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
    -e "npm_config_registry=$NPM_REGISTRY" -e npm_config_cache=/cache/npm \
    -v "$ADMIN_DIR:/workspace" -v "$CACHE_DIR/npm-admin:/cache/npm" \
    -w /workspace "$NODE_IMAGE" sh -lc 'npm ci && npm run build'

  log '编译 Web 用户端（Node.js 20）'
  docker run --rm --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
    -e "npm_config_registry=$NPM_REGISTRY" -e npm_config_cache=/cache/npm \
    -v "$WEB_DIR:/workspace" -v "$CACHE_DIR/npm-web:/cache/npm" \
    -w /workspace "$NODE_IMAGE" sh -lc 'npm ci && npm run build'

  for arch in amd64 arm64; do
    log "编译升级器 linux/$arch"
    docker run --rm --user "$uid_gid" -e GOOS=linux -e "GOARCH=$arch" -e CGO_ENABLED=0 \
      -e "GOPROXY=$GO_PROXY" -e GOCACHE=/cache/build -e GOMODCACHE=/cache/mod \
      -v "$SERVER_DIR:/workspace" -v "$CACHE_DIR/go-build:/cache/build" \
      -v "$CACHE_DIR/go-mod:/cache/mod" -v "$STAGING_DIR/updater:/out" \
      -w /workspace/deploy/updater "$GO_IMAGE" \
      go build -ldflags "-X main.version=$VERSION -X aid-updater/internal/manifest.trustedPublicKey=$MANIFEST_PUBLIC_KEY" \
      -o "/out/aid-updater_linux_$arch" ./cmd/aid-updater
  done
}

build_with_host() {
  require_local_build_tools
  log '使用服务器本机工具链编译服务端'
  (cd "$SERVER_DIR" && mvn -s "$WORK_DIR/maven-settings.xml" -Dmaven.repo.local="$CACHE_DIR/m2" clean package -DskipTests -q)
  log '使用服务器本机工具链编译后台管理端'
  (cd "$ADMIN_DIR" && npm_config_registry="$NPM_REGISTRY" npm_config_cache="$CACHE_DIR/npm-admin" npm ci && npm run build)
  log '使用服务器本机工具链编译 Web 用户端'
  (cd "$WEB_DIR" && NUXT_TELEMETRY_DISABLED=1 npm_config_registry="$NPM_REGISTRY" npm_config_cache="$CACHE_DIR/npm-web" npm ci && npm run build)
  for arch in amd64 arm64; do
    log "编译升级器 linux/$arch"
    (cd "$SERVER_DIR/deploy/updater" && GOOS=linux GOARCH="$arch" CGO_ENABLED=0 GOPROXY="$GO_PROXY" \
      GOCACHE="$CACHE_DIR/go-build" GOMODCACHE="$CACHE_DIR/go-mod" \
      go build -ldflags "-X main.version=$VERSION -X aid-updater/internal/manifest.trustedPublicKey=$MANIFEST_PUBLIC_KEY" \
      -o "$STAGING_DIR/updater/aid-updater_linux_$arch" ./cmd/aid-updater)
  done
}

copy_installer_file() {
  relative="$1"
  source_file="$SERVER_DIR/$relative"
  [ -f "$source_file" ] || die "源码缺少安装文件: $relative"
  target_file="$STAGING_DIR/installer/$relative"
  mkdir -p "$(dirname "$target_file")"
  cp "$source_file" "$target_file"
}

assemble_package() {
  backend_jar="$SERVER_DIR/aid-admin/target/aid-admin.jar"
  [ -f "$backend_jar" ] || die '服务端构建产物缺失: aid-admin/target/aid-admin.jar'
  [ -d "$ADMIN_DIR/dist" ] || die '后台管理端构建产物缺失: dist/'
  if [ -f "$WEB_DIR/.output/server/index.mjs" ]; then
    web_output="$WEB_DIR/.output"
  elif [ -f "$WEB_DIR/dist/server/index.mjs" ]; then
    web_output="$WEB_DIR/dist"
  else
    die 'Web 构建产物缺少 server/index.mjs'
  fi

  mkdir -p "$STAGING_DIR/backend" "$STAGING_DIR/installer/sql" "$STAGING_DIR/sql"
  cp "$backend_jar" "$STAGING_DIR/backend/aid-admin.jar"
  cp -R "$ADMIN_DIR/dist" "$STAGING_DIR/admin-dist"
  cp -R "$web_output" "$STAGING_DIR/web-dist"

  for file in \
    deploy/README.md \
    deploy/aid-deploy.conf.example \
    deploy/aid-updater.config.example.json \
    deploy/aid-updater.service \
    deploy/install-updater.sh \
    sql/aid-init.sql; do
    copy_installer_file "$file"
  done
  manager_source="$SERVER_DIR/deploy/aid.sh"
  if [ ! -f "$SERVER_DIR/deploy/build-release-from-source.sh" ] && [ -n "${AID_MANAGER_SCRIPT:-}" ]; then
    manager_source="$AID_MANAGER_SCRIPT"
  fi
  [ -f "$manager_source" ] || die "缺少部署管理脚本: $manager_source"
  mkdir -p "$STAGING_DIR/installer/deploy"
  cp "$manager_source" "$STAGING_DIR/installer/deploy/aid.sh"
  if [ -f "$SERVER_DIR/deploy/build-release-from-source.sh" ]; then
    cp "$SERVER_DIR/deploy/build-release-from-source.sh" "$STAGING_DIR/installer/deploy/build-release-from-source.sh"
  else
    cp "$SCRIPT_PATH" "$STAGING_DIR/installer/deploy/build-release-from-source.sh"
  fi
  [ -d "$SERVER_DIR/deploy/docker" ] || die '源码缺少 deploy/docker'
  cp -R "$SERVER_DIR/deploy/docker" "$STAGING_DIR/installer/deploy/docker"

  has_sql=false
  for sql_file in "$SERVER_DIR"/sql/v*.sql; do
    [ -f "$sql_file" ] || continue
    cp "$sql_file" "$STAGING_DIR/sql/"
    has_sql=true
  done
  [ "$has_sql" = true ] || rmdir "$STAGING_DIR/sql"

  server_commit="$(repo_commit "$SERVER_DIR")"
  admin_commit="$(repo_commit "$ADMIN_DIR")"
  web_commit="$(repo_commit "$WEB_DIR")"
  channel=stable
  case "$VERSION" in *-*) channel=beta ;; esac
  cat > "$STAGING_DIR/build-info.json" <<EOF
{
  "product": "AID",
  "version": "$VERSION",
  "channel": "$channel",
  "builtAt": "$(date '+%Y-%m-%d %H:%M:%S')",
  "builtBy": "remote-source-build",
  "buildHost": "$(hostname 2>/dev/null || printf unknown)",
  "sourceForge": "$SOURCE_FORGE",
  "sourceRef": "$TAG",
  "serverCommit": "$server_commit",
  "adminCommit": "$admin_commit",
  "webCommit": "$web_commit",
  "containsWeb": true,
  "containsSql": $has_sql,
  "containsUpdater": true,
  "containsInstaller": true
}
EOF

  mkdir -p "$(dirname "$OUTPUT")"
  output_part="$OUTPUT.part"
  rm -f "$output_part"
  (cd "$STAGING_DIR" && tar -czf "$output_part" ./*)
  mv -f "$output_part" "$OUTPUT"
  log "源码构建包已生成: $OUTPUT"
  log "SHA256: $(sha256sum "$OUTPUT" | awk '{print $1}')"
}

safe_reset_work_dir
select_forge
prepare_dependency_mirrors

SERVER_DIR="$WORK_DIR/repos/server"
ADMIN_DIR="$WORK_DIR/repos/admin"
WEB_DIR="$WORK_DIR/repos/web"
STAGING_DIR="$WORK_DIR/staging"

log "按版本标签拉取三端源码: $TAG"
if ! clone_release_set; then
  if [ "$SOURCE_FORGE" = github ]; then
    warn 'GitHub 探测成功但拉取中断，清理未完成源码后整组重试 Gitee'
    probe_forge "$GITEE_BASE" || die "Gitee 三端源码标签 $TAG 不完整或网络不可达"
    SOURCE_BASE="$GITEE_BASE"
    SOURCE_FORGE=gitee
    prepare_dependency_mirrors
    clone_release_set || die "从 Gitee 拉取三端源码失败"
  else
    die "从 $SOURCE_FORGE 拉取三端源码失败"
  fi
fi

mkdir -p "$STAGING_DIR/updater" "$CACHE_DIR/m2" "$CACHE_DIR/npm-admin" \
  "$CACHE_DIR/npm-web" "$CACHE_DIR/go-build" "$CACHE_DIR/go-mod"
if [ "$USE_DOCKER" = yes ]; then
  build_with_docker
else
  build_with_host
fi
assemble_package
