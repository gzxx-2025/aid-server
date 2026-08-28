#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

build_log="${TMP_ROOT}/source-build.log"
for line in $(seq 1 200); do printf '普通日志 %s\n' "${line}"; done > "${build_log}"
printf '[ERROR] UNIQUE-SOURCE-BUILD-FAILURE\n' >> "${build_log}"
output="$(source_build_failure_diagnostics "${build_log}" 2>&1)"
[[ "${output}" == *'UNIQUE-SOURCE-BUILD-FAILURE'* && "${output}" == *"${build_log}"* ]] \
  || { echo 'FAIL: source build failure must print the useful log tail and full path' >&2; exit 1; }

builder_body="$(declare -f bootstrap_source_builder)"
[[ "${builder_body}" == *'for sourceRef in master "v${RESOLVED_VERSION}"'* ]] \
  || { echo 'FAIL: installer must prefer the latest public source builder' >&2; exit 1; }

builder_file="${ROOT_DIR}/deploy/build-release-from-source.sh"
count="$(grep -c -- '--no-transfer-progress' "${builder_file}")"
[[ "${count}" -ge 3 ]] \
  || { echo 'FAIL: every Maven build path must suppress transfer progress noise' >&2; exit 1; }
if grep -Eq 'git[[:space:]]+-C([[:space:]]|$)' "${builder_file}"; then
  echo 'FAIL: source builder must remain compatible with CentOS 7 Git 1.8.3.1' >&2
  exit 1
fi

