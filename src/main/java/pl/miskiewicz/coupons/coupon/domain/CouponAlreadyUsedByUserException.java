package pl.miskiewicz.coupons.coupon.domain;

public final class CouponAlreadyUsedByUserException extends DomainException {

    public CouponAlreadyUsedByUserException() {
        super("Coupon has already been used by this user");
    }
}
