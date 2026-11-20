# Coupon service

REST service for discount coupons: create a coupon, redeem it, and keep the limits exact under concurrent load.
Java 25, Spring Boot 4.1, PostgreSQL 18, Spock and Testcontainers.

> **TL;DR (PL)** — Serwis REST do kuponów rabatowych. Kod jest unikalny bez względu na wielkość liter, limit użyć
> działa według zasady „kto pierwszy, ten lepszy” także przy wielu instancjach, kraj sprawdzam po adresie IP, a jeden
> użytkownik może użyć kuponu tylko raz. Poprawność pod obciążeniem zapewnia jedno atomowe `UPDATE` w PostgreSQL,
> a testy pokazują, że naiwna implementacja w tym samym wyścigu przekracza limit. Uruchomienie:
> `docker compose up --build -d --wait`, a potem `./scripts/prove.sh` — 200 żądań (po 50 równolegle) na dwie instancje
> za nginx, dokładnie 10 przyznanych użyć.

## Run it

```bash
docker compose up --build -d --wait   # PostgreSQL, two app replicas, nginx: public :8080, internal 127.0.0.1:8082
./scripts/prove.sh                    # 200 redemptions of a 10-use coupon, 50 at a time, through the public edge
```

`prove.sh` creates the coupon through the internal edge and fails unless exactly `maxUses` redemptions succeed, every
other request is refused with `409`, no request ends in 5xx, both replicas answered, and the public edge refuses
everything except redemption. A `429` or `503` with `Retry-After` is retried the way a well-behaved client would.
CI runs the same script on every push to `main` and on every pull request. Last lines of its output:

```
201 x 10, 409 x 190, 5xx x 0, currentUses = 10, instances = 2
Public edge: create 403, read 403, suspend 403, cancel 403
PASS: exactly 10 redemptions granted by 2 instances, every other request refused, only redemption is public
```

Tests: `./gradlew test` (unit, seconds) and `./gradlew integrationTest` (Testcontainers, needs Docker).

## API

Only `POST /api/v1/redemptions` is public. The rest is for the back office and the order system and is served on
the internal edge (`127.0.0.1:8082` in compose).

| Method and path | Purpose | Success |
|---|---|---|
| `POST /api/v1/coupons` | create `{code, maxUses, country, validFrom?, expiresAt?}` | `201` + `Location` |
| `GET /api/v1/coupons/{code}` | read, code in any letter case | `200` |
| `PATCH /api/v1/coupons/{code}` | `{"active": false}` suspends, `true` resumes | `200` |
| `POST /api/v1/redemptions` | redeem `{code, userId}`, optional `Idempotency-Key` header | `201` + `Location` |
| `GET /api/v1/redemptions/{id}` | read a redemption, including `cancelledAt` | `200` |
| `DELETE /api/v1/redemptions/{id}` | give the use back when the order is cancelled | `204` |

Every error is RFC 9457 `application/problem+json` with a stable `errorCode`, including the ones nginx produces
itself (`403` at the public edge, `413`, `502`–`504`). The task's error cases map to:

| Situation | Status | `errorCode` |
|---|---|---|
| coupon does not exist | 404 | `COUPON_NOT_FOUND` |
| all uses taken | 409 | `COUPON_EXHAUSTED` |
| request from another country | 403 | `COUPON_COUNTRY_NOT_ALLOWED` (`COUNTRY_UNRESOLVED` if the IP has no country) |
| user already used the coupon | 409 | `COUPON_ALREADY_USED_BY_USER` |
| code taken on create (any case) | 409 | `COUPON_CODE_ALREADY_EXISTS` |
| malformed field | 400 | one code per field: `INVALID_COUPON_CODE`, `INVALID_USER_ID`, `INVALID_COUNTRY_CODE`, `INVALID_MAX_USES`, ... |
| missing `active` flag on suspend | 400 | `VALIDATION_FAILED` with `errors[]` |
| unparsable JSON | 400 | `MALFORMED_REQUEST` |
| coupon contended (bulkhead or row lock) | 429 + `Retry-After` 1–3 s | `REDEMPTION_BUSY` |
| database unreachable | 503 + `Retry-After` 1–3 s | `SERVICE_UNAVAILABLE` |

