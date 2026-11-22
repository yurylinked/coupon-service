# Coupon service

REST service for discount coupons: create a coupon, redeem it, and keep the limits exact under concurrent load.
Java 25, Spring Boot 4.1, PostgreSQL 18, Spock and Testcontainers.

> **TL;DR (PL)** — Serwis REST do kuponów rabatowych. Kod jest unikalny bez względu na wielkość liter, limit użyć
> działa według zasady „kto pierwszy, ten lepszy” także przy wielu instancjach, kraj sprawdzam po adresie IP, a jeden
> użytkownik może użyć kuponu tylko raz. Poprawność pod obciążeniem zapewnia jedno atomowe `UPDATE` w PostgreSQL,
> a testy pokazują, że naiwna implementacja w tym samym wyścigu przekracza limit.

The core of the task is two files: `JdbcRedemptionStore.redeem` (one conditional `UPDATE` and one `INSERT` in a
transaction) and `RedeemCouponService` (the checks around it). Design notes and the features beyond the task are in
[docs/DESIGN.md](docs/DESIGN.md).

## Run it

Prerequisites: Docker, and curl 7.84 or newer for `prove.sh`. Gradle needs a JDK 17+ to start and downloads JDK 25
for the build itself.

```bash
docker compose up --build -d --wait   # PostgreSQL, two app replicas, nginx: public :8080, internal 127.0.0.1:8082
./scripts/prove.sh                    # 200 redemptions of a 10-use coupon, 50 at a time, through the public edge
```

`prove.sh` passes only if exactly `maxUses` redemptions succeed, every other request gets `409`, nothing ends in 5xx,
both replicas answered, and the public edge refuses everything except redemption. CI runs it on every push to `main`
and on every pull request:

```
201 x 10, 409 x 190, 5xx x 0, currentUses = 10, instances = 2
Public edge: create 403, read 403, suspend 403, cancel 403
PASS: exactly 10 redemptions granted by 2 instances, every other request refused, only redemption is public
```

Compose's geo provider is static: every client is treated as `${DEMO_COUNTRY:-PL}`. Export the same `DEMO_COUNTRY`
for `docker compose` and `prove.sh`.

Tests: `./gradlew test` (unit, seconds) and `./gradlew integrationTest` (Testcontainers, needs Docker).
`./gradlew check` runs both.

## API

Only `POST /api/v1/redemptions` is public. Everything else is for the back office and the order system and is served
on the internal edge (`127.0.0.1:8082` in compose).

```bash
curl -i -X POST localhost:8082/api/v1/coupons -H 'Content-Type: application/json' \
  -d '{"code":"wiosna","maxUses":2,"country":"PL","expiresAt":"2027-06-30T22:00:00Z"}'
curl -i -X POST localhost:8080/api/v1/redemptions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-1001' -d '{"code":"WIOSNA","userId":"user-1"}'
```

| Method and path | Purpose | Success |
|---|---|---|
| `POST /api/v1/coupons` | create `{code, maxUses, country, validFrom?, expiresAt?}` | `201` + `Location` |
| `GET /api/v1/coupons/{code}` | read, code in any letter case | `200` |
| `PATCH /api/v1/coupons/{code}` | `{"active": false}` suspends, `true` resumes | `200` |
| `POST /api/v1/redemptions` | redeem `{code, userId}`, optional `Idempotency-Key` header | `201` + `Location` |
| `GET /api/v1/redemptions/{id}` | read a redemption, including `cancelledAt` | `200` |
| `DELETE /api/v1/redemptions/{id}` | give the use back when the order is cancelled | `204` |

Errors are RFC 9457 `application/problem+json` with a stable `errorCode`:

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"Coupon has reached its maximum number of uses",
 "instance":"/api/v1/redemptions","errorCode":"COUPON_EXHAUSTED"}
