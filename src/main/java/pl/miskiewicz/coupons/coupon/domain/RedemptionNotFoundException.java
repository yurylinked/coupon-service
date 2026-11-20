package pl.miskiewicz.coupons.coupon.domain;

public final class RedemptionNotFoundException extends RuntimeException {

    public RedemptionNotFoundException() {
        super("Redemption not found", null, false, false);
    }
}
