package pl.miskiewicz.coupons.coupon.domain;

public final class CouponNotYetValidException extends CouponRejectedException {

    public CouponNotYetValidException() {
        super("Coupon is not valid yet");
    }
}
