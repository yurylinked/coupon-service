package pl.miskiewicz.coupons.coupon.domain;

public final class CouponSuspendedException extends CouponRejectedException {

    public CouponSuspendedException() {
        super("Coupon is suspended");
    }
}
