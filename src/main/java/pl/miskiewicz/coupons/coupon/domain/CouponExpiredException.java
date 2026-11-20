package pl.miskiewicz.coupons.coupon.domain;

public final class CouponExpiredException extends RuntimeException {

    public CouponExpiredException() {
        super("Coupon has expired", null, false, false);
    }
}
