package pl.miskiewicz.coupons.coupon.domain;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface RedemptionStore {

    boolean hasRedeemed(CouponCode code, UserId userId);

    RedeemAttempt redeem(CouponCode code, UserId userId, CountryCode country, @Nullable IdempotencyKey key);

    Optional<RedemptionRecord> findById(RedemptionId redemptionId);

    Optional<RedemptionRecord> findByIdempotencyKey(CouponCode code, UserId userId, IdempotencyKey key);

    boolean cancel(RedemptionId redemptionId);
}
