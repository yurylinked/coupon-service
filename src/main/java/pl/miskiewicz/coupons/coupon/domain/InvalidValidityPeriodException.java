package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidValidityPeriodException extends RuntimeException {

    public InvalidValidityPeriodException() {
        super("Validity must start before the coupon expires", null, false, false);
    }
}
