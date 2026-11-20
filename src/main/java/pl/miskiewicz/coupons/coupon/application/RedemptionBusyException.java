package pl.miskiewicz.coupons.coupon.application;

public final class RedemptionBusyException extends RuntimeException {

    public RedemptionBusyException() {
        super("Coupon is busy, retry the request", null, false, false);
    }
}
