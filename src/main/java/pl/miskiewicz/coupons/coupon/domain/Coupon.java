package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Coupon(
        CouponCode code,
        CountryCode country,
        int maxUses,
        int currentUses,
        Instant createdAt,
        Instant validFrom,
        Instant expiresAt,
        boolean active) {

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
        if (validFrom != null && expiresAt != null && !validFrom.isBefore(expiresAt)) {
            throw new InvalidValidityPeriodException();
        }
    }

    public static Coupon newCoupon(
            CouponCode code, CountryCode country, int maxUses, Instant createdAt, Instant validFrom, Instant expiresAt) {
        return new Coupon(code, country, maxUses, 0, createdAt, validFrom, expiresAt, true);
    }

    // The single place that decides why a coupon cannot be redeemed at a given instant. Permanent states come first:
    // "suspended" on an expired code would suggest it may come back, and "exhausted" can still change after a cancel.
    public Optional<RuntimeException> rejectionAt(Instant instant) {
        if (isExpiredAt(instant)) {
            return Optional.of(new CouponExpiredException());
        }
        if (!active) {
            return Optional.of(new CouponSuspendedException());
        }
        if (isNotYetActiveAt(instant)) {
            return Optional.of(new CouponNotYetActiveException());
        }
        if (isExhausted()) {
            return Optional.of(new CouponExhaustedException());
        }
        return Optional.empty();
    }

    public void ensureRedeemableAt(Instant instant) {
        Optional<RuntimeException> rejection = rejectionAt(instant);
        if (rejection.isPresent()) {
            throw rejection.get();
        }
    }

    public boolean isExhausted() {
        return currentUses >= maxUses;
    }

    public boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !instant.isBefore(expiresAt);
    }

    public boolean isNotYetActiveAt(Instant instant) {
        return validFrom != null && instant.isBefore(validFrom);
    }

    public Optional<Instant> validityStart() {
        return Optional.ofNullable(validFrom);
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }
}
