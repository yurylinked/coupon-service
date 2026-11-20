package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidValidityPeriodException extends DomainException {

    public InvalidValidityPeriodException() {
        super("Validity must start before the coupon expires, within years 0001-9999");
    }
}
