package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidActiveFlagException extends DomainException {

    public InvalidActiveFlagException() {
        super("Field active must be true or false");
    }
}
