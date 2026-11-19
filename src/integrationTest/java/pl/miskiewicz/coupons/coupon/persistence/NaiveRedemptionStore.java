package pl.miskiewicz.coupons.coupon.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

/**
 * Deliberately wrong check-then-act implementation, kept only to prove that the concurrency test catches it.
 */
public class NaiveRedemptionStore implements RedemptionStore {

    private static final Duration PROCESSING_TIME = Duration.ofMillis(20);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public NaiveRedemptionStore(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public boolean hasRedeemed(CouponCode code, UserId userId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Redemption redeem(CouponCode code, UserId userId, CountryCode country, Instant redeemedAt) {
        return transaction.execute(status -> {
            CouponRow coupon = jdbc.sql("SELECT id, current_uses, max_uses FROM coupon WHERE code = :code")
                    .param("code", code.value())
                    .query(CouponRow.class)
                    .single();
            if (coupon.currentUses() >= coupon.maxUses()) {
                throw new CouponExhaustedException();
            }
            simulateProcessing();
            jdbc.sql("UPDATE coupon SET current_uses = :uses WHERE id = :id")
                    .param("uses", coupon.currentUses() + 1)
                    .param("id", coupon.id())
                    .update();
            UUID id = jdbc.sql("""
                            INSERT INTO coupon_usage (coupon_id, user_id, country_code, used_at)
                            VALUES (:couponId, :userId, :country, :usedAt)
                            ON CONFLICT (coupon_id, user_id) DO NOTHING
                            RETURNING public_id
                            """)
                    .param("couponId", coupon.id())
                    .param("userId", userId.value())
                    .param("country", country.value())
                    .param("usedAt", OffsetDateTime.ofInstant(redeemedAt, ZoneOffset.UTC))
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(CouponAlreadyUsedByUserException::new);
            return new Redemption(id, code, userId, country, redeemedAt);
        });
    }

    private static void simulateProcessing() {
        try {
            Thread.sleep(PROCESSING_TIME);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    record CouponRow(long id, int currentUses, int maxUses) {
    }
}
