package pl.miskiewicz.coupons.coupon.domain;

public final class RedemptionNotFoundException extends DomainException {

    public RedemptionNotFoundException() {
        super("Redemption not found");
    }
}
