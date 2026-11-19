package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidUserIdException extends RuntimeException {

    public InvalidUserIdException() {
        super("User id must be 1-64 characters: letters, digits or . _ @ : + -", null, false, false);
    }
}
