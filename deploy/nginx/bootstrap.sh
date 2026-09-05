#!/bin/sh
# Initial container startup only. Online edits live outside the installer tree.
set -eu
target=/etc/nginx/aid-managed
[ ! -L "$target" ] || exit 1
mkdir -p "$target"
for kind in public admin; do
  [ ! -L "$target/$kind.conf" ] || exit 1
  if [ -e "$target/$kind.conf" ]; then
    [ -f "$target/$kind.conf" ] && grep -qx '# AID_MANAGED_NGINX_INCLUDE=1' "$target/$kind.conf" || exit 1
  fi
  if [ ! -f "$target/$kind.conf" ]; then
    candidate=$(mktemp "$target/.bootstrap-XXXXXX")
    if ! sh /opt/aid-nginx/render.sh "$kind" > "$candidate"; then rm -f "$candidate"; exit 1; fi
    chmod 644 "$candidate"
    mv "$candidate" "$target/$kind.conf"
  fi
done
