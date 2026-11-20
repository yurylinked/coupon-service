package pl.miskiewicz.coupons.coupon.domain;

public final class CountryUnresolvedException extends DomainException {

    public CountryUnresolvedException() {
        super("Country of the request could not be determined");
    }
}
