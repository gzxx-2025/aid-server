#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

fixtureDir="${TMP_ROOT}/fixture"
fixtureArchive="${TMP_ROOT}/aid-updater.tar.gz"
manifest="${TMP_ROOT}/latest.json"
mkdir -p "${fixtureDir}"
cat > "${fixtureDir}/aid-updater" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == "-version" ]] && { echo '1.2.3-beta.1'; exit 0; }
exit 1
EOF
chmod 0755 "${fixtureDir}/aid-updater"
tar -czf "${fixtureArchive}" -C "${fixtureDir}" aid-updater
fixtureSha="$(sha256sum "${fixtureArchive}" | awk '{print $1}')"
payload="{\"updater\":{\"version\":\"1.2.3-beta.1\",\"packages\":{\"linux_amd64\":{\"url\":\"https://example.test/amd64.tar.gz\",\"sha256\":\"${fixtureSha}\"}}}}"
payloadB64="$(printf '%s' "${payload}" | base64 | tr -d '\r\n')"
cat > "${manifest}" <<EOF
{
  "beta": {
    "productVersion": "1.2.3-beta.1",
    "sourceBuild": true,
    "updater": {
      "packages": {
        "linux_amd64": {
          "mirrors": [
            "https://mirror.example.test/amd64.tar.gz"
          ],
          "sha256": "${fixtureSha}",
          "url": "https://example.test/amd64.tar.gz"
        },
        "linux_arm64": {
          "mirrors": [
            "https://mirror.example.test/arm64.tar.gz"
          ],
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "url": "https://example.test/arm64.tar.gz"
        }
      },
      "version": "1.2.3-beta.1"
    }
  },
  "signature": {
    "algorithm": "Ed25519",
    "payload": "${payloadB64}",
    "value": "test"
  },
  "updater": {
    "packages": {
      "linux_amd64": {
        "mirrors": [
          "https://mirror.example.test/stable-amd64.tar.gz"
        ],
        "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "url": "https://example.test/stable-amd64.tar.gz"
      }
    },
    "version": "1.2.3"
  }
}
EOF

[[ "$(json_updater_version "${manifest}" beta)" == '1.2.3-beta.1' ]] \
  || { echo 'FAIL: beta updater version parsing failed' >&2; exit 1; }
[[ "$(json_updater_package_string "${manifest}" beta linux_amd64 url)" == 'https://example.test/amd64.tar.gz' ]] \
  || { echo 'FAIL: updater URL parsing failed' >&2; exit 1; }
[[ "$(json_updater_package_string "${manifest}" beta linux_arm64 mirror)" == 'https://mirror.example.test/arm64.tar.gz' ]] \
  || { echo 'FAIL: updater mirror parsing failed' >&2; exit 1; }
[[ "$(json_updater_version "${manifest}" stable)" == '1.2.3' ]] \
  || { echo 'FAIL: stable updater version parsing failed' >&2; exit 1; }
[[ "$(json_updater_package_string "${manifest}" stable linux_amd64 url)" == 'https://example.test/stable-amd64.tar.gz' ]] \
  || { echo 'FAIL: stable updater URL parsing failed' >&2; exit 1; }

RESOLVED_MANIFEST_PATH="${manifest}"
RESOLVED_CHANNEL=beta
resolve_official_release() { :; }
require_download_tools() { :; }
try_download() {
  cp "${fixtureArchive}" "$2"
}
ensure_official_updater_binary >/dev/null
[[ "$("${DATA_ROOT}/app/updater/aid-updater" -version)" == '1.2.3-beta.1' ]] \
  || { echo 'FAIL: updater binary recovery failed' >&2; exit 1; }

TRACE_FILE="${TMP_ROOT}/trace"
UPDATER_DATA_DIR="${TMP_ROOT}/updater-data"
mkdir -p "${UPDATER_DATA_DIR}"
: > "${TRACE_FILE}"
write_updater_config() { echo config >> "${TRACE_FILE}"; }
ensure_docker_image() { echo "image:$1" >> "${TRACE_FILE}"; }
compose_cmd() { echo "compose:$*" >> "${TRACE_FILE}"; }
wait_docker_container_healthy() { echo "healthy:$1" >> "${TRACE_FILE}"; }
setup_updater docker >/dev/null
expected=$'config\nimage:docker:27-cli\ncompose:up -d aid-updater\ncompose:restart aid-updater\nhealthy:aid-updater'
[[ "$(cat "${TRACE_FILE}")" == "${expected}" ]] \
  || { echo 'FAIL: Docker updater setup sequence is incomplete' >&2; cat "${TRACE_FILE}" >&2; exit 1; }

compose_cmd() { return 1; }
docker_container_diagnostics() { :; }
stop_failed_docker_service() { :; }
if setup_updater docker >/dev/null 2>&1; then
  echo 'FAIL: Docker updater setup must propagate Compose failures' >&2
  exit 1
fi

echo 'updater recovery tests passed'
