package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Objects;

public record Coupon(CouponCode code, CountryCode country, int maxUses, int currentUses, Instant createdAt) {

    public Coupon {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(createdAt, "createdAt");
        if (maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be at least 1");
        }
        if (currentUses < 0 || currentUses > maxUses) {
            throw new IllegalArgumentException("currentUses must be between 0 and maxUses");
        }
    }

    public static Coupon newCoupon(CouponCode code, CountryCode country, int maxUses, Instant createdAt) {
        return new Coupon(code, country, maxUses, 0, createdAt);
    }
}
