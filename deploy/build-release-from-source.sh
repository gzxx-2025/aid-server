#!/bin/sh
# AID 远程源码构建器：从同一版本标签拉取三端公开源码，在临时目录完成构建并组装本地安装包。
# Gitee 三仓均可访问时优先使用 Gitee；任一不可用则整组回退到 GitHub，禁止混用来源。

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
NODE_IMAGE="${AID_NODE_IMAGE:-node:22.22.0-bookworm-slim}"
GO_IMAGE="${AID_GO_IMAGE:-golang:1.22.12-bookworm}"
DEPENDENCY_INSTALL_MODE="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
DEPENDENCY_REGION="${AID_DEPENDENCY_REGION:-auto}"
DEFAULT_DOCKER_MIRRORS="docker.m.daocloud.io,dockerproxy.net"
DOCKER_MIRRORS="${AID_DOCKER_MIRRORS:-${AID_DOCKER_CN_MIRROR:-$DEFAULT_DOCKER_MIRRORS}}"
DOCKER_MIRROR_ORDER=""
DOCKER_MIRRORS_RESOLVED=0
IMAGE_PULL_TIMEOUT_SECONDS="${AID_IMAGE_PULL_TIMEOUT_SECONDS:-900}"
IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS="${AID_IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS:-45}"
JDK_VERSION="17.0.20"
JDK_BUILD="8"
JDK_HOME=""
JAVA_RUNTIME_IMAGE="aid/openjdk:17.0.20"
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
  AID_NPM_REGISTRY            覆盖首选 npm 镜像
  AID_MAVEN_MIRROR_URL        覆盖首选 Maven 镜像
  AID_MAVEN_FALLBACK_URL      覆盖备用 Maven 仓库
  AID_GO_PROXY                覆盖 Go 模块代理链
  AID_DEPENDENCY_REGION       依赖线路：auto、cn 或 global；auto 按服务器公网出口地区选择
  AID_DOCKER_MIRRORS          Docker Hub 国内镜像前缀，逗号分隔；自动测速排序
  AID_DOCKER_CN_MIRROR        兼容旧版单镜像设置（新配置优先使用 AID_DOCKER_MIRRORS）
  AID_JDK_DOWNLOAD_URL        覆盖 Temurin OpenJDK 17.0.20 下载地址
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
case "$DEPENDENCY_REGION" in auto|cn|global) ;; *) die 'AID_DEPENDENCY_REGION 仅支持 auto、cn 或 global' ;; esac
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

detect_dependency_region() {
  case "$DEPENDENCY_REGION" in
    cn|global) RESOLVED_DEPENDENCY_REGION="$DEPENDENCY_REGION" ;;
    auto)
      detected_country=''
      if command -v curl >/dev/null 2>&1; then
        for country_url in https://ipinfo.io/country https://ifconfig.co/country-iso; do
          detected_country="$(curl -fsSL --connect-timeout 3 --max-time 6 "$country_url" 2>/dev/null \
            | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]' | head -c 2 || true)"
          case "$detected_country" in [A-Z][A-Z]) break ;; *) detected_country='' ;; esac
        done
      elif command -v wget >/dev/null 2>&1; then
        detected_country="$(wget -qO- --timeout=6 https://ipinfo.io/country 2>/dev/null \
          | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]' | head -c 2 || true)"
        case "$detected_country" in [A-Z][A-Z]) ;; *) detected_country='' ;; esac
      fi
      if [ "$detected_country" = CN ]; then
        RESOLVED_DEPENDENCY_REGION=cn
      elif [ -n "$detected_country" ]; then
        RESOLVED_DEPENDENCY_REGION=global
      elif [ "${SOURCE_FORGE:-}" = gitee ]; then
        RESOLVED_DEPENDENCY_REGION=cn
      elif [ "${SOURCE_FORGE:-}" = github ]; then
        RESOLVED_DEPENDENCY_REGION=global
      elif command -v curl >/dev/null 2>&1 && curl -fsSI --connect-timeout 5 --max-time 8 https://github.com >/dev/null 2>&1; then
        RESOLVED_DEPENDENCY_REGION=global
      elif command -v wget >/dev/null 2>&1 && wget -q --spider --timeout=8 https://github.com >/dev/null 2>&1; then
        RESOLVED_DEPENDENCY_REGION=global
      else
        RESOLVED_DEPENDENCY_REGION=cn
      fi ;;
  esac
}

normalize_docker_mirror() {
  mirror_value="$1"
  mirror_value="$(printf '%s' "$mirror_value" | tr '[:upper:]' '[:lower:]')"
  mirror_value="${mirror_value#https://}"
  mirror_value="${mirror_value#http://}"
  mirror_value="${mirror_value%/}"
  printf '%s\n' "$mirror_value" | grep -Eq '^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._/-]+)?$' || return 1
  printf '%s\n' "$mirror_value"
}

