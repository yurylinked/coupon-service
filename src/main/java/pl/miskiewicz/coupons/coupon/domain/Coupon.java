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

    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

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
        if (expiresAt != null && (!expiresAt.isAfter(createdAt) || expiresAt.isAfter(LATEST))) {
            throw new InvalidExpiryException();
        }
        if (validFrom != null && (validFrom.isBefore(EARLIEST) || validFrom.isAfter(LATEST)
                || expiresAt != null && !validFrom.isBefore(expiresAt))) {
            throw new InvalidValidityPeriodException();
        }
    }

    public static Coupon newCoupon(
            CouponCode code, CountryCode country, int maxUses, Instant createdAt, Instant validFrom, Instant expiresAt) {
        return new Coupon(code, country, maxUses, 0, createdAt, validFrom, expiresAt, true);
    }

    // Expiry first because it is final; exhausted last because a cancellation can undo it.
    public Optional<CouponRejectedException> rejectionAt(Instant instant) {
        if (isExpiredAt(instant)) {
            return Optional.of(new CouponExpiredException());
        }
        if (!active) {
            return Optional.of(new CouponSuspendedException());
        }
        if (isNotYetValidAt(instant)) {
            return Optional.of(new CouponNotYetValidException());
        }
        if (isExhausted()) {
            return Optional.of(new CouponExhaustedException());
        }
        return Optional.empty();
    }

    public void ensureRedeemableAt(Instant instant) {
        Optional<CouponRejectedException> rejection = rejectionAt(instant);
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

    public boolean isNotYetValidAt(Instant instant) {
        return validFrom != null && instant.isBefore(validFrom);
    }
}
