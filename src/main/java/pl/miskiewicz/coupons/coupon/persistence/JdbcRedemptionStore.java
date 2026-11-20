package pl.miskiewicz.coupons.coupon.persistence;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

@Repository
class JdbcRedemptionStore implements RedemptionStore {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final CouponRepository coupons;
    private final String setLockTimeout;

    JdbcRedemptionStore(
            JdbcClient jdbc,
            CouponRepository coupons,
            @Value("${coupons.redemption.lock-timeout:2s}") Duration lockTimeout) {
        this.jdbc = jdbc;
        this.coupons = coupons;
        // SET does not accept bind parameters; the value is a Duration from configuration, never request input.
        this.setLockTimeout = "SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'";
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Redemption redeem(CouponCode code, UserId userId, CountryCode country, Instant redeemedAt) {
        try {
            jdbc.sql(setLockTimeout).update();
            Long couponId = jdbc.sql("""
                            UPDATE coupon
                            SET current_uses = current_uses + 1
                            WHERE code = :code
                              AND active
                              AND current_uses < max_uses
                              AND (valid_from IS NULL OR valid_from <= :now)
                              AND (expires_at IS NULL OR expires_at > :now)
                            RETURNING id
                            """)
                    .param("code", code.value())
                    .param("now", OffsetDateTime.ofInstant(redeemedAt, ZoneOffset.UTC))
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> noUseLeft(code, userId, redeemedAt));
            UUID redemptionId = jdbc.sql("""
                            INSERT INTO coupon_usage (coupon_id, user_id, country_code, used_at)
                            VALUES (:couponId, :userId, :country, :usedAt)
                            ON CONFLICT (coupon_id, user_id) WHERE cancelled_at IS NULL DO NOTHING
                            RETURNING public_id
                            """)
                    .param("couponId", couponId)
                    .param("userId", userId.value())
                    .param("country", country.value())
                    .param("usedAt", OffsetDateTime.ofInstant(redeemedAt, ZoneOffset.UTC))
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(CouponAlreadyUsedByUserException::new);
            return new Redemption(redemptionId, code, userId, country, redeemedAt);
        } catch (DataAccessException ex) {
            if (isLockTimeout(ex)) {
                throw new RedemptionBusyException();
            }
            throw ex;
        }
    }

    @Override
    public Optional<CouponCode> findCouponCode(UUID redemptionId) {
        return jdbc.sql("""
                        SELECT c.code
                        FROM coupon_usage u
                        JOIN coupon c ON c.id = u.coupon_id
                        WHERE u.public_id = :redemptionId
                        """)
                .param("redemptionId", redemptionId)
                .query(String.class)
                .optional()
                .map(CouponCode::new);
    }

    // The coupon row is locked first, in the same order as a redemption, so a cancel and a redemption of the same
    // coupon queue up instead of deadlocking. The usage state is read only after the lock is held: a second cancel of
    // the same redemption waits here, then sees the first one committed and changes nothing.
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(UUID redemptionId, Instant cancelledAt) {
        try {
            jdbc.sql(setLockTimeout).update();
            Long couponId = jdbc.sql("""
                            SELECT id
                            FROM coupon
                            WHERE id = (SELECT coupon_id FROM coupon_usage WHERE public_id = :redemptionId)
                            FOR UPDATE
                            """)
                    .param("redemptionId", redemptionId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(RedemptionNotFoundException::new);
            CancellationState state = jdbc.sql("""
                            SELECT u.cancelled_at IS NOT NULL AS cancelled,
                                   c.expires_at IS NOT NULL AND c.expires_at <= :now AS expired
                            FROM coupon_usage u
                            JOIN coupon c ON c.id = u.coupon_id
                            WHERE u.public_id = :redemptionId
                            """)
                    .param("now", OffsetDateTime.ofInstant(cancelledAt, ZoneOffset.UTC))
                    .param("redemptionId", redemptionId)
                    .query(CancellationState.class)
                    .single();
            if (state.cancelled()) {
                return;
            }
            if (state.expired()) {
                throw new CouponExpiredException();
            }
            jdbc.sql("UPDATE coupon_usage SET cancelled_at = :cancelledAt WHERE public_id = :redemptionId")
                    .param("cancelledAt", OffsetDateTime.ofInstant(cancelledAt, ZoneOffset.UTC))
                    .param("redemptionId", redemptionId)
                    .update();
            jdbc.sql("UPDATE coupon SET current_uses = current_uses - 1 WHERE id = :couponId")
                    .param("couponId", couponId)
                    .update();
        } catch (DataAccessException ex) {
            if (isLockTimeout(ex)) {
                throw new RedemptionBusyException();
            }
            throw ex;
        }
    }

    // A blocked UPDATE re-checks the row after the winner commits and may find nothing to update. Under READ COMMITTED
    // the next statements see what the winner committed, so the same user is told they already used the coupon and
    // anyone else gets the coupon's own reason, decided by the same domain rule as the pre-check and at the same
    // instant as the UPDATE. If no rule rejects any more, the state changed in between (for example the coupon was
    // reactivated) and the client is asked to retry instead of being given a reason that is no longer true.
    private RuntimeException noUseLeft(CouponCode code, UserId userId, Instant now) {
        if (hasRedeemed(code, userId)) {
            return new CouponAlreadyUsedByUserException();
        }
        return coupons.findByCode(code)
                .flatMap(coupon -> coupon.rejectionAt(now))
                .orElseGet(RedemptionBusyException::new);
    }

    record CancellationState(boolean cancelled, boolean expired) {
    }

    private static boolean isLockTimeout(DataAccessException ex) {
        return ex.getMostSpecificCause() instanceof SQLException sqlException
                && LOCK_NOT_AVAILABLE.equals(sqlException.getSQLState());
    }
}
