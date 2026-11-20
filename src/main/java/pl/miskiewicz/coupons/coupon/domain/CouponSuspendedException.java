package pl.miskiewicz.coupons.coupon.domain;

public final class CouponSuspendedException extends RuntimeException {

    public CouponSuspendedException() {
        super("Coupon is suspended", null, false, false);
    }
}
