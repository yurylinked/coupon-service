package pl.miskiewicz.coupons.coupon.web;

import java.time.Instant;
import pl.miskiewicz.coupons.coupon.domain.Coupon;

record CouponResponse(
        String code,
        String country,
        int maxUses,
        int currentUses,
        Instant createdAt,
        Instant validFrom,
        Instant expiresAt,
        boolean active) {

    static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.code().value(),
                coupon.country().value(),
                coupon.maxUses(),
                coupon.currentUses(),
                coupon.createdAt(),
                coupon.validFrom(),
                coupon.expiresAt(),
                coupon.active());
    }
}
