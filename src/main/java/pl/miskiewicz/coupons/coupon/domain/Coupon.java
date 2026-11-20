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

    public static final int MAX_USES_LIMIT = 1_000_000;
    // RFC 3339 has four-digit years only; Jackson would print year 10000 as "+10000-…", which clients cannot parse.
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    public Coupon {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(createdAt, "createdAt");
        if (maxUses < 1 || maxUses > MAX_USES_LIMIT) {
            throw new InvalidMaxUsesException();
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
            CouponCode code, CountryCode country, Integer maxUses, Instant createdAt, Instant validFrom, Instant expiresAt) {
        if (maxUses == null) {
            throw new InvalidMaxUsesException();
        }
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

    boolean isExhausted() {
        return currentUses >= maxUses;
    }

    boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !instant.isBefore(expiresAt);
    }

    boolean isNotYetValidAt(Instant instant) {
        return validFrom != null && instant.isBefore(validFrom);
    }
}
