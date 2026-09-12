#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

STAGING_DIR="${TMP_ROOT}/staging"
mkdir -p "${STAGING_DIR}/backend" "${STAGING_DIR}/sql"
printf 'new-version\n' > "${STAGING_DIR}/backend/aid-admin.jar"
printf 'SELECT 2;\n' > "${STAGING_DIR}/sql/v-test.sql"

PLAIN_PACKAGE="${TMP_ROOT}/plain.tar.gz"
WRAPPED_PACKAGE="${TMP_ROOT}/wrapped.tar.gz"
(cd "${STAGING_DIR}" && tar -czf "${PLAIN_PACKAGE}" *)
mkdir -p "${TMP_ROOT}/wrapped/aid-v-test"
cp -a "${STAGING_DIR}/." "${TMP_ROOT}/wrapped/aid-v-test/"
(cd "${TMP_ROOT}/wrapped" && tar -czf "${WRAPPED_PACKAGE}" aid-v-test)

assert_sql_staging_preserves_installed_artifacts() {
  local package="$1"
  rm -rf "${DATA_ROOT}/packages"
  mkdir -p "${DATA_ROOT}/app"
  printf 'currently-running-version\n' > "${DATA_ROOT}/app/aid-admin.jar"
  stage_release_sql "${package}"
  [[ "$(cat "${DATA_ROOT}/packages/pending-sql/v-test.sql")" == 'SELECT 2;' ]]
  [[ "$(cat "${DATA_ROOT}/app/aid-admin.jar")" == 'currently-running-version' ]]
}

assert_sql_staging_preserves_installed_artifacts "${PLAIN_PACKAGE}"
assert_sql_staging_preserves_installed_artifacts "${WRAPPED_PACKAGE}"

migration_order="$({
  sql_migration_sort_key '/tmp/v1.0.0.sql'
  sql_migration_sort_key '/tmp/v1.0.0-beta.10.sql'
  sql_migration_sort_key '/tmp/v1.0.0-rc.2.sql'
  sql_migration_sort_key '/tmp/v1.0.0-beta.2.sql'
  sql_migration_sort_key '/tmp/v2.0.0.sql'
} | sort -t '|' -k1,1 | awk -F '|' '{sub(".*/", "", $2); print $2}')"
expected_order="$(printf '%s\n' \
  v1.0.0-beta.2.sql \
  v1.0.0-beta.10.sql \
  v1.0.0-rc.2.sql \
  v1.0.0.sql \
  v2.0.0.sql)"
[[ "${migration_order}" == "${expected_order}" ]] \
  || { echo "FAIL: migration order is not SemVer order: ${migration_order}" >&2; exit 1; }

stage_line="$(grep -n '^[[:space:]]*stage_release_sql "${package}"' "${ROOT_DIR}/deploy/aid.sh" | tail -n 1 | cut -d: -f1)"
history_line="$(grep -n '^[[:space:]]*if ! run_sql_dir_with_history "${DATA_ROOT}/packages/pending-sql"' "${ROOT_DIR}/deploy/aid.sh" | tail -n 1 | cut -d: -f1)"
place_line="$(grep -n '^[[:space:]]*place_artifacts "${package}"' "${ROOT_DIR}/deploy/aid.sh" | tail -n 1 | cut -d: -f1)"
[[ -n "${stage_line}" && -n "${history_line}" && -n "${place_line}" \
    && "${stage_line}" -lt "${history_line}" && "${history_line}" -lt "${place_line}" ]] \
  || { echo 'FAIL: update flow must stage and execute SQL before replacing artifacts' >&2; exit 1; }

grep -Fq 'restore_database_clean "${backupDir}/db.sql.gz" "${schemaFingerprint}"' "${ROOT_DIR}/deploy/aid.sh" \
  || { echo 'FAIL: failed migration must use clean restore with schema verification' >&2; exit 1; }
grep -Fq 'mark_sql_baseline_from_dir "${REPO_DIR}/sql"' "${ROOT_DIR}/deploy/aid.sh" \
  || { echo 'FAIL: fresh installs must record the migration baseline' >&2; exit 1; }

echo 'upgrade SQL-before-artifacts tests passed'
