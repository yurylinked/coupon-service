package pl.miskiewicz.coupons.coupon.domain;

public final class CouponCountryNotAllowedException extends RuntimeException {

    public CouponCountryNotAllowedException() {
        super("Coupon is not available in the country of the request", null, false, false);
    }
}
