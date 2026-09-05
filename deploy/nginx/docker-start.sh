#!/bin/sh
set -eu
sh /opt/aid-nginx/bootstrap.sh
if [ "${NGINX_HTTPS_ENTRY:-false}" = true ]; then
  envsubst '${HTTPS_PUBLIC_DOMAIN} ${HTTPS_ADMIN_DOMAIN}' < /etc/nginx/aid-sites/aid-https.conf.template > /etc/nginx/conf.d/default.conf
else
  cp /etc/nginx/aid-sites/aid.conf /etc/nginx/conf.d/default.conf
fi
