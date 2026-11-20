package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidExpiryException extends DomainException {

    public InvalidExpiryException() {
        super("Expiry must be later than the moment the coupon is created, within years 0001-9999");
    }
}
