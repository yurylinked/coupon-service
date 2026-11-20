package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidExpiryException extends RuntimeException {

    public InvalidExpiryException() {
        super("Expiry must be later than the moment the coupon is created", null, false, false);
    }
}
