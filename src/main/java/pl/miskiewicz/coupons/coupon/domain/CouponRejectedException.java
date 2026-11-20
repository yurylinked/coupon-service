package pl.miskiewicz.coupons.coupon.domain;

public abstract sealed class CouponRejectedException extends DomainException
        permits CouponExpiredException, CouponSuspendedException, CouponNotYetValidException, CouponExhaustedException {

    protected CouponRejectedException(String message) {
        super(message);
    }
}
