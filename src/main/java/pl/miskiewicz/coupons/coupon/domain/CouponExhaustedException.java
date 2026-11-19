package pl.miskiewicz.coupons.coupon.domain;

public final class CouponExhaustedException extends RuntimeException {

    public CouponExhaustedException() {
        super("Coupon has reached its maximum number of uses", null, false, false);
    }
}
