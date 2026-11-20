package pl.miskiewicz.coupons.coupon.domain;

// The coupon row stayed locked longer than the store is allowed to wait. Nothing was changed; a retry may succeed.
public final class LockNotAcquiredException extends RuntimeException {

    public LockNotAcquiredException(Throwable cause) {
        super("Coupon row lock not acquired in time", cause);
    }
}
