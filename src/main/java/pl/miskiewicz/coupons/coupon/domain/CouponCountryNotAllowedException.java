package pl.miskiewicz.coupons.coupon.domain;

public final class CouponCountryNotAllowedException extends DomainException {

    public CouponCountryNotAllowedException() {
        super("Coupon is not available in the country of the request");
    }
}
