package pl.miskiewicz.coupons.coupon.domain;

public final class RedemptionCancelledException extends DomainException {

    public RedemptionCancelledException() {
        super("This redemption was cancelled; send a new Idempotency-Key to redeem again");
    }
}