# 只加载构建器的 Git 拉取函数，用本地裸仓验证标签解析与失败分类，
# 避免触发构建器其他资源预检和正式构建步骤。
clone_function_block="$(awk '
  /^git_tag_records\(\) \{/ { capture=1 }
  /^prepare_dependency_mirrors\(\) \{/ { capture=0 }
  capture { print }
' "${builder_file}")"
eval "${clone_function_block}"

fixture_source="${TMP_ROOT}/source-fixture"
fixture_forge="${TMP_ROOT}/fixture-forge"
fixture_bare="${fixture_forge}/aid-server.git"
mkdir -p "${fixture_source}/deploy" "${fixture_source}/frontend/admin" \
  "${fixture_source}/frontend/web" "${fixture_forge}"
printf '<project/>\n' > "${fixture_source}/pom.xml"
printf '#!/bin/sh\n' > "${fixture_source}/deploy/build-release-from-source.sh"
printf '{"name":"admin"}\n' > "${fixture_source}/frontend/admin/package.json"
printf '{"lockfileVersion":3}\n' > "${fixture_source}/frontend/admin/package-lock.json"
printf '{"name":"web"}\n' > "${fixture_source}/frontend/web/package.json"
printf '{"lockfileVersion":3}\n' > "${fixture_source}/frontend/web/package-lock.json"
(
  cd "${fixture_source}"
  git init -q
  git config user.name 'AID Test'
  git config user.email 'aid-test@example.invalid'
  git add pom.xml deploy/build-release-from-source.sh \
    frontend/admin/package.json frontend/admin/package-lock.json \
    frontend/web/package.json frontend/web/package-lock.json
  git commit -q -m 'fixture complete source tree'
  git tag -a vclone-test -m 'annotated fixture tag'
)
git clone -q --bare "${fixture_source}" "${fixture_bare}"
fixture_commit="$(cd "${fixture_source}" && git rev-parse HEAD)"
fixture_tag_object="$(cd "${fixture_source}" && git rev-parse refs/tags/vclone-test)"

USE_DOCKER=no
GIT_IMAGE='alpine/git:test'
TAG=vclone-test
SOURCE_BASE="${fixture_forge}"
SOURCE_FORGE=gitee
SOURCE_PROBE_OK=yes
SERVER_REPO=aid-server
WORK_DIR="${TMP_ROOT}/host-clone-work"
mkdir -p "${WORK_DIR}/repos"
required_source_paths=(
  pom.xml
  deploy/build-release-from-source.sh
  frontend/admin/package.json
  frontend/admin/package-lock.json
  frontend/web/package.json
  frontend/web/package-lock.json
)
clone_release_body="$(declare -f clone_release_set)"
for required_source_path in "${required_source_paths[@]}"; do
  [[ "${clone_release_body}" == *"${required_source_path}"* ]] \
    || { echo "FAIL: clone_release_set does not enforce ${required_source_path}" >&2; exit 1; }
done

resolved_fixture_commit="$(resolve_remote_tag_commit "${fixture_bare}")"
[[ "${fixture_tag_object}" != "${fixture_commit}" && "${resolved_fixture_commit}" == "${fixture_commit}" ]] \
  || { echo 'FAIL: annotated tag must resolve to its peeled commit' >&2; exit 1; }

# 模拟 git clone 返回成功但工作树丢失 package.json。校验必须先将其归类为
# 本地检出不完整，再清理并使用同一 Gitee 地址的显式 tag fetch 修复。
eval "$(declare -f clone_tag_shortcut | sed '1s/clone_tag_shortcut/original_clone_tag_shortcut/')"
clone_tag_shortcut() {
  original_clone_tag_shortcut "$@" || return 1
  rm -f "$2/frontend/admin/package.json" "$2/frontend/web/package.json"
}
retry_output=''
if ! retry_output="$(clone_repo aid-server "${WORK_DIR}/repos/server" \
    "${required_source_paths[@]}" 2>&1)"; then
  printf '%s\n' "${retry_output}" >&2
  echo 'FAIL: same-forge explicit tag retry did not repair an incomplete worktree' >&2
  exit 1
fi
grep -Fq '本地工作树检出不完整' <<< "${retry_output}" \
  || { echo 'FAIL: incomplete worktree was not diagnosed separately' >&2; exit 1; }
grep -Fq 'gitee 显式拉取 refs/tags/vclone-test 并 detached checkout，不切换源' <<< "${retry_output}" \
  || { echo 'FAIL: Gitee did not retry the exact tag before forge fallback' >&2; exit 1; }
[[ -f "${WORK_DIR}/repos/server/frontend/admin/package.json" \
    && -f "${WORK_DIR}/repos/server/frontend/admin/package-lock.json" \
    && -f "${WORK_DIR}/repos/server/frontend/web/package.json" \
    && -f "${WORK_DIR}/repos/server/frontend/web/package-lock.json" ]] \
  || { echo 'FAIL: explicit tag retry did not restore required package files' >&2; exit 1; }
[[ "$(cd "${WORK_DIR}/repos/server" && git rev-parse HEAD)" == "${fixture_commit}" ]] \
  || { echo 'FAIL: repaired checkout HEAD does not match the annotated tag commit' >&2; exit 1; }
eval "$(declare -f original_clone_tag_shortcut | sed '1s/original_clone_tag_shortcut/clone_tag_shortcut/')"
unset -f original_clone_tag_shortcut

# select_forge 和后续 ls-remote 都暂时不可用时，仍必须在 Gitee 同源完成
# 一次精确 refspec fetch；fetch 成功后用本地 tag^{commit} 校验 HEAD/tree/工作树。
eval "$(declare -f probe_forge | sed '1s/probe_forge/original_probe_forge/')"
probe_forge() { return 1; }
FORGE=auto
GITEE_BASE="${fixture_forge}"
GITHUB_BASE="${TMP_ROOT}/unused-github"
select_output_file="${TMP_ROOT}/select-forge.log"
select_forge > "${select_output_file}" 2>&1
select_output="$(<"${select_output_file}")"
[[ "${SOURCE_FORGE}" == gitee && "${SOURCE_BASE}" == "${fixture_forge}" \
    && "${SOURCE_PROBE_OK}" == no ]] \
  || { echo 'FAIL: failed Gitee probe switched forge before same-source explicit retry' >&2; exit 1; }
grep -Fq '复核失败后才切换 GitHub' <<< "${select_output}" \
  || { echo 'FAIL: failed Gitee probe did not explain same-source retry order' >&2; exit 1; }
eval "$(declare -f original_probe_forge | sed '1s/original_probe_forge/probe_forge/')"
unset -f original_probe_forge

eval "$(declare -f resolve_remote_tag_commit | sed '1s/resolve_remote_tag_commit/original_resolve_remote_tag_commit/')"
resolve_remote_tag_commit() { return 1; }
TAG=vclone-test
probe_down_output=''
if ! probe_down_output="$(clone_repo aid-server "${WORK_DIR}/repos/probe-down" \
    "${required_source_paths[@]}" 2>&1)"; then
  printf '%s\n' "${probe_down_output}" >&2
  echo 'FAIL: exact same-forge fetch was not attempted after repeated tag-probe failure' >&2
  exit 1
fi
grep -Fq '远程标签探测不可用，但精确标签检出成功' <<< "${probe_down_output}" \
  || { echo 'FAIL: degraded probe success was not recorded clearly' >&2; exit 1; }
[[ "$(cd "${WORK_DIR}/repos/probe-down" && git rev-parse HEAD)" == "${fixture_commit}" ]] \
  || { echo 'FAIL: probe-down exact fetch did not check out the expected tag commit' >&2; exit 1; }
eval "$(declare -f original_resolve_remote_tag_commit | sed '1s/original_resolve_remote_tag_commit/resolve_remote_tag_commit/')"
unset -f original_resolve_remote_tag_commit
SOURCE_PROBE_OK=yes

# 远程 tag 的 tree 本身缺文件时，同源显式重试也必须失败，且诊断不能
# 冒充为“本地检出不完整”；最终失败目录应被幂等清理。
(
  cd "${fixture_source}"
  git rm -q frontend/admin/package.json
  git commit -q -m 'fixture incomplete remote tree'
  git tag -a vclone-broken -m 'annotated incomplete tag'
  git push -q "${fixture_bare}" refs/tags/vclone-broken
)
TAG=vclone-broken
broken_output=''
if broken_output="$(clone_repo aid-server "${WORK_DIR}/repos/broken" \
    "${required_source_paths[@]}" 2>&1)"; then
  echo 'FAIL: remote tag missing a required file was accepted' >&2
  exit 1
fi
grep -Fq 'HEAD tree 不包含关键文件 frontend/admin/package.json' <<< "${broken_output}" \
  || { echo 'FAIL: incomplete remote tag tree lacks a precise diagnostic' >&2; exit 1; }
grep -Fq 'remote-tree-missing' <<< "${broken_output}" \
  || { echo 'FAIL: incomplete remote tag tree lacks a stable failure category' >&2; exit 1; }
if grep -Fq '本地工作树检出不完整' <<< "${broken_output}"; then
  echo 'FAIL: remote tag content failure was misclassified as a local checkout failure' >&2
  exit 1
fi
[[ ! -e "${WORK_DIR}/repos/broken" ]] \
  || { echo 'FAIL: failed checkout directory was not cleaned idempotently' >&2; exit 1; }

# 本机无 Docker Engine，用 fake docker 覆盖 alpine/git 路由的命令契约和校验命令。
# 这里不声称已完成真实容器网络/文件系统集成验证。
docker_log="${TMP_ROOT}/fake-docker.log"
: > "${docker_log}"
fake_tag_object='1111111111111111111111111111111111111111'
fake_tag_commit='2222222222222222222222222222222222222222'
fake_blob='3333333333333333333333333333333333333333'
docker() {
  printf 'docker %s\n' "$*" >> "${docker_log}"
  case " $* " in
    *' ls-remote --exit-code '*)
      printf '%s\trefs/tags/vdocker\n' "${fake_tag_object}"
      printf '%s\trefs/tags/vdocker^{}\n' "${fake_tag_commit}"
      ;;
    *' rev-parse --verify refs/tags/vdocker^{commit} '*) printf '%s\n' "${fake_tag_commit}" ;;
    *' rev-parse --verify HEAD^{commit} '*) printf '%s\n' "${fake_tag_commit}" ;;
    *' symbolic-ref -q HEAD '*) return 1 ;;
    *' ls-tree --full-tree HEAD -- '*)
      docker_last_arg="${!#}"
      printf '100644 blob %s\t%s\n' "${fake_blob}" "${docker_last_arg}"
      ;;
  esac
  return 0
}
command() {
  if [[ "$1" == '-v' && "${2:-}" == 'timeout' ]]; then
    return 1
  fi
  builtin command "$@"
}
USE_DOCKER=yes
GIT_IMAGE='alpine/git:test'
TAG=vdocker
SOURCE_FORGE=github
docker_repo="${TMP_ROOT}/docker-route/repo"
mkdir -p "${docker_repo}/.git" "${docker_repo}/deploy" \
  "${docker_repo}/frontend/admin" "${docker_repo}/frontend/web"