probe_docker_mirror() {
  mirror_probe="$1"
  mirror_registry="${mirror_probe%%/*}"
  command -v curl >/dev/null 2>&1 || return 1
  mirror_result="$(curl -sS -o /dev/null --connect-timeout 3 --max-time 6 \
    -w '%{http_code} %{time_total}' "https://$mirror_registry/v2/" 2>/dev/null || true)"
  mirror_code="${mirror_result%% *}"
  mirror_seconds="${mirror_result#* }"
  case "$mirror_code" in 200|401) ;; *) return 1 ;; esac
  awk -v value="$mirror_seconds" 'BEGIN { printf "%d\n", value * 1000 }'
}

resolve_docker_mirror_order() {
  [ "$DOCKER_MIRRORS_RESOLVED" -eq 0 ] || return 0
  mirror_ranked=''; mirror_deferred=''; mirror_seen=' '
  old_ifs="$IFS"; IFS=','
  for mirror_candidate in $DOCKER_MIRRORS; do
    IFS="$old_ifs"
    mirror_candidate="$(printf '%s' "$mirror_candidate" | tr -d '[:space:]')"
    [ -n "$mirror_candidate" ] || { IFS=','; continue; }
    mirror_normalized="$(normalize_docker_mirror "$mirror_candidate" 2>/dev/null || true)"
    if [ -z "$mirror_normalized" ]; then
      warn "已忽略非法 Docker 镜像地址: $mirror_candidate"
      IFS=','; continue
    fi
    case "$mirror_seen" in *" $mirror_normalized "*) IFS=','; continue ;; esac
    mirror_seen="$mirror_seen$mirror_normalized "
    if mirror_latency="$(probe_docker_mirror "$mirror_normalized" 2>/dev/null)"; then
      log "Docker镜像测速: $mirror_normalized ${mirror_latency}ms"
      mirror_ranked="${mirror_ranked}${mirror_latency} ${mirror_normalized}\n"
    else
      warn "Docker镜像测速不可达，保留为末位重试: $mirror_normalized"
      mirror_deferred="$mirror_deferred$mirror_normalized "
    fi
    IFS=','
  done
  IFS="$old_ifs"
  mirror_sorted=''
  if [ -n "$mirror_ranked" ]; then
    mirror_sorted="$(printf '%b' "$mirror_ranked" | sort -n -k1,1 | awk '{printf "%s ", $2}')"
  fi
  DOCKER_MIRROR_ORDER="$mirror_sorted$mirror_deferred"
  DOCKER_MIRROR_ORDER="${DOCKER_MIRROR_ORDER% }"
  DOCKER_MIRRORS_RESOLVED=1
  if [ -n "$DOCKER_MIRROR_ORDER" ]; then
    log "Docker国内镜像尝试顺序: $(printf '%s' "$DOCKER_MIRROR_ORDER" | sed 's/ / -> /g')"
  else
    warn '未配置有效 Docker 国内镜像，将只尝试 Docker Hub 官方地址'
  fi
}

