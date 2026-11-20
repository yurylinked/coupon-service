package pl.miskiewicz.coupons.coupon.domain;

public final class CouponNotFoundException extends DomainException {

    public CouponNotFoundException() {
        super("Coupon not found");
    }
}
