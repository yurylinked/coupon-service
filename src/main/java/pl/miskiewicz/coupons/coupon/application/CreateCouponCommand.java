package pl.miskiewicz.coupons.coupon.application;

import java.time.Instant;

public record CreateCouponCommand(String code, int maxUses, String country, Instant expiresAt) {
}