dockerhub_mirror_image() {
  mirror_prefix="$1"
  image="$2"
  first="${image%%/*}"
  case "$image" in
    */*)
      case "$first" in *.*|*:*|localhost) return 1 ;; esac
      printf '%s/%s\n' "$mirror_prefix" "$image" ;;
    *) printf '%s/library/%s\n' "$mirror_prefix" "$image" ;;
  esac
}

docker_image_digest() {
  case "$1" in
    alpine/git:2.47.2) echo 'sha256:062a01ad7a0eb17cff382bc5e26086b4d710e56dfdfdf001109a49b6d9bd378c' ;;
    maven:3.9.9-eclipse-temurin-17) echo 'sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc' ;;
    node:22.22.0-bookworm-slim) echo 'sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94' ;;
    golang:1.22.12-bookworm) echo 'sha256:3d699e4d15d0f8f13c9195c0632a16702b8cbdece2955af1c23b37ae5d55a253' ;;
    debian:bookworm-slim) echo 'sha256:7b140f374b289a7c2befc338f42ebe6441b7ea838a042bbd5acbfca6ec875818' ;;
    node:22.22.0-alpine) echo 'sha256:e4bf2a82ad0a4037d28035ae71529873c069b13eb0455466ae0bc13363826e34' ;;
    nginx:1.25-alpine) echo 'sha256:516475cc129da42866742567714ddc681e5eed7b9ee0b9e9c015e464b4221a00' ;;
    docker:27-cli) echo 'sha256:851f91d241214e7c6db86513b270d58776379aacc5eb9c4a87e5b47115e3065c' ;;
    *) return 1 ;;
  esac
}

image_with_digest() {
  image="$1"; digest="$2"
  printf '%s@%s\n' "${image%:*}" "$digest"
}

local_image_matches_digest() {
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | grep -Fq "@$2"
}

pull_docker_image() {
  pull_image_ref="$1"
  if command -v timeout >/dev/null 2>&1; then
    timeout "$IMAGE_PULL_TIMEOUT_SECONDS" docker pull "$pull_image_ref"
  else
    docker pull "$pull_image_ref"
  fi
}

probe_docker_image_manifest() {
  manifest_probe_ref="$1"
  if command -v timeout >/dev/null 2>&1; then
    timeout "$IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS" docker manifest inspect "$manifest_probe_ref" >/dev/null 2>&1
  else
    docker manifest inspect "$manifest_probe_ref" >/dev/null 2>&1
  fi
}

try_docker_mirrors() {
  mirror_try_image="$1"; mirror_try_label="$2"; mirror_try_digest="$3"
  resolve_docker_mirror_order
  mirror_ready=''; mirror_deferred=''
  for mirror_try_prefix in $DOCKER_MIRROR_ORDER; do
    mirror_try_ref="$(dockerhub_mirror_image "$mirror_try_prefix" "$mirror_try_image" 2>/dev/null || true)"
    [ -n "$mirror_try_ref" ] || continue
    if probe_docker_image_manifest "$mirror_try_ref"; then
      log "Docker镜像清单可用: $mirror_try_ref"
      mirror_ready="$mirror_ready$mirror_try_prefix "
    else
      warn "Docker镜像清单预检失败，保留为末位重试: $mirror_try_ref"
      mirror_deferred="$mirror_deferred$mirror_try_prefix "
    fi
  done
  mirror_candidates="$mirror_ready$mirror_deferred"
  mirror_candidates="${mirror_candidates% }"
  for mirror_try_prefix in $mirror_candidates; do
    mirror_try_ref="$(dockerhub_mirror_image "$mirror_try_prefix" "$mirror_try_image" 2>/dev/null || true)"
    [ -n "$mirror_try_ref" ] || continue
    log "通过国内镜像下载 $mirror_try_label: $mirror_try_ref"
    if pull_docker_image "$mirror_try_ref"; then
      if [ -z "$mirror_try_digest" ] || local_image_matches_digest "$mirror_try_ref" "$mirror_try_digest"; then
        docker tag "$mirror_try_ref" "$mirror_try_image" || die "$mirror_try_label 镜像名称映射失败: $mirror_try_image"
        log "$mirror_try_label 镜像下载成功: $mirror_try_prefix"
        return 0
      fi
      warn "$mirror_try_label 镜像摘要与官方发布清单不一致，已拒绝来源: $mirror_try_prefix"
      docker image rm "$mirror_try_ref" >/dev/null 2>&1 || true
    else
      warn "$mirror_try_label 镜像下载失败，继续下一个来源: $mirror_try_prefix"
    fi
  done
  return 1
}

ensure_docker_image() {
  image="$1"; label="$2"; digest=''; official_ref=''
  digest="$(docker_image_digest "$image" 2>/dev/null || true)"
  if docker image inspect "$image" >/dev/null 2>&1; then
    if [ -z "$digest" ] || local_image_matches_digest "$image" "$digest"; then
      log "$label 镜像已存在，跳过下载: $image"
      return 0
    fi
    warn "$label 本地镜像摘要不符合当前发布清单，将重新拉取: $image"
  fi
  resolve_docker_mirror_order
  if [ -n "$digest" ]; then
    official_ref="$(image_with_digest "$image" "$digest")"
  else
    official_ref="$image"
    warn "$label 使用了自定义或未固定镜像，无法与官方发布摘要核对: $image"
  fi
  for mirror_prefix in $DOCKER_MIRROR_ORDER; do
    mirror_image="$(dockerhub_mirror_image "$mirror_prefix" "$image" 2>/dev/null || true)"
    if [ -n "$mirror_image" ] && docker image inspect "$mirror_image" >/dev/null 2>&1 \
        && { [ -z "$digest" ] || local_image_matches_digest "$mirror_image" "$digest"; }; then
      docker tag "$mirror_image" "$image"
      log "$label 国内镜像缓存有效，已映射为标准名称: $image"
      return 0
    fi
  done
  if [ "$DEPENDENCY_INSTALL_MODE" = manual ]; then
    die "缺少 $label 镜像 $image；请从已配置镜像或官方地址手动拉取后重试，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
  fi
  if [ "$RESOLVED_DEPENDENCY_REGION" = cn ]; then
    if try_docker_mirrors "$image" "$label" "$digest"; then return 0; fi
    warn "全部国内镜像均失败，自动回退官方地址: $official_ref"
  fi
  log "通过官方地址下载 $label: $official_ref"
  if pull_docker_image "$official_ref"; then
    [ "$official_ref" = "$image" ] || docker tag "$official_ref" "$image"
    return 0
  fi
  if [ "$RESOLVED_DEPENDENCY_REGION" != cn ]; then
    warn '官方地址下载失败，自动尝试测速后的国内镜像列表'
    if try_docker_mirrors "$image" "$label" "$digest"; then return 0; fi
  fi
  die "$label 镜像下载失败；官方地址和全部国内镜像均不可用: $image"
}

if [ "$USE_DOCKER" = yes ]; then
  detect_dependency_region
  command -v git >/dev/null 2>&1 || ensure_docker_image "$GIT_IMAGE" 'Git源码拉取'
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
      log "检测 Gitee 三端源码标签 $TAG"
      if probe_forge "$GITEE_BASE"; then
        SOURCE_BASE="$GITEE_BASE"; SOURCE_FORGE=gitee
        log 'Gitee 可用，使用 Gitee 主源'
      else
        warn 'Gitee 不可用或标签不完整，整组切换到 GitHub 备用源'
        probe_forge "$GITHUB_BASE" || die "Gitee 与 GitHub 均无法提供完整的三端标签 $TAG"
        SOURCE_BASE="$GITHUB_BASE"; SOURCE_FORGE=github
        log 'GitHub 可用，使用 GitHub 备用源'
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
  detect_dependency_region
  if [ "$RESOLVED_DEPENDENCY_REGION" = cn ]; then
    NPM_REGISTRY="${AID_NPM_REGISTRY:-https://registry.npmmirror.com}"
    NPM_REGISTRY_FALLBACK="https://registry.npmjs.org"
    GO_PROXY="${AID_GO_PROXY:-https://goproxy.cn|https://proxy.golang.org|direct}"
    warn '已自动选择国内依赖线路；Docker、JDK、Maven、npm、Go 均优先使用国内镜像并保留官方回退'
  else
    NPM_REGISTRY="${AID_NPM_REGISTRY:-https://registry.npmjs.org}"
    NPM_REGISTRY_FALLBACK="https://registry.npmmirror.com"
    GO_PROXY="${AID_GO_PROXY:-https://proxy.golang.org|https://goproxy.cn|direct}"
    log '已自动选择国际依赖线路；Docker、JDK、npm、Go 均保留国内备用线路'
  fi
  # 线上源码构建固定优先使用国内 Maven 镜像；国内镜像不可用时，重新执行
  # Maven 构建并回退到原始 Maven Central，避免单一镜像故障阻断部署。
  MAVEN_MIRROR_URL="${AID_MAVEN_MIRROR_URL:-https://maven.aliyun.com/repository/public}"
  MAVEN_MIRROR_FALLBACK_URL="${AID_MAVEN_FALLBACK_URL:-https://repo.maven.apache.org/maven2}"
  case "$MAVEN_MIRROR_URL" in https://*) ;; *) die "Maven 首选仓库必须使用 HTTPS: $MAVEN_MIRROR_URL" ;; esac
  case "$MAVEN_MIRROR_FALLBACK_URL" in https://*) ;; *) die "Maven 备用仓库必须使用 HTTPS: $MAVEN_MIRROR_FALLBACK_URL" ;; esac
  log "Maven 仓库：国内主源 $MAVEN_MIRROR_URL；官方备用 $MAVEN_MIRROR_FALLBACK_URL"
  write_maven_settings "$WORK_DIR/maven-settings.xml" "$MAVEN_MIRROR_URL" aid-build-primary
  write_maven_settings "$WORK_DIR/maven-settings-fallback.xml" "$MAVEN_MIRROR_FALLBACK_URL" aid-build-fallback
}

write_maven_settings() {
  settings_file="$1"; mirror_url="$2"; mirror_id="$3"
  cat > "$settings_file" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>$mirror_id</id>
      <mirrorOf>*</mirrorOf>
      <url>$mirror_url</url>
    </mirror>
  </mirrors>
</settings>
EOF
}

download_file() {
  url="$1"; target="$2"; part="$2.part"; expected_checksum="${3:-}"
  case "$url" in https://*) ;; *) warn "拒绝非 HTTPS 下载地址: $url"; return 1 ;; esac
  if [ -n "$expected_checksum" ] && [ -s "$target" ] \
      && [ "$(sha256sum "$target" 2>/dev/null | awk '{print $1}')" = "$expected_checksum" ]; then
    log "完整缓存校验通过，跳过下载: $target"
    return 0
  fi
  if [ -n "$expected_checksum" ] && [ -s "$part" ] \
      && [ "$(sha256sum "$part" 2>/dev/null | awk '{print $1}')" = "$expected_checksum" ]; then
    mv -f "$part" "$target"
    log "未完成缓存实际已完整，经 SHA256 校验后直接复用: $target"
    return 0
  fi
  [ ! -s "$part" ] || warn "发现未完成缓存，将从断点继续: $part"
  if command -v curl >/dev/null 2>&1; then
    if [ -s "$part" ]; then
      download_rc=0
      curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
        --max-time 1800 --speed-limit 32768 --speed-time 30 --proto '=https' --tlsv1.2 \
        --progress-bar --continue-at - --output "$part" "$url" || download_rc=$?
      if [ "$download_rc" -eq 33 ] || [ "$download_rc" -eq 36 ]; then
        warn '当前地址不支持断点续传，将从该地址重新下载'
        rm -f "$part"; download_rc=0
        curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
          --max-time 1800 --speed-limit 32768 --speed-time 30 --proto '=https' --tlsv1.2 \
          --progress-bar --output "$part" "$url" || download_rc=$?
      fi
      if [ "$download_rc" -ne 0 ]; then
        warn "下载中断，断点文件已保留: $part"
        return 1
      fi
    else
      curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
        --max-time 1800 --speed-limit 32768 --speed-time 30 --proto '=https' --tlsv1.2 \
        --progress-bar --output "$part" "$url" || {
          [ ! -s "$part" ] || warn "下载中断，断点文件已保留: $part"; return 1;
        }
    fi
  elif command -v wget >/dev/null 2>&1; then
    wget --continue --https-only --timeout=30 --tries=3 --output-document="$part" "$url" || {
      [ ! -s "$part" ] || warn "下载中断，断点文件已保留: $part"; return 1;
    }
  else
    die '下载 OpenJDK 需要 curl 或 wget'
  fi
  [ -s "$part" ] || { rm -f "$part"; return 1; }
  if [ -n "$expected_checksum" ] \
      && [ "$(sha256sum "$part" 2>/dev/null | awk '{print $1}')" != "$expected_checksum" ]; then
    warn "下载完成但 SHA256 不匹配，已删除不可信文件"
    rm -f "$part"
    return 1
  fi
  mv -f "$part" "$target"
}

prepare_exact_jdk() {
  case "$(uname -m)" in
    x86_64|amd64)
      jdk_arch=x64
      jdk_checksum=be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35 ;;
    aarch64|arm64)
      jdk_arch=aarch64
      jdk_checksum=d143936f473a4cb24e3b0e247d6d0775769d55ec9775c339540e753059a8d77a ;;
    *) die "OpenJDK $JDK_VERSION 暂不支持当前架构: $(uname -m)" ;;
  esac
  jdk_name="OpenJDK17U-jdk_${jdk_arch}_linux_hotspot_${JDK_VERSION}_${JDK_BUILD}.tar.gz"
  jdk_cache_dir="$CACHE_DIR/toolchains"
  jdk_archive="$jdk_cache_dir/$jdk_name"
  JDK_HOME="$jdk_cache_dir/temurin-${JDK_VERSION}-${jdk_arch}"
  if [ -x "$JDK_HOME/bin/java" ] && "$JDK_HOME/bin/java" -version 2>&1 | head -n 1 | grep -Fq '17.0.20'; then
    log "Temurin OpenJDK $JDK_VERSION 已存在，跳过下载: $JDK_HOME"
    return 0
  fi
  mkdir -p "$jdk_cache_dir"
  if [ -f "$jdk_archive" ]; then
    actual_checksum="$(sha256sum "$jdk_archive" | awk '{print $1}')"
    if [ "$actual_checksum" != "$jdk_checksum" ]; then
      warn "OpenJDK 缓存校验失败，将重新下载: $jdk_archive"
      rm -f "$jdk_archive"
    fi
  fi
  if [ ! -f "$jdk_archive" ]; then
    official_url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${JDK_VERSION}%2B${JDK_BUILD}/$jdk_name"
    cn_url="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/${jdk_arch}/linux/$jdk_name"
    if [ -n "${AID_JDK_DOWNLOAD_URL:-}" ]; then
      jdk_urls="${AID_JDK_DOWNLOAD_URL}
$cn_url
$official_url"
    elif [ "$RESOLVED_DEPENDENCY_REGION" = cn ]; then
      jdk_urls="$cn_url
$official_url"
    else
      jdk_urls="$official_url
$cn_url"
    fi
    downloaded=no
    old_ifs="$IFS"; IFS='
'
    for jdk_url in $jdk_urls; do
      [ -n "$jdk_url" ] || continue
      log "下载 Temurin OpenJDK $JDK_VERSION（$jdk_arch）: $jdk_url"
      if download_file "$jdk_url" "$jdk_archive" "$jdk_checksum"; then
        actual_checksum="$(sha256sum "$jdk_archive" | awk '{print $1}')"
        if [ "$actual_checksum" = "$jdk_checksum" ]; then
          downloaded=yes
          break
        fi
        warn "OpenJDK 下载文件 SHA256 不匹配，拒绝使用并尝试备用地址"
        rm -f "$jdk_archive"
      else
        warn 'OpenJDK 当前下载地址不可用，尝试备用地址'
      fi
    done
    IFS="$old_ifs"
    [ "$downloaded" = yes ] || die "Temurin OpenJDK $JDK_VERSION 下载失败；国内镜像和官方地址均不可用"
  fi
  jdk_tmp="$JDK_HOME.tmp.$$"
  rm -rf -- "$jdk_tmp"
  mkdir -p "$jdk_tmp"
  if ! tar -xzf "$jdk_archive" -C "$jdk_tmp" --strip-components=1; then
    rm -rf -- "$jdk_tmp"
    die 'OpenJDK 压缩包解压失败'
  fi
  if [ ! -x "$jdk_tmp/bin/java" ] || ! "$jdk_tmp/bin/java" -version 2>&1 | head -n 1 | grep -Fq '17.0.20'; then
    rm -rf -- "$jdk_tmp"
    die 'OpenJDK 实际版本不是17.0.20'
  fi
  rm -rf -- "$JDK_HOME"
  mv "$jdk_tmp" "$JDK_HOME"
  log "Temurin OpenJDK $JDK_VERSION 已校验并就绪: $JDK_HOME"
}

prepare_jdk_runtime_image() {
  [ "$USE_DOCKER" = yes ] || return 0
  base_image='debian:bookworm-slim'
  if docker image inspect "$JAVA_RUNTIME_IMAGE" >/dev/null 2>&1 \
      && docker run --rm "$JAVA_RUNTIME_IMAGE" java -version 2>&1 | head -n 1 | grep -Fq '17.0.20'; then
    log "OpenJDK $JDK_VERSION 运行镜像已存在: $JAVA_RUNTIME_IMAGE"
    return 0
  fi
  ensure_docker_image "$base_image" 'OpenJDK运行基础'
  runtime_dockerfile="$CACHE_DIR/toolchains/Dockerfile.openjdk-$JDK_VERSION"
  cat > "$runtime_dockerfile" <<EOF
FROM $base_image
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:\${PATH}
COPY . /opt/java/openjdk/
RUN java -version
EOF
  log "使用已校验JDK构建固定Java运行镜像: $JAVA_RUNTIME_IMAGE"
  docker build --pull=false --tag "$JAVA_RUNTIME_IMAGE" --file "$runtime_dockerfile" "$JDK_HOME"
  docker run --rm "$JAVA_RUNTIME_IMAGE" java -version 2>&1 | head -n 1 | grep -Fq '17.0.20' \
    || die 'OpenJDK运行镜像版本校验失败'
}

prepare_runtime_images() {
  [ "$USE_DOCKER" = yes ] || return 0
  prepare_jdk_runtime_image
  ensure_docker_image 'node:22.22.0-alpine' 'Web运行时'
  ensure_docker_image 'nginx:1.25-alpine' 'Nginx网关'
  ensure_docker_image 'docker:27-cli' '升级器Docker客户端'
}

prepare_build_images() {
  [ "$USE_DOCKER" = yes ] || return 0
  ensure_docker_image "$MAVEN_IMAGE" 'Maven构建基础'
  ensure_docker_image "$NODE_IMAGE" 'Node.js 22.22.0构建'
  ensure_docker_image "$GO_IMAGE" 'Go构建'
}

docker_maven_build() {
  settings_file="$1"
  docker run --rm --user "$uid_gid" \
    -v "$SERVER_DIR:/workspace" -v "$CACHE_DIR/m2:/cache/m2" \
    -v "$settings_file:/tmp/settings.xml:ro" \
    -v "$JDK_HOME:/opt/aid-jdk:ro" -w /workspace "$MAVEN_IMAGE" sh -lc \
    'export JAVA_HOME=/opt/aid-jdk; export PATH="$JAVA_HOME/bin:$PATH"; \
     java -version 2>&1 | head -n 1 | grep -F "17.0.20" >/dev/null \
       || { echo "[失败] Maven未使用OpenJDK 17.0.20" >&2; exit 1; }; \
     exec mvn -s /tmp/settings.xml -Dmaven.repo.local=/cache/m2 clean package -DskipTests'
}

read_project_npm_version() {
  source_dir="$1"; label="$2"
  package_file="$source_dir/package.json"
  [ -f "$package_file" ] || die "$label 缺少 package.json"
  npm_version="$(sed -n 's/^[[:space:]]*"packageManager"[[:space:]]*:[[:space:]]*"npm@\([^"]*\)".*/\1/p' \
    "$package_file" | head -n 1)"
  if ! printf '%s\n' "$npm_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    die "$label 必须在 package.json 的 packageManager 中固定完整 npm 版本（例如 npm@10.9.4）"
  fi
  printf '%s\n' "$npm_version"
}

