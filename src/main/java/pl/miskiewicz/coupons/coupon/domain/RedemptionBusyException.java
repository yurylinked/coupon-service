package pl.miskiewicz.coupons.coupon.domain;

public final class RedemptionBusyException extends RuntimeException {

    public RedemptionBusyException() {
        super("Coupon is busy, retry the request", null, false, false);
    }
}
