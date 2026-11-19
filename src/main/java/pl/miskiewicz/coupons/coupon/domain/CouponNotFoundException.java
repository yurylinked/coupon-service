package pl.miskiewicz.coupons.coupon.domain;

public final class CouponNotFoundException extends RuntimeException {

    public CouponNotFoundException() {
        super("Coupon not found", null, false, false);
    }
}
