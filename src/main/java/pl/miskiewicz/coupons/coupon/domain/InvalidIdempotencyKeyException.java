package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidIdempotencyKeyException extends DomainException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1-64 characters: letters, digits, '.', '_', ':' or '-'");
    }
}
