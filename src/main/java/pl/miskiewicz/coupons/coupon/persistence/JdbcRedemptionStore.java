package pl.miskiewicz.coupons.coupon.persistence;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

@Repository
class JdbcRedemptionStore implements RedemptionStore {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final String setLockTimeout;

    JdbcRedemptionStore(JdbcClient jdbc, @Value("${coupons.redemption.lock-timeout:2s}") Duration lockTimeout) {
        this.jdbc = jdbc;
        this.setLockTimeout = "SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'";
    }

    @Override
    public boolean hasRedeemed(CouponCode code, UserId userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM coupon_usage u
                            JOIN coupon c ON c.id = u.coupon_id
                            WHERE c.code = :code AND u.user_id = :userId
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
                            WHERE code = :code AND current_uses < max_uses
                            RETURNING id
                            """)
                    .param("code", code.value())
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> noUseLeft(code, userId));
            UUID redemptionId = jdbc.sql("""
                            INSERT INTO coupon_usage (coupon_id, user_id, country_code, used_at)
                            VALUES (:couponId, :userId, :country, :usedAt)
                            ON CONFLICT (coupon_id, user_id) DO NOTHING
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

    // A blocked UPDATE re-checks the row after the winner commits and may find no use left. Under READ COMMITTED
    // this next statement sees the winner's committed usage row, so the same user is told they already used it.
    private RuntimeException noUseLeft(CouponCode code, UserId userId) {
        return hasRedeemed(code, userId) ? new CouponAlreadyUsedByUserException() : new CouponExhaustedException();
    }

    private static boolean isLockTimeout(DataAccessException ex) {
        return ex.getMostSpecificCause() instanceof SQLException sqlException
                && LOCK_NOT_AVAILABLE.equals(sqlException.getSQLState());
    }
}
