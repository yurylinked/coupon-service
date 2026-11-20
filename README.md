# Coupon service

REST service for discount coupons: create a coupon, redeem it, and keep the limits exact under concurrent load.
Java 25, Spring Boot 4.1, PostgreSQL 18, Spock and Testcontainers.

> **TL;DR (PL)** — Serwis REST do kuponów rabatowych. Kod jest unikalny bez względu na wielkość liter, limit użyć
> działa według zasady „kto pierwszy, ten lepszy” także przy wielu instancjach, kraj sprawdzam po adresie IP, a jeden
> użytkownik może użyć kuponu tylko raz. Poprawność pod obciążeniem zapewnia jedno atomowe `UPDATE` w PostgreSQL,
> a testy pokazują, że naiwna implementacja w tym samym wyścigu przekracza limit. Uruchomienie: `docker compose up`,
> a potem `./scripts/prove.sh` — 200 równoległych żądań na dwie instancje za nginx, dokładnie 10 przyznanych użyć.

## Run it

```bash
docker compose up --build -d     # PostgreSQL, two app replicas, nginx on :8080
./scripts/prove.sh               # 200 parallel redemptions of a 10-use coupon through nginx
```

`prove.sh` fails unless exactly `maxUses` redemptions succeed, every other request gets an answer, no request ends in
5xx, and the admin operations are closed at the public edge. A `503` with `Retry-After` is retried the way a
well-behaved client would. CI runs the same script on every push.

```
201 x 10, 409 x 190, 5xx x 0, currentUses = 10
Through the public edge: DELETE redemption -> 403, PATCH coupon -> 403
PASS: exactly 10 redemptions granted, every other request refused, admin operations closed at the edge
```

Tests: `./gradlew test` (unit, seconds) and `./gradlew integrationTest` (Testcontainers, needs Docker).

## API

| Method and path | Purpose | Success |
|---|---|---|
| `POST /api/v1/coupons` | create `{code, maxUses, country, validFrom?, expiresAt?}` | `201` + `Location` |
| `GET /api/v1/coupons/{code}` | read, code in any letter case | `200` |
| `PATCH /api/v1/coupons/{code}` | `{"active": false}` suspends, `true` resumes (internal) | `200` |
| `POST /api/v1/redemptions` | redeem `{code, userId}`, optional `Idempotency-Key` header | `201` + `Location` |
| `GET /api/v1/redemptions/{id}` | read a redemption, including `cancelledAt` | `200` |
| `DELETE /api/v1/redemptions/{id}` | give the use back when the order is cancelled (internal) | `204` |

Every error is RFC 9457 `application/problem+json` with a stable `errorCode`. The task's error cases map to:

| Situation | Status | `errorCode` |
|---|---|---|
| coupon does not exist | 404 | `COUPON_NOT_FOUND` |
| all uses taken | 409 | `COUPON_EXHAUSTED` |
| request from another country | 403 | `COUPON_COUNTRY_NOT_ALLOWED` (`COUNTRY_UNRESOLVED` if the IP has no country) |
| user already used the coupon | 409 | `COUPON_ALREADY_USED_BY_USER` |
| code taken on create (any case) | 409 | `COUPON_CODE_ALREADY_EXISTS` |
| malformed field | 400 | one code per field: `INVALID_COUPON_CODE`, `INVALID_USER_ID`, `INVALID_COUNTRY_CODE`, ... |
| coupon row contended | 503 + `Retry-After` 1–3 s | `REDEMPTION_BUSY` |

## How each requirement is met