docker_npm_build() {
  source_dir="$1"; cache_dir="$2"; label="$3"; npm_version="$4"; selected_registry="$NPM_REGISTRY"
  log "[构建][$label][依赖] npm@$npm_version ci，首选源: $NPM_REGISTRY"
  if ! docker run --rm --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
      -e "AID_NPM_VERSION=$npm_version" \
      -e "npm_config_registry=$NPM_REGISTRY" -e npm_config_cache=/cache/npm \
      -v "$source_dir:/workspace" -v "$cache_dir:/cache/npm" \
      -w /workspace "$NODE_IMAGE" sh -lc \
      '[ "$(node -v)" = v22.22.0 ] || { echo "[失败] Node.js实际版本不是22.22.0" >&2; exit 1; }; \
       exec npm exec --yes "--package=npm@$AID_NPM_VERSION" -- npm ci'; then
    warn "$label npm 依赖从首选源安装失败，切换备用源: $NPM_REGISTRY_FALLBACK"
    selected_registry="$NPM_REGISTRY_FALLBACK"
    docker run --rm --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
      -e "AID_NPM_VERSION=$npm_version" \
      -e "npm_config_registry=$selected_registry" -e npm_config_cache=/cache/npm \
      -v "$source_dir:/workspace" -v "$cache_dir:/cache/npm" \
      -w /workspace "$NODE_IMAGE" sh -lc \
      '[ "$(node -v)" = v22.22.0 ] || exit 1; \
       npm exec --yes "--package=npm@$AID_NPM_VERSION" -- npm ci' \
      || die "$label npm@$npm_version ci 失败；如日志出现 EUSAGE/Missing，请同步提交 package.json 与 package-lock.json"
  fi
  log "[构建][$label][编译] npm@$npm_version run build，使用源: $selected_registry"
  docker run --rm --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
    -e "AID_NPM_VERSION=$npm_version" \
    -e "npm_config_registry=$selected_registry" -e npm_config_cache=/cache/npm \
    -v "$source_dir:/workspace" -v "$cache_dir:/cache/npm" \
    -w /workspace "$NODE_IMAGE" sh -lc \
    'exec npm exec --yes "--package=npm@$AID_NPM_VERSION" -- npm run build'
  log "[构建][$label][完成] 生产构建成功"
}

