#!/usr/bin/env bash
set -euo pipefail

export BASE_URL="${BASE_URL:-http://localhost:8080}"
requests="${REQUESTS:-200}"
max_uses="${MAX_USES:-10}"
parallelism="${PARALLELISM:-50}"
export CODE="PROVE-$(date +%s)"
results="$(mktemp)"
trap 'rm -f "$results"' EXIT

redeem() {
  local user="$1" attempt status served retry_after
  for attempt in 1 2 3 4 5; do
    read -r status served retry_after < <(curl -s -o /dev/null \
      -w '%{http_code} %header{x-served-by} %header{retry-after}\n' \
      -X POST "${BASE_URL}/api/v1/redemptions" \
      -H 'Content-Type: application/json' \
      -d "{\"code\":\"${CODE}\",\"userId\":\"user-${user}\"}")
    if [[ "$status" != "503" ]]; then
      break
    fi
    sleep "${retry_after:-1}"
  done
  echo "${status} ${served} ${attempt}"
}
export -f redeem

echo "Creating coupon ${CODE} with ${max_uses} uses"
create_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/coupons" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"${CODE}\",\"maxUses\":${max_uses},\"country\":\"${DEMO_COUNTRY:-PL}\"}")"
if [[ "$create_status" != "201" ]]; then
  echo "Coupon creation failed with HTTP ${create_status}" >&2
  exit 1
fi

echo "Firing ${requests} redemptions, ${parallelism} at a time, each from a different user"
echo "A 503 is retried after its Retry-After, like a well-behaved client would"
seq 1 "$requests" | xargs -P "$parallelism" -I{} bash -c 'redeem {}' >> "$results"

current_uses="$(curl -s "${BASE_URL}/api/v1/coupons/${CODE}" | grep -o '"currentUses":[0-9]*' | cut -d: -f2)"
created="$(grep -c '^201 ' "$results" || true)"
rejected="$(grep -c '^409 ' "$results" || true)"
server_errors="$(grep -c '^5' "$results" || true)"
retried="$(awk '$3 > 1' "$results" | wc -l | tr -d ' ')"

echo
echo "Final responses by status:"
cut -d' ' -f1 "$results" | sort | uniq -c | sed 's/^/  /'
echo "Final responses by instance:"
cut -d' ' -f2 "$results" | sort | uniq -c | sed 's/^/  /'
echo "Requests that got 503 + Retry-After first and were retried: ${retried}"
echo
echo "201 x ${created}, 409 x ${rejected}, 5xx x ${server_errors}, currentUses = ${current_uses}"

if [[ "$created" == "$max_uses" && "$current_uses" == "$max_uses" && "$server_errors" == "0" ]]; then
  echo "PASS: exactly ${max_uses} redemptions granted, no request left failing"
else
  echo "FAIL: expected exactly ${max_uses} redemptions and no server errors" >&2
  exit 1
fi