Contention on one coupon is `429`, not `503`: the service is healthy and the client should slow down, and keeping it
out of 5xx keeps it out of availability alerts. `503` is reserved for a lost database.

## How each requirement is met

| Requirement | Mechanism | Proven by |
|---|---|---|
| Unique code, case-insensitive | normalised to upper case in `CouponCode`; `UNIQUE` plus a format `CHECK` in the database | `CouponApiIT` (20 concurrent creates of `promo`/`PROMO` → one 201) |
| Max uses, first come first served | one conditional `UPDATE … SET current_uses = current_uses + 1 WHERE current_uses < max_uses … RETURNING id`, backed by `CHECK (current_uses <= max_uses)` | `RedemptionConcurrencyIT` (200 users at store level, 10 uses → exactly 10; 50 users over HTTP → exactly 10) and its negative control: `NaiveRedemptionStore`, a check-then-act version with a simulated 20 ms of work between check and write, oversells the same race |
| Country by IP | offline DB-IP lookup; client IP from Tomcat's `RemoteIpValve`, trusting only the configured proxy | `RedemptionApiIT` (wrong country → 403), `ClientIpIT`, `DbIpGeoLocationResolverSpec` |
| One use per user | partial unique index `(coupon_id, user_id) WHERE cancelled_at IS NULL` with `INSERT … ON CONFLICT DO NOTHING` | `RedemptionConcurrencyIT` (one user, 50 parallel attempts → one use) |
| Multithreaded production use | per-coupon bulkhead, `lock_timeout` → 429, statement and idle-in-transaction timeouts, all invariants in the database, two replicas in CI | `HotCouponBulkheadIT`, `LockTimeoutIT`, `RedemptionConcurrencyIT` over HTTP, `prove.sh` |

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Service
    participant DB as PostgreSQL
    C->>S: POST /redemptions
    S->>DB: read coupon + statement_timestamp()
    S->>DB: Idempotency-Key seen before? then replay the original 201
    S->>S: country by IP
    S->>DB: has this user an active redemption?
    S->>S: cheap refusals: expired, suspended, not yet valid, exhausted
    S->>S: bulkhead permit for this coupon's stripe (≤ 4 per instance, wait ≤ 200 ms, else 429)
    S->>DB: BEGIN; UPDATE … WHERE current_uses < max_uses … RETURNING id, statement_timestamp()
    Note over DB: row lock; waiters re-check the WHERE on the newest row version
    S->>DB: INSERT usage (used_at = that grant time) ON CONFLICT DO NOTHING; COMMIT
    S-->>C: 201, or the refusal the domain names after re-reading the coupon
