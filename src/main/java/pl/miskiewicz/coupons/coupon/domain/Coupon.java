package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Coupon(
        CouponCode code, CountryCode country, int maxUses, int currentUses, Instant createdAt, Instant expiresAt) {

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
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new InvalidExpiryException();
        }
    }

    public static Coupon newCoupon(
            CouponCode code, CountryCode country, int maxUses, Instant createdAt, Instant expiresAt) {
        return new Coupon(code, country, maxUses, 0, createdAt, expiresAt);
    }

    public boolean isExhausted() {
        return currentUses >= maxUses;
    }

    public boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !instant.isBefore(expiresAt);
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }
}
