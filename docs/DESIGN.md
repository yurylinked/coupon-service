# Design notes

## Request flow

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
    S->>S: refusals from the snapshot: expired, suspended, not yet valid, exhausted
    S->>S: bulkhead permit for this coupon's stripe, else 429
    S->>DB: BEGIN; UPDATE … WHERE current_uses < max_uses … RETURNING id, statement_timestamp()
    Note over DB: row lock; waiters re-check the WHERE on the newest row version
    S->>DB: INSERT redemption ON CONFLICT DO NOTHING; COMMIT
    S-->>C: 201, or the refusal the domain names after re-reading the coupon
```

## Decisions

### PostgreSQL decides who gets a use

Under READ COMMITTED a blocked `UPDATE` re-evaluates its `WHERE` on the newest row version once the lock holder
commits. One conditional statement therefore grants exactly `max_uses`, with no `SERIALIZABLE`, optimistic retries
or application lock. The checks before it can only refuse, and a refusal they give was true when they read the row.

When the `UPDATE` grants nothing, the service re-reads the coupon and asks `Coupon.rejectionAt` why. If no rule
refuses any more, a use came back in the meantime and the client gets `429` to retry, not a false
`COUPON_EXHAUSTED`. A state no rule explains, such as a coupon that disappeared, is a bug and ends as `500`.

### One clock

The creation, redemption and cancellation timestamps, and the instant every validity rule is judged at, come from
PostgreSQL's `statement_timestamp()`. The time is read with the coupon and passed to the domain as an `Instant`, so
the rules stay unit-testable and replicas with skewed clocks agree. `redeemed_at` is the instant at which the granting
`UPDATE` judged validity, so a redemption never looks later than the expiry it passed. If the database clock steps
back, `cancelled_at` is held at `redeemed_at` rather than breaking the `CHECK` that orders them.

### SQL in plain sight

Correctness rests on the conditional `UPDATE … RETURNING` and on `INSERT … ON CONFLICT`. JdbcClient keeps both
visible. An ORM's dirty checking would write an absolute counter value, which is exactly the lost update the naive
store in the tests demonstrates.

### One hot row

An exact first-come-first-served limit needs one source of truth in the same transaction as the redemption record.
The cost is a ceiling of roughly one redemption per lock hold time for a single coupon. Sharding the counter would
raise the ceiling but loses strict ordering near the limit. If one coupon outgrows one row, the next step is a pool
of pre-generated use rows claimed with `FOR UPDATE SKIP LOCKED`. It stays exact, but an empty result can mean
"all taken" or "all briefly locked", so it needs a re-check before answering `COUPON_EXHAUSTED`.

### Bounded waiting

`lock_timeout` is set per transaction with `set_config`, and Hikari passes `statement_timeout` and
`idle_in_transaction_session_timeout` to every connection. A bulkhead limits how many connections one coupon can hold
on its row lock: codes are hashed onto stripes, and each stripe admits a few connections per instance and turns the
rest away within a short wait. The values are in `application.yaml`.

The bulkhead is sized for one or two hot coupons at a time. Several hot coupons together can still fill the pool,
and a cold coupon whose code hashes to a hot coupon's stripe shares its permits. Cancellations use the same stripe
because they take the same row lock. `HotCouponBulkheadIT` shows a cold coupon served while a hot row is locked, and
the `benchmarkTest` task measures the same scenario without the bulkhead.

A request turned away with `429` can lose to a later one, so first come first served means first to commit. That is
the price of protecting the pool; arrival order across instances cannot be observed anyway.

### Validation in one place

Formats and bounds live in the domain (`CouponCode`, `UserId`, `CountryCode`, `IdempotencyKey`, `RedemptionId`, the
use limit in `Coupon`) and are mirrored by `CHECK` constraints. Each field has its own error code. A structurally
invalid value is `400`; a valid but unknown one is `404`. The outcomes form sealed hierarchies, so a new outcome does
not compile until it has an HTTP status and a metric tag.

JSON is bound strictly: a value of the wrong JSON type (`"maxUses": "ten"` or `10.5`) and an unknown field are
`MALFORMED_REQUEST`, because the body was not the documented shape; a value of the right type that breaks a rule gets
the field's own code. An empty string for `validFrom` or `expiresAt` is `MALFORMED_REQUEST` too: a coupon must
not lose its expiry because a form sent a blank field; leave the field out for no expiry.

## Beyond the task

These were added because an order system needs them.

- **Cancellation** (`DELETE /redemptions/{id}`) records `cancelled_at`, returns the use and lets the user redeem
  again. A cancelled order is recorded even after the coupon expired: the use goes back so `current_uses` keeps
  equalling the active rows, and an expired coupon refuses everyone anyway. It is idempotent, so the order system can
  retry. It locks the coupon row first, in the same order as a redemption, so the two queue instead of deadlocking.
- **Idempotency-Key**: a client that times out and retries gets the original redemption (`201`,
  `Idempotent-Replayed: true`) instead of `COUPON_ALREADY_USED_BY_USER`. The key is scoped to a coupon and a user, so
  one order id can carry several coupons. A unique index on `(coupon_id, user_id, idempotency_key)` covers cancelled
  rows too, so a stale retry after a cancellation is refused with `REDEMPTION_CANCELLED`. This differs from the IETF
  Idempotency-Key draft on purpose: there is no "same key, different payload" `422` because the key is bound to the
  request's identity, parallel twins get the one redemption or a retryable `429` instead of a `409`, and keys do not
  expire because the redemptions they point to do not.
- **Start of validity and suspension** (`validFrom`, `PATCH {"active": false}`) cover campaigns that start at a set
  time and codes that leak to deal sites. Both are enforced in the same atomic `UPDATE`.

## Operations

- Management port 8081 serves `/actuator/health/{liveness,readiness}` and `/actuator/prometheus`; nginx does not
  expose it.
- Metrics `coupon.redemptions{outcome}` and `coupon.cancellations{outcome}` are never tagged by coupon code. Logs are
  structured ECS.
- `503` with `Retry-After` means the database could not serve the request right now: no connection within the
  Hikari timeout, a lost connection, a server shutting down or restarting, or a statement past `statement_timeout`.
  Other database failures are `500` and logged.
- nginx answers its own errors as problem+json: `400` for a request it cannot read, `403` for anything but redemption
  on the public edge, `404` and `405` on the internal edge, `413`, and `502`–`504` as `503` with a fixed
  `Retry-After: 2`. It re-resolves the replicas through Docker's DNS, so a recreated container gets traffic without a
  reload.
- `X-Served-By` on the public edge shows which replica answered, so `prove.sh` can check both did. It is demo
  instrumentation.
- Shutdown is graceful within compose's stop grace period. The image runs as a non-root user.

## Security and privacy

- The service enforces business invariants, not identity. It expects an authenticated checkout in front of it to
  supply the user id. The internal edge would need service-to-service authentication in production.
- The client IP is trusted only from the nginx address (`TRUSTED_PROXIES`), and nginx overwrites `X-Forwarded-For`.
  Country by IP is targeting, not security: a VPN defeats it.
- A redemption stores the user id, the resolved country and the optional `Idempotency-Key`, never the IP. The
  application logs neither user ids nor IPs; the demo nginx access log does.

## Trade-offs

- The schema is a single migration because nothing has been deployed. On a live table each change would be
  expand/contract, with `CREATE INDEX CONCURRENTLY` in its own non-transactional migration.
- Compose uses a static geo provider, because behind Docker NAT every client has a private address. `ClientIpIT`
  covers extracting the client address; `DbIpGeoLocationResolverSpec` and `GeoConfigurationSpec` cover the DB-IP
  lookup. The database is committed so builds do not depend on db-ip.com; `scripts/download-geo-db.sh` refreshes it
  and records its month in `geo/dbip-country-lite.month`.
- The SQL is PostgreSQL-specific (`ON CONFLICT … WHERE`, partial indexes, `uuidv7()`). The approach, one conditional
  statement as the arbiter, carries over to other databases.
- With more time: an outbox to Kafka for redemptions, the user id from a token, OpenAPI, tracing, and killing a
  replica during the CI run.