host_npm_build() {
  source_dir="$1"; cache_dir="$2"; label="$3"; npm_version="$4"; selected_registry="$NPM_REGISTRY"
  log "[构建][$label][依赖] npm@$npm_version ci，首选源: $NPM_REGISTRY"
  if ! (cd "$source_dir" && npm_config_registry="$NPM_REGISTRY" npm_config_cache="$cache_dir" \
      npm exec --yes "--package=npm@$npm_version" -- npm ci); then
    warn "$label npm 依赖从首选源安装失败，切换备用源: $NPM_REGISTRY_FALLBACK"
    selected_registry="$NPM_REGISTRY_FALLBACK"
    (cd "$source_dir" && npm_config_registry="$selected_registry" npm_config_cache="$cache_dir" \
      npm exec --yes "--package=npm@$npm_version" -- npm ci) \
      || die "$label npm@$npm_version ci 失败；如日志出现 EUSAGE/Missing，请同步提交 package.json 与 package-lock.json"
  fi
  log "[构建][$label][编译] npm@$npm_version run build，使用源: $selected_registry"
  (cd "$source_dir" && NUXT_TELEMETRY_DISABLED=1 npm_config_registry="$selected_registry" \
    npm_config_cache="$cache_dir" npm exec --yes "--package=npm@$npm_version" -- npm run build)
  log "[构建][$label][完成] 生产构建成功"
}

