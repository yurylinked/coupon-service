package pl.miskiewicz.coupons.coupon.domain;

public final class CouponAlreadyExistsException extends RuntimeException {

    public CouponAlreadyExistsException() {
        super("Coupon with this code already exists", null, false, false);
    }
}
