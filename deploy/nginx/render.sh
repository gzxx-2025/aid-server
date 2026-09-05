#!/bin/sh
# Render only allowlisted server-context directives. Never evaluate supplied text.
set -eu
kind="${1:?public or admin}"
case "$kind" in public|admin) ;; *) exit 2 ;; esac
export NGINX_BACKEND_ORIGIN="${NGINX_BACKEND_ORIGIN:-http://aid-server:8080}"
export NGINX_MAX_BODY_MB="${NGINX_MAX_BODY_MB:-1024}"
export NGINX_READ_TIMEOUT_SECONDS="${NGINX_READ_TIMEOUT_SECONDS:-300}"
export NGINX_CONNECT_TIMEOUT_SECONDS="${NGINX_CONNECT_TIMEOUT_SECONDS:-10}"
export NGINX_EXTRA_DIRECTIVES="${NGINX_EXTRA_DIRECTIVES:-}"
export NGINX_TLS_DIRECTIVES=''
case "$NGINX_BACKEND_ORIGIN" in
  https:*)
    ca=/etc/ssl/certs/ca-certificates.crt
    if [ "${NGINX_DEPLOY_MODE:-docker}" = systemd ] && [ -f /etc/pki/tls/certs/ca-bundle.crt ]; then ca=/etc/pki/tls/certs/ca-bundle.crt; fi
    NGINX_TLS_DIRECTIVES="proxy_ssl_server_name on; proxy_ssl_verify on; proxy_ssl_trusted_certificate $ca; proxy_ssl_verify_depth 5;"
    export NGINX_TLS_DIRECTIVES
    ;;
esac
awk '
function fail() { print "Invalid Nginx configuration values" > "/dev/stderr"; exit 2 }
BEGIN {
  origin=ENVIRON["NGINX_BACKEND_ORIGIN"]
  if (origin !~ /^https?:\/\/([a-zA-Z0-9][a-zA-Z0-9.-]*|\[[0-9a-fA-F:]+\])(:[0-9]+)?$/ || length(origin)>255) fail()
  port=origin; sub(/^https?:\/\//,"",port)
  if (port ~ /:[0-9]+$/) { sub(/^.*:/,"",port); if((port+0)<1 || (port+0)>65535) fail() }
  keys[1]="NGINX_MAX_BODY_MB"; limits[1]=10240
  keys[2]="NGINX_READ_TIMEOUT_SECONDS"; limits[2]=3600
  keys[3]="NGINX_CONNECT_TIMEOUT_SECONDS"; limits[3]=120
  for(i=1;i<=3;i++){ v=ENVIRON[keys[i]]; if(v !~ /^[1-9][0-9]*$/ || (v+0)>limits[i]) fail() }
  extra=ENVIRON["NGINX_EXTRA_DIRECTIVES"]
  if(length(extra)>2048 || extra ~ /[\r\n]/) fail()
  n=split(extra,parts,";")
  for(i=1;i<=n;i++) {
    v=parts[i]; gsub(/^[ \t]+|[ \t]+$/,"",v)
    if(v=="") continue
    if(i==n) fail()
    if(v !~ /^(gzip (on|off)|gzip_min_length [1-9][0-9]*|(keepalive_timeout|client_body_timeout|send_timeout) [1-9][0-9]*s)$/) fail()
    split(v,words," "); if(seen[words[1]]++) fail()
  }
}
{
  gsub(/\$\{NGINX_BACKEND_ORIGIN\}/,origin)
  for(i=1;i<=3;i++) gsub("\\$\\{" keys[i] "\\}",ENVIRON[keys[i]])
  gsub(/\$\{NGINX_EXTRA_DIRECTIVES\}/,extra)
  gsub(/\$\{NGINX_TLS_DIRECTIVES\}/,ENVIRON["NGINX_TLS_DIRECTIVES"])
  print
}' "$(dirname "$0")/$kind.conf.template"
