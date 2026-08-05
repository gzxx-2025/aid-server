#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

generated="$(gen_database_secret)"
[[ "${generated}" =~ ^[A-Za-z0-9]{12}$ ]] \
  || { echo 'FAIL: generated database password must be 12 alphanumeric characters' >&2; exit 1; }

fakeMysql="${TMP_ROOT}/mysql"
stateFile="${TMP_ROOT}/state"
callLog="${TMP_ROOT}/calls.log"
cat > "${fakeMysql}" <<'EOF'
#!/usr/bin/env bash
set -u
printf '%s|%s\n' "${MYSQL_PWD-}" "$*" >> "${MYSQL_FAKE_LOG}"

if [[ "$*" == *"-uroot"* ]]; then
  if [[ "$*" == *"information_schema.tables"* ]]; then
    [[ ! -f "${MYSQL_FAKE_STATE}" && -z "${MYSQL_PWD-}" ]] || exit 1
    printf '0\n'
    exit 0
  fi
  if [[ "$*" == *"CREATE DATABASE"* ]]; then
    if [[ ! -f "${MYSQL_FAKE_STATE}" && -z "${MYSQL_PWD-}" ]] \
        || [[ -f "${MYSQL_FAKE_STATE}" && "${MYSQL_PWD-}" == "root-secret" ]]; then
      printf 'configured\n' > "${MYSQL_FAKE_STATE}"
      exit 0
    fi
    exit 1
  fi
  if [[ -f "${MYSQL_FAKE_STATE}" ]]; then
    [[ "${MYSQL_PWD-}" == "root-secret" ]]
  else
    [[ -z "${MYSQL_PWD-}" ]]
  fi
  exit $?
fi

[[ -f "${MYSQL_FAKE_STATE}" && "${MYSQL_PWD-}" == "db-secret" \
  && "$*" == *"--database=aid"* && "$*" == *"--user=aid"* ]]
EOF
chmod +x "${fakeMysql}"
export MYSQL_FAKE_STATE="${stateFile}"
export MYSQL_FAKE_LOG="${callLog}"

# 旧脚本中断后留下的 48 位配置尚未写入空数据库时，可安全迁移为 12 位并备份。
legacyDb="$(printf 'D%.0s' {1..48})"
legacyRoot="$(printf 'R%.0s' {1..48})"
mkdir -p "$(dirname "${CONF}")"
printf 'DB_PASSWORD=%s\nMYSQL_ROOT_PASSWORD=%s\n' "${legacyDb}" "${legacyRoot}" > "${CONF}"
normalize_pristine_managed_mysql_credentials "${fakeMysql}" "/data/aid/run/mysql/mysql.sock" \
  127.0.0.1 3306 aid aid "${legacyDb}" "${legacyRoot}" >/dev/null
[[ "${MANAGED_DB_PASSWORD}" =~ ^[A-Za-z0-9]{12}$ \
  && "${MANAGED_ROOT_PASSWORD}" =~ ^[A-Za-z0-9]{12}$ \
  && "${MANAGED_DB_PASSWORD}" != "${MANAGED_ROOT_PASSWORD}" ]] \
  || { echo 'FAIL: pristine legacy credentials were not migrated to distinct 12-char values' >&2; exit 1; }
grep -Fq "DB_PASSWORD=${MANAGED_DB_PASSWORD}" "${CONF}"
grep -Fq "MYSQL_ROOT_PASSWORD=${MANAGED_ROOT_PASSWORD}" "${CONF}"
[[ "$(find "$(dirname "${CONF}")" -maxdepth 1 -name 'aid-deploy.conf.bak.*' | wc -l | tr -d ' ')" == "1" ]] \
  || { echo 'FAIL: legacy config migration did not create exactly one backup' >&2; exit 1; }
: > "${callLog}"

# 全新 initialize-insecure 数据目录：先通过空 root 密码接管，再同步正式配置。
reconcile_managed_mysql_credentials "${fakeMysql}" "/data/aid/run/mysql/mysql.sock" \
  127.0.0.1 3306 aid aid db-secret root-secret >/dev/null
grep -Fq -- '--socket=/data/aid/run/mysql/mysql.sock' "${callLog}" \
  || { echo 'FAIL: managed root connection did not use the managed socket' >&2; exit 1; }
grep -Fq "ALTER USER 'aid'@'127.0.0.1' IDENTIFIED BY 'db-secret'" "${callLog}" \
  || { echo 'FAIL: business password was not synchronized from config' >&2; exit 1; }
grep -Fq "ALTER USER 'root'@'localhost' IDENTIFIED BY 'root-secret'" "${callLog}" \
  || { echo 'FAIL: root password was not synchronized from config' >&2; exit 1; }

# 重复运行仍须重新应用并验证配置，不能只验证业务账号后跳过 root。
: > "${callLog}"
reconcile_managed_mysql_credentials "${fakeMysql}" "/data/aid/run/mysql/mysql.sock" \
  127.0.0.1 3306 aid aid db-secret root-secret >/dev/null
grep -Fq 'root-secret|--connect-timeout=3 --protocol=socket --socket=/data/aid/run/mysql/mysql.sock -uroot -e SELECT 1' "${callLog}" \
  || { echo 'FAIL: configured root credential was not verified on reinstall' >&2; exit 1; }
grep -Fq 'db-secret|--connect-timeout=3 --protocol=TCP --host=127.0.0.1 --port=3306 --database=aid --user=aid -e SELECT 1' "${callLog}" \
  || { echo 'FAIL: configured business credential was not verified on reinstall' >&2; exit 1; }

# root 作为业务账号时两项配置不能互相矛盾。
if ( reconcile_managed_mysql_credentials "${fakeMysql}" "/data/aid/run/mysql/mysql.sock" \
    127.0.0.1 3306 aid root another-secret root-secret ) >/dev/null 2>&1; then
  echo 'FAIL: conflicting root/business passwords were accepted' >&2
  exit 1
fi

echo 'Managed MySQL credential reconciliation tests passed'
