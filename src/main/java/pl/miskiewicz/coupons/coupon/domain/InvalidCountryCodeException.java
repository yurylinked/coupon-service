package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidCountryCodeException extends RuntimeException {

    public InvalidCountryCodeException() {
        super("Country must be an ISO 3166-1 alpha-2 code", null, false, false);
    }
}