```

## Design decisions

- **The database is the arbiter.** Under READ COMMITTED a blocked `UPDATE` re-evaluates its `WHERE` on the newest row
  version once the lock holder commits, so one conditional statement grants exactly `max_uses` without
  `SERIALIZABLE`, optimistic retries or an application lock. The checks before it only refuse cheaply. They are
  safe without the lock because a refusal they give was true when read; only the `UPDATE` can grant a use.
- **The domain names the reason, the SQL decides.** When the `UPDATE` finds nothing, the service re-reads the coupon
  and asks `Coupon.rejectionAt` why. If no rule rejects any more, a use came back in the meantime and the client gets
  `429` to retry rather than a false `COUPON_EXHAUSTED`. A state no rule explains (the coupon vanished) is a bug and
  ends as `500`, not as a retry the client would repeat forever. The rejection types form a sealed hierarchy, so a
  new outcome does not compile until it has an HTTP status and a metric tag.
- **One clock: the database.** Creation time, expiry, start of validity, `used_at` and `cancelled_at` all use
  PostgreSQL's `statement_timestamp()`. The time is read together with the coupon and passed to the domain as a plain
  `Instant`, so the rules stay pure and unit-testable while replicas with skewed clocks still agree. `used_at` is the
  very instant the granting `UPDATE` judged validity at, so a redemption never looks later than the expiry it passed.
  `CHECK (cancelled_at >= used_at)` relies on the database server's clock not stepping backwards; if it ever did,
  the cancel would fail loudly on the constraint rather than store a negative interval.
- **JdbcClient, not JPA.** Correctness rests on two statements, the conditional `UPDATE … RETURNING` and
  `INSERT … ON CONFLICT`. Keeping them as visible SQL is the point; an ORM would hide exactly what needs reviewing.
- **One hot row, not Redis or sharded counters.** An exact first-come-first-served limit needs one source of truth in
  the same transaction as the usage record. The cost is a throughput ceiling of roughly one redemption per lock hold
  time for a single coupon. Splitting the counter into shards would raise that ceiling but loses strict
  first-come-first-served near the limit. If one coupon ever outgrows one row, the next step is a pool of
  pre-generated use rows claimed with `FOR UPDATE SKIP LOCKED`, which keeps the limit exact; its catch is that an empty
  result means "all taken" or "all briefly locked", so it needs a re-check before answering `COUPON_EXHAUSTED`.
- **Bulkhead per instance.** Codes are hashed onto 1024 stripes, and each stripe allows at most 4 connections per
  instance on its row locks (the holder plus waiters), turning the rest away within 200 ms. A flash sale on one
  coupon therefore cannot take the whole connection pool from every other coupon. It is sized for one or two hot
  coupons at a time; several hot coupons together can still fill the pool of 10. Cancellations share the same
  stripe. `HotCouponBulkheadIT` shows a cold coupon served while a hot row is locked; the `benchmarkTest` Gradle task
  measures the same scenario without the bulkhead (pool starvation).
- **Fail fast instead of queueing.** `lock_timeout` (2 s, via `set_config`) and the bulkhead answer `429` with a
  randomised `Retry-After` of 1–3 s, so clients turned away by the same stall do not return in one wave. A
  `statement_timeout` of 5 s and an `idle_in_transaction_session_timeout` of 10 s bound anything else that would
  hold a connection.
- **First come first served means first to commit.** A client turned away with `429` can lose to a later request.
  That is the deliberate price of protecting the pool; arrival order across instances is not observable anyway.
- **Errors per field, once.** Formats and bounds live in the domain (`CouponCode`, `UserId`, `CountryCode`,
  `IdempotencyKey`, `RedemptionId`, and the 1–1 000 000 use limit in `Coupon`), mirrored by database `CHECK`s. A
  structurally invalid value is `400`; a valid but unknown one is `404`.

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
  geo lookup while its twin is redeemed and cancelled. Deliberate differences from the IETF Idempotency-Key draft:
  the key is bound to a coupon and a user, so there is no "same key, different payload" `422`; parallel twins wait
  on the database and both get the one redemption instead of a `409`; keys do not expire, because the redemption
  they point to does not either.
- **Start of validity and a kill switch** (`validFrom`, `PATCH {"active": false}`): campaigns that start at a set
  time, and a way to stop a code that leaked to a deal site without deleting it. Both are enforced in the same
  atomic `UPDATE`.

## Operations

- Management port 8081: `/actuator/health/{liveness,readiness}` and `/actuator/prometheus`, not exposed by nginx.
- Metrics `coupon.redemptions{outcome}` and `coupon.cancellations{outcome}` (success, replayed, exhausted, busy, …),
  never tagged by coupon code, plus HTTP latency histograms. Structured ECS logs in the container.
- Unknown database failures are `500` and logged; a lost connection or transaction is `503` with `Retry-After`.
- Graceful shutdown (15 s, inside compose's 20 s stop grace period), non-root image, health checks, Hikari pool 10
  with a 2 s connection timeout, virtual threads.
- `X-Served-By` on the public edge exposes the replica's address so `prove.sh` can show both replicas answered. It
  is demo instrumentation and would not ship.

## Security and privacy boundaries

- The service enforces business invariants, not identity. It expects to sit behind an authenticated checkout
  backend that supplies the user id; authentication was out of scope for the task.
- The public nginx edge passes only `POST /api/v1/redemptions`. Creating, reading, suspending and cancelling are on
  the internal edge, published on localhost only; in production they would also need service-to-service
  authentication.
- The client IP is trusted only from the configured proxy CIDR (`TRUSTED_PROXIES`), and nginx overwrites
  `X-Forwarded-For` with the peer address, so a client cannot forge it. Country by IP is targeting, not security:
  a VPN defeats it.
- Stored per redemption: the user id, the resolved country and the optional `Idempotency-Key`; never the IP. The
  application logs neither user ids nor IP addresses (the demo nginx access log does). How long redemption records
  are kept is a business and legal decision.

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
