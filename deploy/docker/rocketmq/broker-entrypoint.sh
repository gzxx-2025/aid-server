#!/bin/sh
set -eu

BASE_CONFIG=/home/rocketmq/conf/broker.conf
RUNTIME_CONFIG=/tmp/aid-broker.conf
ACL_CONFIG="${ROCKETMQ_HOME}/conf/plain_acl.yml"
ACCESS_KEY="${ROCKETMQ_ACCESS_KEY:-}"
SECRET_KEY="${ROCKETMQ_SECRET_KEY:-}"
FLUSH_DISK_TYPE="${ROCKETMQ_FLUSH_DISK_TYPE:-ASYNC_FLUSH}"

cp "${BASE_CONFIG}" "${RUNTIME_CONFIG}"

case "${FLUSH_DISK_TYPE}" in
    ASYNC_FLUSH|SYNC_FLUSH) ;;
    *)
        echo "ROCKETMQ_FLUSH_DISK_TYPE 只支持 ASYNC_FLUSH 或 SYNC_FLUSH" >&2
        exit 1
        ;;
esac
sed -i "s/^flushDiskType[[:space:]]*=.*/flushDiskType = ${FLUSH_DISK_TYPE}/" "${RUNTIME_CONFIG}"
echo "RocketMQ Broker 刷盘模式: ${FLUSH_DISK_TYPE}"

if [ -n "${ACCESS_KEY}" ] || [ -n "${SECRET_KEY}" ]; then
    if [ -z "${ACCESS_KEY}" ] || [ -z "${SECRET_KEY}" ]; then
        echo "RocketMQ ACL AccessKey 与 SecretKey 必须同时配置" >&2
        exit 1
    fi
    if ! printf '%s:%s\n' "${ACCESS_KEY}" "${SECRET_KEY}" | grep -Eq '^[A-Za-z0-9]+:[A-Za-z0-9]+$'; then
        echo "RocketMQ ACL 凭证仅允许字母和数字" >&2
        exit 1
    fi

    # RocketMQ 5.3.1 的 Remoting 客户端使用 ACL 1.0 签名协议。ACL 文件只在
    # 容器运行时生成且权限为 600，不写入仓库，也不持久化明文凭证。
    umask 077
    cat > "${ACL_CONFIG}" <<EOF
globalWhiteRemoteAddresses:
  - 127.0.0.1
accounts:
  - accessKey: ${ACCESS_KEY}
    secretKey: ${SECRET_KEY}
    admin: false
    defaultTopicPerm: PUB|SUB
    defaultGroupPerm: PUB|SUB
EOF
    printf '\naclEnable = true\n' >> "${RUNTIME_CONFIG}"
    echo "RocketMQ Broker ACL 已启用"
else
    echo "RocketMQ Broker ACL 未启用（仅适用于可信内网）"
fi

exec sh mqbroker -c "${RUNTIME_CONFIG}"
