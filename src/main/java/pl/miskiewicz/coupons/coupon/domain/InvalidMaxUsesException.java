package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidMaxUsesException extends DomainException {

    public InvalidMaxUsesException() {
        super("Maximum number of uses must be between 1 and " + Coupon.MAX_USES_LIMIT);
    }
}
