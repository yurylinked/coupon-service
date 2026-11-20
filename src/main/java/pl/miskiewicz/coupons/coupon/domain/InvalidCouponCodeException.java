package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidCouponCodeException extends DomainException {

    public InvalidCouponCodeException() {
        super("Coupon code must be 3-32 characters: letters A-Z, digits, '_' or '-'");
    }
}
