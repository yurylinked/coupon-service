package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RedemptionStore {

    boolean hasRedeemed(CouponCode code, UserId userId);

    Redemption redeem(CouponCode code, UserId userId, CountryCode country, Instant redeemedAt);

    Optional<CouponCode> findCouponCode(UUID redemptionId);

    void cancel(UUID redemptionId, Instant cancelledAt);
}