printf '<project/>\n' > "${docker_repo}/pom.xml"
printf '#!/bin/sh\n' > "${docker_repo}/deploy/build-release-from-source.sh"
printf '{}\n' > "${docker_repo}/frontend/admin/package.json"
printf '{}\n' > "${docker_repo}/frontend/admin/package-lock.json"
printf '{}\n' > "${docker_repo}/frontend/web/package.json"
printf '{}\n' > "${docker_repo}/frontend/web/package-lock.json"
[[ "$(resolve_remote_tag_commit 'https://example.invalid/aid-server.git')" == "${fake_tag_commit}" ]] \
  || { echo 'FAIL: Docker tag probe did not use the peeled annotated-tag commit' >&2; exit 1; }
fetch_tag_explicitly 'https://example.invalid/aid-server.git' "${docker_repo}" \
  || { echo 'FAIL: Docker explicit tag route rejected the expected command contract' >&2; exit 1; }
validate_repo_checkout "${docker_repo}" "${fake_tag_commit}" \
  "${required_source_paths[@]}" >/dev/null \
  || { echo 'FAIL: Docker checkout validation did not route through alpine/git' >&2; exit 1; }
unset -f command docker
USE_DOCKER=no
grep -Fq 'ls-remote --exit-code https://example.invalid/aid-server.git refs/tags/vdocker refs/tags/vdocker^{}' "${docker_log}" \
  || { echo 'FAIL: Docker route does not query both tag object and peeled commit' >&2; exit 1; }
