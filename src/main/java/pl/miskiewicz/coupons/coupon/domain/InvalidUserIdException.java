package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidUserIdException extends DomainException {

    public InvalidUserIdException() {
        super("User id must be 1-64 characters: letters, digits or . _ @ : + -");
    }
}
