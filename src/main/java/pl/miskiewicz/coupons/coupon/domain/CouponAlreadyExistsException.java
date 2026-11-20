package pl.miskiewicz.coupons.coupon.domain;

public final class CouponAlreadyExistsException extends DomainException {

    public CouponAlreadyExistsException() {
        super("Coupon with this code already exists");
    }
}
