package pl.miskiewicz.coupons.coupon.domain;

public final class CouponExhaustedException extends CouponRejectedException {

    public CouponExhaustedException() {
        super("Coupon has reached its maximum number of uses");
    }
}
