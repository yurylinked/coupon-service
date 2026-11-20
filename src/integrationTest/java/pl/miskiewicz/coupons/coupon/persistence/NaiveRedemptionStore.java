package pl.miskiewicz.coupons.coupon.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.IdempotencyKey;
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionId;
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

// Deliberately wrong check-then-act implementation, kept only to prove that the concurrency test catches it.
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
    public RedeemAttempt redeem(CouponCode code, UserId userId, CountryCode country, @Nullable IdempotencyKey key) {
        return transaction.execute(status -> {
            CouponRow coupon = jdbc.sql("SELECT id, current_uses, max_uses FROM coupon WHERE code = :code")
                    .param("code", code.value())
                    .query(CouponRow.class)
                    .single();
            if (coupon.currentUses() >= coupon.maxUses()) {
                return new RedeemAttempt.NoUseLeft();
            }
            simulateProcessing();
            jdbc.sql("UPDATE coupon SET current_uses = :uses WHERE id = :id")
                    .param("uses", coupon.currentUses() + 1)
                    .param("id", coupon.id())
                    .update();
            Optional<Redemption> inserted = jdbc.sql("""
                            INSERT INTO redemption (coupon_id, user_id, country_code)
                            VALUES (:couponId, :userId, :country)
                            ON CONFLICT (coupon_id, user_id) WHERE cancelled_at IS NULL DO NOTHING
                            RETURNING public_id, redeemed_at
                            """)
                    .param("couponId", coupon.id())
                    .param("userId", userId.value())
                    .param("country", country.value())
                    .query((rs, rowNum) -> new Redemption(new RedemptionId(rs.getObject("public_id", UUID.class)), code,
                            userId, country, Timestamps.instant(rs, "redeemed_at")))
                    .optional();
            return inserted.<RedeemAttempt>map(RedeemAttempt.Redeemed::new).orElseGet(RedeemAttempt.AlreadyRedeemed::new);
        });
    }

    @Override
    public Optional<RedemptionRecord> findById(RedemptionId redemptionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RedemptionRecord> findByIdempotencyKey(CouponCode code, UserId userId, IdempotencyKey key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean cancel(RedemptionId redemptionId) {
        throw new UnsupportedOperationException();
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