require_local_build_tools() {
  command -v mvn >/dev/null 2>&1 || die '源码构建需要 Maven 3.8+'
  command -v node >/dev/null 2>&1 || die '源码构建需要 Node.js 22+'
  command -v npm >/dev/null 2>&1 || die '源码构建需要 npm'
  command -v go >/dev/null 2>&1 || die '源码构建升级器需要 Go 1.22+'
  node_major="$(node -v 2>/dev/null | sed -E 's/v([0-9]+).*/\1/')"
  case "$node_major" in
    ''|*[!0-9]*) die '无法识别 Node.js 版本，请安装 Node.js 22+' ;;
  esac
  [ "$node_major" -ge 22 ] || die "Node.js版本过低（当前${node_major}，需要22+）"
}

build_with_docker() {
  uid_gid="$(id -u):$(id -g)"
  log "[构建][服务端][开始] Temurin OpenJDK $JDK_VERSION + Maven，国内主源: $MAVEN_MIRROR_URL"
  if ! docker_maven_build "$WORK_DIR/maven-settings.xml"; then
    warn "Maven 从首选仓库构建失败，切换备用仓库: $MAVEN_MIRROR_FALLBACK_URL"
    docker_maven_build "$WORK_DIR/maven-settings-fallback.xml"
  fi
  log '[构建][服务端][完成] aid-admin.jar 构建成功'

  log "[构建][后台管理端][开始] Node.js 22.22.0 + npm@$ADMIN_NPM_VERSION"
  docker_npm_build "$ADMIN_DIR" "$CACHE_DIR/npm-admin" '后台管理端' "$ADMIN_NPM_VERSION"

  log "[构建][Web用户端][开始] Node.js 22.22.0 + npm@$WEB_NPM_VERSION"
  docker_npm_build "$WEB_DIR" "$CACHE_DIR/npm-web" 'Web用户端' "$WEB_NPM_VERSION"

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
  log "[构建][服务端][开始] 隔离 Temurin OpenJDK $JDK_VERSION + Maven，国内主源: $MAVEN_MIRROR_URL"
  if ! (cd "$SERVER_DIR" && JAVA_HOME="$JDK_HOME" PATH="$JDK_HOME/bin:$PATH" \
      mvn -s "$WORK_DIR/maven-settings.xml" -Dmaven.repo.local="$CACHE_DIR/m2" clean package -DskipTests); then
    warn "Maven 从首选仓库构建失败，切换备用仓库: $MAVEN_MIRROR_FALLBACK_URL"
    (cd "$SERVER_DIR" && JAVA_HOME="$JDK_HOME" PATH="$JDK_HOME/bin:$PATH" \
      mvn -s "$WORK_DIR/maven-settings-fallback.xml" -Dmaven.repo.local="$CACHE_DIR/m2" clean package -DskipTests)
  fi
  log '[构建][服务端][完成] aid-admin.jar 构建成功'
  log "[构建][后台管理端][开始] 使用服务器本机 Node.js + npm@$ADMIN_NPM_VERSION"
  host_npm_build "$ADMIN_DIR" "$CACHE_DIR/npm-admin" '后台管理端' "$ADMIN_NPM_VERSION"
  log "[构建][Web用户端][开始] 使用服务器本机 Node.js + npm@$WEB_NPM_VERSION"
  host_npm_build "$WEB_DIR" "$CACHE_DIR/npm-web" 'Web用户端' "$WEB_NPM_VERSION"
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
  "dependencyRegion": "$RESOLVED_DEPENDENCY_REGION",
  "sourceRef": "$TAG",
  "jdkVersion": "$JDK_VERSION",
  "nodeVersion": "22.22.0",
  "adminNpmVersion": "$ADMIN_NPM_VERSION",
  "webNpmVersion": "$WEB_NPM_VERSION",
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
  # 根目录成员使用规范名称（installer/...），避免不同 tar 实现对 ./installer
  # 与 installer 的精确匹配行为不一致；aid.sh 仍兼容旧包中的 ./ 前缀。
  (cd "$STAGING_DIR" && tar -czf "$output_part" *)
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
  if [ "$FORGE" = auto ] && [ "$SOURCE_FORGE" = gitee ]; then
    warn 'Gitee 探测成功但拉取中断，清理未完成源码后整组重试 GitHub 备用源'
    probe_forge "$GITHUB_BASE" || die "GitHub 三端源码标签 $TAG 不完整或网络不可达"
    SOURCE_BASE="$GITHUB_BASE"
    SOURCE_FORGE=github
    prepare_dependency_mirrors
    clone_release_set || die "从 GitHub 备用源拉取三端源码失败"
  elif [ "$FORGE" = auto ] && [ "$SOURCE_FORGE" = github ]; then
    warn 'GitHub 备用源探测成功但拉取中断，重新检查 Gitee 主源'
    probe_forge "$GITEE_BASE" || die "Gitee 与 GitHub 均无法稳定提供三端源码标签 $TAG"
    SOURCE_BASE="$GITEE_BASE"
    SOURCE_FORGE=gitee
    prepare_dependency_mirrors
    clone_release_set || die "从 Gitee 主源拉取三端源码失败"
  else
    die "从 $SOURCE_FORGE 拉取三端源码失败"
  fi
fi

ADMIN_NPM_VERSION="$(read_project_npm_version "$ADMIN_DIR" '后台管理端')"
WEB_NPM_VERSION="$(read_project_npm_version "$WEB_DIR" 'Web用户端')"
log "前端锁定工具链: 后台管理端 npm@$ADMIN_NPM_VERSION，Web用户端 npm@$WEB_NPM_VERSION"

mkdir -p "$STAGING_DIR/updater" "$CACHE_DIR/m2" "$CACHE_DIR/npm-admin" \
  "$CACHE_DIR/npm-web" "$CACHE_DIR/go-build" "$CACHE_DIR/go-mod"
detect_dependency_region
prepare_dependency_mirrors
prepare_exact_jdk
if [ "$USE_DOCKER" = yes ]; then
  prepare_build_images
  prepare_runtime_images
  build_with_docker
else
  build_with_host
fi
assemble_package
