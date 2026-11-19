package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;

public interface RedemptionStore {

    boolean hasRedeemed(CouponCode code, UserId userId);

    Redemption redeem(CouponCode code, UserId userId, CountryCode country, Instant redeemedAt);
}
