package pl.miskiewicz.coupons.coupon.domain;

public final class InvalidRedemptionIdException extends DomainException {

    public InvalidRedemptionIdException() {
        super("Redemption id must be a UUID");
    }
}