| Requirement | Mechanism | Proven by |
|---|---|---|
| Unique code, case-insensitive | normalised to upper case in `CouponCode`; `UNIQUE` plus a format `CHECK` in the database | `CouponApiIT` (20 concurrent creates of `promo`/`PROMO` → one 201) |
| Max uses, first come first served | one conditional `UPDATE … SET current_uses = current_uses + 1 WHERE current_uses < max_uses … RETURNING id`, backed by `CHECK (current_uses <= max_uses)` | `RedemptionConcurrencyIT` (200 users, 10 uses → exactly 10) and its negative control: `NaiveRedemptionStore`, a check-then-act version, oversells the same race |
| Country by IP | offline DB-IP lookup; client IP from Tomcat's `RemoteIpValve`, trusting only the configured proxy | `ClientIpIT`, `DbIpGeoLocationResolverSpec` |
| One use per user | partial unique index `(coupon_id, user_id) WHERE cancelled_at IS NULL` with `INSERT … ON CONFLICT DO NOTHING` | `RedemptionConcurrencyIT` (one user, 50 parallel requests → one use) |
| Multithreaded production use | per-coupon bulkhead, `lock_timeout` → 503, all invariants in the database, two replicas in CI | `HotCouponBulkheadIT`, `LockTimeoutIT`, `prove.sh` |

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Service
    participant DB as PostgreSQL
    C->>S: POST /redemptions
    S->>DB: read coupon + statement_timestamp()
    S->>S: cheap refusals: unknown, country, already used, expired, suspended, exhausted
    S->>S: bulkhead permit for this coupon (≤ 4 per instance, wait ≤ 200 ms, else 503)
    S->>DB: BEGIN; UPDATE … WHERE current_uses < max_uses … RETURNING id
    Note over DB: row lock; waiters re-check the WHERE on the newest row version
    S->>DB: INSERT usage ON CONFLICT DO NOTHING; COMMIT
    S-->>C: 201, or the refusal the domain names after re-reading the coupon
