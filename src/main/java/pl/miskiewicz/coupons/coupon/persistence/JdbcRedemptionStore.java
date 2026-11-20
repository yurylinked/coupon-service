package pl.miskiewicz.coupons.coupon.persistence;

import static pl.miskiewicz.coupons.coupon.persistence.Timestamps.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

// Every write locks the coupon row first, so redemptions and cancellations of one coupon queue instead of
// deadlocking. The SQL predicates decide; the application explains a refusal afterwards.
@Repository
class JdbcRedemptionStore implements RedemptionStore {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String REDEMPTION_COLUMNS =
            "u.public_id, c.code, u.user_id, u.country_code, u.used_at, u.cancelled_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final String lockTimeout;

    JdbcRedemptionStore(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${coupons.redemption.lock-timeout:2s}") Duration lockTimeout) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    @Override
    public boolean hasRedeemed(CouponCode code, UserId userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM coupon_usage u
                            JOIN coupon c ON c.id = u.coupon_id
                            WHERE c.code = :code AND u.user_id = :userId AND u.cancelled_at IS NULL
                        )
                        """)
                .param("code", code.value())
                .param("userId", userId.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public RedeemAttempt redeem(CouponCode code, UserId userId, CountryCode country, IdempotencyKey key) {
        return inTransaction(status -> {
            Optional<Long> couponId = jdbc.sql("""
                            UPDATE coupon
                            SET current_uses = current_uses + 1
                            WHERE code = :code
                              AND active
                              AND current_uses < max_uses
                              AND (valid_from IS NULL OR valid_from <= statement_timestamp())
                              AND (expires_at IS NULL OR expires_at > statement_timestamp())
                            RETURNING id
                            """)
                    .param("code", code.value())
                    .query(Long.class)
                    .optional();
            if (couponId.isEmpty()) {
                return hasRedeemed(code, userId) ? new RedeemAttempt.AlreadyRedeemed() : new RedeemAttempt.NoUseLeft();
            }
            try {
                Optional<Redemption> inserted = jdbc.sql("""
                                INSERT INTO coupon_usage (coupon_id, user_id, country_code, idempotency_key)
                                VALUES (:couponId, :userId, :country, :idempotencyKey)
                                ON CONFLICT (coupon_id, user_id) WHERE cancelled_at IS NULL DO NOTHING
                                RETURNING public_id, used_at
                                """)
                        .param("couponId", couponId.get())
                        .param("userId", userId.value())
                        .param("country", country.value())
                        .param("idempotencyKey", key == null ? null : key.value())
                        .query((rs, rowNum) -> new Redemption(
                                rs.getObject("public_id", UUID.class), code, userId, country, instant(rs, "used_at")))
                        .optional();
                if (inserted.isEmpty()) {
                    status.setRollbackOnly();
                    return new RedeemAttempt.AlreadyRedeemed();
                }
                return new RedeemAttempt.Redeemed(inserted.get());
            } catch (DataAccessException ex) {
                if (!hasSqlState(ex, UNIQUE_VIOLATION)) {
                    throw ex;
                }
                // The key was already used by this user for this coupon, in a redemption that has since been cancelled.
                status.setRollbackOnly();
                return new RedeemAttempt.AlreadyRedeemed();
            }
        });
    }

    @Override
    public Optional<RedemptionRecord> findById(UUID redemptionId) {
        return jdbc.sql("SELECT " + REDEMPTION_COLUMNS + """
                         FROM coupon_usage u
                        JOIN coupon c ON c.id = u.coupon_id
                        WHERE u.public_id = :redemptionId
                        """)
                .param("redemptionId", redemptionId)
                .query((rs, rowNum) -> mapRecord(rs))
                .optional();
    }

    @Override
    public Optional<RedemptionRecord> findByIdempotencyKey(CouponCode code, UserId userId, IdempotencyKey key) {
        return jdbc.sql("SELECT " + REDEMPTION_COLUMNS + """
                         FROM coupon_usage u
                        JOIN coupon c ON c.id = u.coupon_id
                        WHERE c.code = :code AND u.user_id = :userId AND u.idempotency_key = :key
                        """)
                .param("code", code.value())
                .param("userId", userId.value())
                .param("key", key.value())
                .query((rs, rowNum) -> mapRecord(rs))
                .optional();
    }

    // The usage row is updated only after the coupon lock is held, so a concurrent second cancel sees the first.
    @Override
    public boolean cancel(UUID redemptionId) {
        return inTransaction(status -> {
            Optional<Long> couponId = jdbc.sql("""
                            SELECT id
                            FROM coupon
                            WHERE id = (SELECT coupon_id FROM coupon_usage WHERE public_id = :redemptionId)
                            FOR NO KEY UPDATE
                            """)
                    .param("redemptionId", redemptionId)
                    .query(Long.class)
                    .optional();
            if (couponId.isEmpty()) {
                return false;
            }
            int cancelled = jdbc.sql("""
                            UPDATE coupon_usage u
                            SET cancelled_at = statement_timestamp()
                            FROM coupon c
                            WHERE u.public_id = :redemptionId
                              AND u.cancelled_at IS NULL
                              AND c.id = u.coupon_id
                              AND (c.expires_at IS NULL OR c.expires_at > statement_timestamp())
                            """)
                    .param("redemptionId", redemptionId)
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

    private static RedemptionRecord mapRecord(ResultSet rs) throws SQLException {
        return new RedemptionRecord(
                new Redemption(
                        rs.getObject("public_id", UUID.class),
                        new CouponCode(rs.getString("code")),
                        new UserId(rs.getString("user_id")),
                        new CountryCode(rs.getString("country_code")),
                        instant(rs, "used_at")),
                instant(rs, "cancelled_at"));
    }
}