```

| Situation | Status | `errorCode` |
|---|---|---|
| coupon does not exist | 404 | `COUPON_NOT_FOUND` |
| all uses taken | 409 | `COUPON_EXHAUSTED` |
| coupon expired, suspended or not valid yet | 409 | `COUPON_EXPIRED`, `COUPON_SUSPENDED`, `COUPON_NOT_YET_VALID` |
| request from another country | 403 | `COUPON_COUNTRY_NOT_ALLOWED` (`COUNTRY_UNRESOLVED` if the IP has no country) |
| user already used the coupon | 409 | `COUPON_ALREADY_USED_BY_USER` |
| code taken on create (any case) | 409 | `COUPON_CODE_ALREADY_EXISTS` |
| redemption does not exist | 404 | `REDEMPTION_NOT_FOUND` |
| `Idempotency-Key` of a cancelled redemption | 409 | `REDEMPTION_CANCELLED` |
| malformed field | 400 | one code per field: `INVALID_COUPON_CODE`, `INVALID_USER_ID`, `INVALID_MAX_USES`, ... |
| unparsable JSON, wrong JSON type, unknown field | 400 | `MALFORMED_REQUEST` |
| coupon contended (bulkhead or row lock) | 429 + `Retry-After` 1–3 s | `REDEMPTION_BUSY` |
| database unavailable (no connection within 2 s, connection lost, server restarting, statement timeout) | 503 + `Retry-After` 1–3 s | `SERVICE_UNAVAILABLE` |

Contracts worth knowing:

- `expiresAt` is exclusive and needs an offset (`2027-06-30T22:00:00Z` is midnight in Warsaw in summer), so DST is
  the caller's explicit choice.
- Send one `Idempotency-Key` per payment attempt, such as the order id; a retry of the same attempt reuses it.
- `userId` is case-sensitive: `User-1` and `user-1` are different customers.
- Cancelling is always recorded and returns the use, also after the coupon expired.

## How each requirement is met

| Requirement | Mechanism | Proven by |
|---|---|---|
| Unique code, case-insensitive | upper-cased in `CouponCode`; `UNIQUE` plus a format `CHECK` that admits only upper case | `CouponApiIT`: 20 concurrent creates of `promo`/`PROMO`, one `201` |
| Max uses, first come first served | `UPDATE coupon SET current_uses = current_uses + 1 WHERE … current_uses < max_uses … RETURNING`, backed by `CHECK (current_uses <= max_uses)` | `RedemptionConcurrencyIT`: 200 users for 10 uses get exactly 10; a check-then-act store oversells the same race |
| Country by IP | offline DB-IP lookup; client IP from Tomcat's `RemoteIpValve`, trusting only the nginx address | `RedemptionApiIT`, `ClientIpIT`, `DbIpGeoLocationResolverSpec` |
| One use per user | partial unique index `(coupon_id, user_id) WHERE cancelled_at IS NULL` with `INSERT … ON CONFLICT DO NOTHING` | `RedemptionConcurrencyIT`: one user, 50 parallel attempts, one use |
| Production under concurrency | per-coupon bulkhead, `lock_timeout`, statement timeouts, invariants in the database, two replicas | `HotCouponBulkheadIT`, `LockTimeoutIT`, `prove.sh` |

## How I used AI

Claude Code wrote most of the code and tests with me, and separate agents reviewed it without seeing earlier
conclusions. I checked every finding against the code and reproduced every bug with a failing test first. Some of the
calls I made along the way:

- The first version took time from a JVM `Clock`. I moved every comparison to PostgreSQL's `statement_timestamp()`,
  because two replicas with skewed clocks could disagree about whether a coupon had expired.
- An agent first answered contention with `503`. I changed it to `429`: one hot coupon is not an outage, and `503`
  would page whoever watches the 5xx rate.
- A reviewer proposed collapsing the per-outcome exceptions into one class with an enum. I kept the sealed types,
  because the compiler then refuses any outcome without an HTTP status and a metric tag.

## Attribution

IP geolocation by [DB-IP](https://db-ip.com), DB-IP Lite database licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