```

## Design decisions

- **The database is the arbiter.** Under READ COMMITTED a blocked `UPDATE` re-evaluates its `WHERE` on the newest row
  version once the lock holder commits, so one conditional statement grants exactly `max_uses` without
  `SERIALIZABLE`, optimistic retries or an application lock. The checks before it only refuse cheaply. They are
  safe without the lock because a refusal they give was true when read; only the `UPDATE` can grant a use.
- **The domain names the reason, the SQL decides.** When the `UPDATE` finds nothing, the service re-reads the coupon
  and asks `Coupon.rejectionAt` why. If no rule rejects any more, a use came back in the meantime and the client gets
  `503` to retry rather than a false `COUPON_EXHAUSTED`. The rejection types form a sealed hierarchy, so a new
  outcome does not compile until it has an HTTP status and a metric tag.
- **One clock: the database.** Expiry, start of validity, `used_at` and `cancelled_at` all use PostgreSQL's
  `statement_timestamp()`. The time is read together with the coupon and passed to the domain as a plain `Instant`,
  so the rules stay pure and unit-testable while replicas with skewed clocks still agree, and
  `CHECK (cancelled_at >= used_at)` holds.
- **JdbcClient, not JPA.** Correctness rests on two statements, the conditional `UPDATE … RETURNING` and
  `INSERT … ON CONFLICT`. Keeping them as visible SQL is the point; an ORM would hide exactly what needs reviewing.
- **One hot row, not Redis or sharded counters.** An exact first-come-first-served limit needs one source of truth in
  the same transaction as the usage record. The cost is a throughput ceiling of roughly one redemption per lock hold
  time for a single coupon. If one coupon ever outgrows that, the next step is a pool of pre-generated use rows
  claimed with `FOR UPDATE SKIP LOCKED`, which keeps the limit exact.
- **Bulkhead per instance.** Each instance allows at most 4 connections on one coupon's row lock (the holder plus
  waiters) and turns the rest away within 200 ms. A flash sale on one coupon therefore cannot take the whole
  connection pool from every other coupon. `HotCouponBulkheadIT` shows a cold coupon served while a hot row is
  locked; the `benchmarkTest` Gradle task measures the same scenario without the bulkhead (pool starvation).
- **Fail fast instead of queueing.** `lock_timeout` (2 s, via `set_config`) and the bulkhead answer `503` with a
  randomised `Retry-After` of 1–3 s, so clients turned away by the same stall do not return in one wave.
- **First come first served means first to commit.** A client turned away with `503` can lose to a later request.
  That is the deliberate price of protecting the pool; arrival order across instances is not observable anyway.
- **Errors per field, once.** Formats live in value objects (`CouponCode`, `UserId`, `CountryCode`, `IdempotencyKey`,
  `RedemptionId`). A structurally invalid value is `400`; a valid but unknown one is `404`.

## Beyond the task, and why

These follow how promotions work in large Polish e-commerce, where a code bound to an order is given back when the
order is cancelled.

- **Cancellation** (`DELETE /redemptions/{id}`): returns the use to the pool unless the coupon has expired, keeps the
  row for the record (`cancelled_at`) and lets the user redeem again. It is idempotent, so the order system can
  retry. It locks the coupon row first, in the same order as a redemption, so the two queue instead of deadlocking.
  Twenty parallel cancellations of one redemption decrement exactly once.
- **Idempotency-Key**: a client that times out and retries gets the original redemption (`201`,
  `Idempotent-Replayed: true`) instead of `COUPON_ALREADY_USED_BY_USER` for a discount it actually received. The
  key is scoped to a coupon and a user, so an order id can carry several coupons. A unique index
  `(coupon_id, user_id, idempotency_key)` covers cancelled rows too, so a stale retry after a cancellation is
  refused (`REDEMPTION_CANCELLED`) and can never redeem twice. A deterministic test holds one request inside the
  geo lookup while its twin is redeemed and cancelled.
- **Start of validity and a kill switch** (`validFrom`, `PATCH {"active": false}`): campaigns that start at a set
  time, and a way to stop a code that leaked to a deal site without deleting it. Both are enforced in the same
  atomic `UPDATE`.

## Operations

- Management port 8081: `/actuator/health/{liveness,readiness}` and `/actuator/prometheus`, not exposed by nginx.
- Metrics `coupon.redemptions{outcome}` and `coupon.cancellations{outcome}` (success, replayed, exhausted, busy, …),
  never tagged by coupon code, plus HTTP latency histograms. Structured ECS logs in the container.
- Unknown database failures are `500` and logged; a lost connection or transaction is `503` with `Retry-After`.
- Graceful shutdown, non-root image, health checks, Hikari pool 10 with a 2 s connection timeout, virtual threads.

## Security and privacy boundaries

- The service enforces business invariants, not identity. It expects to sit behind an authenticated checkout
  backend that supplies the user id; authentication was out of scope for the task.
- The nginx edge lets only `GET` and `POST` through. Cancelling and suspending are for internal callers on the
  private network; in production they would also need service-to-service authentication.
- The client IP is trusted only from the configured proxy CIDR (`TRUSTED_PROXIES`); an empty value trusts nobody.
  Country by IP is targeting, not security: a VPN defeats it.
- Only the resolved country and the user id are stored. The application logs neither user ids nor IP addresses.
  How long redemption records are kept is a business and legal decision.

## Trade-offs I would revisit in a real deployment

- The schema is a single migration because nothing has been deployed. On a live table each change would be
  expand/contract: `ADD CONSTRAINT … NOT VALID` then `VALIDATE`, and `CREATE INDEX CONCURRENTLY` in its own
  non-transactional migration (`executeInTransaction=false`) with
  `spring.flyway.postgresql.transactional-lock=false`, because Flyway's default transaction-level advisory lock
  makes a concurrent index build wait forever.
- Compose uses a static geo provider: behind Docker NAT every client has a private address. The IP path is covered
  by `ClientIpIT` and the DB-IP resolver spec instead.
- The geo database is committed (`geo/dbip-country-lite.mmdb`) so builds are reproducible and do not depend on
  db-ip.com. `scripts/download-geo-db.sh` refreshes it; in production it would be mounted from a volume refreshed
  monthly.
- With more time: publish redemptions to Kafka through an outbox, rate limiting at the gateway, user id from the
  token, a dashboard, and fault injection (killing a replica) in the CI run.

## How I used AI

I used Claude Code as a pair programmer and reviewer. It drafted code and tests, and I had separate agents review
the result independently: concurrency, security, code quality and an "interviewer" pass. Every finding was checked
against the code, and every bug was first reproduced with a failing test. Two examples where the first answer was
wrong: the first version of the idempotency replay missed a window between the key lookup and the already-used
check, and a later review found that a stalled request could reuse a key after its twin was cancelled; both are now
covered by deterministic tests. The design decisions above are mine and I can defend each of them.

## Attribution

IP geolocation by [DB-IP](https://db-ip.com), DB-IP Lite database licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
