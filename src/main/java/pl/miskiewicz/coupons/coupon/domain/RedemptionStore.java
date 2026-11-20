package pl.miskiewicz.coupons.coupon.domain;

import java.util.Optional;
import java.util.UUID;

public interface RedemptionStore {

    boolean hasRedeemed(CouponCode code, UserId userId);

    // key is null when the client sent no Idempotency-Key.
    RedeemAttempt redeem(CouponCode code, UserId userId, CountryCode country, IdempotencyKey key);

    Optional<RedemptionRecord> findById(UUID redemptionId);

    Optional<RedemptionRecord> findByIdempotencyKey(CouponCode code, UserId userId, IdempotencyKey key);

    boolean cancel(UUID redemptionId);
}
