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

echo 'source build diagnostics tests passed'
