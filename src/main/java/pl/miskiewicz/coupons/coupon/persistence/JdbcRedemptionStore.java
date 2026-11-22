package pl.miskiewicz.coupons.coupon.persistence;

import static pl.miskiewicz.coupons.coupon.persistence.Timestamps.instant;
import static pl.miskiewicz.coupons.coupon.persistence.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.IdempotencyKey;
import pl.miskiewicz.coupons.coupon.domain.LockNotAcquiredException;
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionId;
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

// Every write locks the coupon row first, so redemptions and cancellations of one coupon queue instead of
// deadlocking. The SQL predicates decide; the application explains a refusal afterwards.
@Repository
class JdbcRedemptionStore implements RedemptionStore {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String IDEMPOTENCY_KEY_INDEX = "uq_redemption_idempotency_key";
    private static final String SELECT_REDEMPTION = """
            SELECT r.public_id, c.code, r.user_id, r.country_code, r.redeemed_at, r.cancelled_at
            FROM redemption r
            JOIN coupon c ON c.id = r.coupon_id
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final String lockTimeout;

    JdbcRedemptionStore(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RedemptionLockProperties properties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.lockTimeout = properties.lockTimeout().toMillis() + "ms";
    }

    @Override
    public boolean hasRedeemed(CouponCode code, UserId userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM redemption r
                            JOIN coupon c ON c.id = r.coupon_id
                            WHERE c.code = :code AND r.user_id = :userId AND r.cancelled_at IS NULL
                        )
                        """)
                .param("code", code.value())
                .param("userId", userId.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public RedeemAttempt redeem(CouponCode code, UserId userId, CountryCode country, @Nullable IdempotencyKey key) {
        return inTransaction(status -> {
            // Validity is judged at the instant the statement arrived; the same instant becomes redeemed_at.
            Optional<Grant> grant = jdbc.sql("""
                            UPDATE coupon
                            SET current_uses = current_uses + 1
                            WHERE code = :code
                              AND active
                              AND current_uses < max_uses
                              AND (valid_from IS NULL OR valid_from <= statement_timestamp())
                              AND (expires_at IS NULL OR expires_at > statement_timestamp())
                            RETURNING id, statement_timestamp() AS redeemed_at
                            """)
                    .param("code", code.value())
                    .query((rs, rowNum) -> new Grant(rs.getLong("id"), instant(rs, "redeemed_at")))
                    .optional();
            if (grant.isEmpty()) {
                return hasRedeemed(code, userId) ? new RedeemAttempt.AlreadyRedeemed() : new RedeemAttempt.NoUseLeft();
            }
            try {
                Optional<Redemption> inserted = jdbc.sql("""
                                INSERT INTO redemption (coupon_id, user_id, country_code, idempotency_key, redeemed_at)
                                VALUES (:couponId, :userId, :country, :idempotencyKey, :redeemedAt)
                                ON CONFLICT (coupon_id, user_id) WHERE cancelled_at IS NULL DO NOTHING
                                RETURNING public_id, redeemed_at
                                """)
                        .param("couponId", grant.get().couponId())
                        .param("redeemedAt", utc(grant.get().redeemedAt()))
                        .param("userId", userId.value())
                        .param("country", country.value())
                        .param("idempotencyKey", key == null ? null : key.value())
                        .query((rs, rowNum) -> new Redemption(
                                new RedemptionId(rs.getObject("public_id", UUID.class)), code, userId, country,
                                instant(rs, "redeemed_at")))
                        .optional();
                if (inserted.isEmpty()) {
                    status.setRollbackOnly();
                    return new RedeemAttempt.AlreadyRedeemed();
                }
                return new RedeemAttempt.Redeemed(inserted.get());
            } catch (DataAccessException ex) {
                if (!violates(ex, IDEMPOTENCY_KEY_INDEX)) {
                    throw ex;
                }
                // The key was already used by this user for this coupon, in a redemption that has since been cancelled.
                status.setRollbackOnly();
                return new RedeemAttempt.AlreadyRedeemed();
            }
        });
    }

    @Override
    public Optional<RedemptionRecord> findById(RedemptionId redemptionId) {
        return jdbc.sql(SELECT_REDEMPTION + "WHERE r.public_id = :redemptionId")
                .param("redemptionId", redemptionId.value())
                .query((rs, rowNum) -> mapRecord(rs))
                .optional();
    }

    @Override
    public Optional<RedemptionRecord> findByIdempotencyKey(CouponCode code, UserId userId, IdempotencyKey key) {
        return jdbc.sql(SELECT_REDEMPTION
                        + "WHERE c.code = :code AND r.user_id = :userId AND r.idempotency_key = :key")
                .param("code", code.value())
                .param("userId", userId.value())
                .param("key", key.value())
                .query((rs, rowNum) -> mapRecord(rs))
                .optional();
    }

    // The redemption row is updated only after the coupon lock is held, so a concurrent second cancel sees the first.
    // A cancelled order is recorded even after the coupon expired; the use goes back so the counter keeps matching
    // the active rows, and nobody can take it any more. GREATEST keeps the CHECK if the database clock stepped back.
    @Override
    public boolean cancel(RedemptionId redemptionId) {
        return inTransaction(status -> {
            Optional<Long> couponId = jdbc.sql("""
                            SELECT id
                            FROM coupon
                            WHERE id = (SELECT coupon_id FROM redemption WHERE public_id = :redemptionId)
                            FOR NO KEY UPDATE
                            """)
                    .param("redemptionId", redemptionId.value())
                    .query(Long.class)
                    .optional();
            if (couponId.isEmpty()) {
                return false;
            }
            int cancelled = jdbc.sql("""
                            UPDATE redemption r
                            SET cancelled_at = GREATEST(statement_timestamp(), r.redeemed_at)
                            WHERE r.public_id = :redemptionId
                              AND r.cancelled_at IS NULL
                            """)
                    .param("redemptionId", redemptionId.value())
                    .update();
            if (cancelled == 0) {
                return false;
            }
            jdbc.sql("UPDATE coupon SET current_uses = current_uses - 1 WHERE id = :couponId")
                    .param("couponId", couponId.get())
                    .update();
            return true;
        });
    }

    private <T> T inTransaction(TransactionCallback<T> work) {
        try {
            return transactions.execute(status -> {
                jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                        .param("timeout", lockTimeout)
                        .query(String.class)
                        .single();
                return work.doInTransaction(status);
            });
        } catch (DataAccessException ex) {
            if (hasSqlState(ex, LOCK_NOT_AVAILABLE)) {
                throw new LockNotAcquiredException(ex);
            }
            throw ex;
        }
    }

    private static boolean hasSqlState(DataAccessException ex, String sqlState) {
        return ex.getMostSpecificCause() instanceof SQLException sql && sqlState.equals(sql.getSQLState());
    }

    private static boolean violates(DataAccessException ex, String constraint) {
        return ex.getMostSpecificCause() instanceof PSQLException psql
                && psql.getServerErrorMessage() != null
                && constraint.equals(psql.getServerErrorMessage().getConstraint());
    }

    private static RedemptionRecord mapRecord(ResultSet rs) throws SQLException {
        return new RedemptionRecord(
                new Redemption(
                        new RedemptionId(rs.getObject("public_id", UUID.class)),
                        new CouponCode(rs.getString("code")),
                        new UserId(rs.getString("user_id")),
                        new CountryCode(rs.getString("country_code")),
                        instant(rs, "redeemed_at")),
                instant(rs, "cancelled_at"));
    }

    private record Grant(long couponId, Instant redeemedAt) {
    }
}
