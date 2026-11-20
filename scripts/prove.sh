#!/usr/bin/env bash
set -euo pipefail

export BASE_URL="${BASE_URL:-http://localhost:8080}"
admin_url="${ADMIN_URL:-http://localhost:8082}"
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
    if [[ "$status" != "429" && "$status" != "503" ]]; then
      break
    fi
    sleep "${retry_after:-1}"
  done
  echo "${status} ${served} ${attempt}"
}
export -f redeem

status_of() {
  curl -s -o /dev/null -w '%{http_code}' "$@"
}

echo "Creating coupon ${CODE} with ${max_uses} uses through the internal edge"
create_status="$(status_of -X POST "${admin_url}/api/v1/coupons" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"${CODE}\",\"maxUses\":${max_uses},\"country\":\"${DEMO_COUNTRY:-PL}\"}")"
if [[ "$create_status" != "201" ]]; then
  echo "Coupon creation failed with HTTP ${create_status}" >&2
  exit 1
fi

echo "Firing ${requests} redemptions through the public edge, ${parallelism} at a time, each from a different user"
echo "A 429 or 503 is retried after its Retry-After, like a well-behaved client would"
seq 1 "$requests" | xargs -P "$parallelism" -I{} bash -c 'redeem {}' >> "$results"

current_uses="$(curl -s "${admin_url}/api/v1/coupons/${CODE}" | grep -o '"currentUses":[0-9]*' | cut -d: -f2)"
created="$(grep -c '^201 ' "$results" || true)"
rejected="$(grep -c '^409 ' "$results" || true)"
server_errors="$(grep -c '^5' "$results" || true)"
retried="$(awk '$3 > 1' "$results" | wc -l | tr -d ' ')"
instances="$(cut -d' ' -f2 "$results" | sort -u | grep -c . || true)"

public_create="$(status_of -X POST "${BASE_URL}/api/v1/coupons" -H 'Content-Type: application/json' \
  -d '{"code":"PUBLIC","maxUses":1,"country":"PL"}')"
public_read="$(status_of "${BASE_URL}/api/v1/coupons/${CODE}")"
public_suspend="$(status_of -X PATCH "${BASE_URL}/api/v1/coupons/${CODE}" -H 'Content-Type: application/json' \
  -d '{"active":false}')"
public_cancel="$(status_of -X DELETE "${BASE_URL}/api/v1/redemptions/00000000-0000-7000-8000-000000000000")"

echo
echo "Final responses by status:"
cut -d' ' -f1 "$results" | sort | uniq -c | sed 's/^/  /'
echo "Final responses by instance:"
cut -d' ' -f2 "$results" | sort | uniq -c | sed 's/^/  /'
echo "Requests that were told to retry first: ${retried}"
echo
echo "201 x ${created}, 409 x ${rejected}, 5xx x ${server_errors}, currentUses = ${current_uses}, instances = ${instances}"
echo "Public edge: create ${public_create}, read ${public_read}, suspend ${public_suspend}, cancel ${public_cancel}"

if [[ "$created" == "$max_uses" && "$current_uses" == "$max_uses" && "$server_errors" == "0" \
      && "$((created + rejected))" == "$requests" && "$instances" -ge 2 \
      && "$public_create" == "403" && "$public_read" == "403" \
      && "$public_suspend" == "403" && "$public_cancel" == "403" ]]; then
  echo "PASS: exactly ${max_uses} redemptions granted by ${instances} instances, every other request refused, only redemption is public"
else
  echo "FAIL: expected exactly ${max_uses} redemptions over at least 2 instances, ${requests} answers, no server errors, and 403 for everything but redemption on the public edge" >&2
  exit 1
fi
