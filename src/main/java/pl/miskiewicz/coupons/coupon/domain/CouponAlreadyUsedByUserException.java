package pl.miskiewicz.coupons.coupon.domain;

public final class CouponAlreadyUsedByUserException extends RuntimeException {

    public CouponAlreadyUsedByUserException() {
        super("Coupon has already been used by this user", null, false, false);
    }
}
