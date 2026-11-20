package pl.miskiewicz.coupons.coupon.domain;

public final class CouponNotYetActiveException extends RuntimeException {

    public CouponNotYetActiveException() {
        super("Coupon is not active yet", null, false, false);
    }
}
