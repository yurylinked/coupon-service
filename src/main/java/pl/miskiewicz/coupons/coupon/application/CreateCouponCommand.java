package pl.miskiewicz.coupons.coupon.application;

import java.time.Instant;

public record CreateCouponCommand(String code, Integer maxUses, String country, Instant validFrom, Instant expiresAt) {
}