grep -Fq 'fetch --depth 1 --no-tags origin refs/tags/vdocker:refs/tags/vdocker' "${docker_log}" \
  || { echo 'FAIL: Docker route does not fetch the exact tag refspec' >&2; exit 1; }
grep -Fq 'checkout --detach refs/tags/vdocker' "${docker_log}" \
  || { echo 'FAIL: Docker route does not use detached tag checkout' >&2; exit 1; }
for required_source_path in "${required_source_paths[@]}"; do
  grep -Fq "ls-tree --full-tree HEAD -- ${required_source_path}" "${docker_log}" \
    || { echo "FAIL: Docker route does not inspect HEAD tree path ${required_source_path}" >&2; exit 1; }
  grep -Fq "cat-file -e HEAD:${required_source_path}" "${docker_log}" \
    || { echo "FAIL: Docker route does not verify Git object ${required_source_path}" >&2; exit 1; }
done

# 后台管理端继续 build；Web 用户端必须静态生成，并按静态入口装配发布包。
grep -Eq 'docker_npm_build "\$WEB_DIR" .* generate$' "${builder_file}" \
  || { echo 'FAIL: Docker Web build must run npm generate' >&2; exit 1; }
grep -Eq 'host_npm_build "\$WEB_DIR" .* generate$' "${builder_file}" \
  || { echo 'FAIL: host Web build must run npm generate' >&2; exit 1; }
grep -Fq '[ -f "$WEB_DIR/dist/public/index.html" ] && [ -f "$WEB_DIR/dist/public/200.html" ]' "${builder_file}" \
  || { echo 'FAIL: source package must require the generated static index and SPA entry' >&2; exit 1; }
grep -Fq 'cp -R "$web_output"/. "$STAGING_DIR/web-dist/"' "${builder_file}" \
  || { echo 'FAIL: generated static contents must be copied into web-dist root' >&2; exit 1; }
grep -Fq 'try_files $uri $uri/ /200.html;' "${ROOT_DIR}/deploy/docker/nginx/web-static.conf" \
  || { echo 'FAIL: Docker static Web must use Nuxt 200.html as SPA fallback' >&2; exit 1; }
[[ "$(grep -Fc 'try_files \$uri \$uri/ /200.html;' "${ROOT_DIR}/deploy/aid.sh")" -eq 2 ]] \
  || { echo 'FAIL: manual HTTP and HTTPS Web sites must use Nuxt 200.html fallback' >&2; exit 1; }

# 同版本旧 SSR 缓存必须在严格校验前被识别为不兼容；补齐当前静态入口和
# 静态容器模板后，才允许进入严格校验与复用路径。
cache_root="${TMP_ROOT}/cache-package"
cache_archive="${TMP_ROOT}/aid-v-cache.tar.gz"
mkdir -p "${cache_root}/backend" "${cache_root}/admin-dist" "${cache_root}/web-dist/server" \
  "${cache_root}/updater" "${cache_root}/installer/deploy/docker/nginx"
printf 'jar\n' > "${cache_root}/backend/aid-admin.jar"
printf '<!doctype html>\n' > "${cache_root}/admin-dist/index.html"
printf 'export default {}\n' > "${cache_root}/web-dist/server/index.mjs"
printf '{"builtBy":"remote-source-build"}\n' > "${cache_root}/build-info.json"
printf 'updater\n' > "${cache_root}/updater/aid-updater_linux_amd64"
printf 'updater\n' > "${cache_root}/updater/aid-updater_linux_arm64"
printf '#!/bin/bash\n' > "${cache_root}/installer/deploy/aid.sh"
printf '#!/bin/sh\n' > "${cache_root}/installer/deploy/build-release-from-source.sh"
printf 'services: {}\n' > "${cache_root}/installer/deploy/docker/docker-compose.yml"
(cd "${cache_root}" && tar -czf "${cache_archive}" ./*)
if source_package_cache_matches_current_contract "${cache_archive}"; then
  echo 'FAIL: legacy SSR source package cache must be rejected before strict validation' >&2
  exit 1
fi
printf '<!doctype html>\n' > "${cache_root}/web-dist/index.html"
printf '<!doctype html>\n' > "${cache_root}/web-dist/200.html"
printf 'server {}\n' > "${cache_root}/installer/deploy/docker/nginx/web-static.conf"
(cd "${cache_root}" && tar -czf "${cache_archive}" ./*)
source_package_cache_matches_current_contract "${cache_archive}" \
  || { echo 'FAIL: current static source package cache should be reusable' >&2; exit 1; }

echo 'source build diagnostics tests passed'
