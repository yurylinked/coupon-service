package pl.miskiewicz.coupons.coupon.domain;

public final class CouponExpiredException extends CouponRejectedException {

    public CouponExpiredException() {
        super("Coupon has expired");
    }
}
