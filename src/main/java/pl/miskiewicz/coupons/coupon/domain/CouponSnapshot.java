package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

// A coupon together with the database time at which it was read, so every replica judges it by the same clock.
public record CouponSnapshot(Coupon coupon, Instant observedAt) {

    public CouponSnapshot {
        Objects.requireNonNull(coupon, "coupon");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public Optional<CouponRejectedException> rejection() {
        return coupon.rejectionAt(observedAt);
    }

    public void ensureRedeemable() {
        coupon.ensureRedeemableAt(observedAt);
    }

    public boolean isExpired() {
        return coupon.isExpiredAt(observedAt);
    }
}
