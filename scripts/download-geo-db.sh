#!/usr/bin/env bash
set -euo pipefail

target="${1:-geo/dbip-country-lite.mmdb}"
current_month="$(date -u +%Y-%m)"
previous_month="$(date -u -v-1m +%Y-%m 2>/dev/null || date -u -d '-1 month' +%Y-%m)"

mkdir -p "$(dirname "$target")"
for month in "$current_month" "$previous_month"; do
  url="https://download.db-ip.com/free/dbip-country-lite-${month}.mmdb.gz"
  if curl -fsSL "$url" | gunzip > "${target}.tmp"; then
    mv "${target}.tmp" "$target"
    echo "DB-IP Country Lite ${month} saved to ${target}"
    exit 0
  fi
done

rm -f "${target}.tmp"
echo "Could not download DB-IP Country Lite for ${current_month} or ${previous_month}" >&2
exit 1
