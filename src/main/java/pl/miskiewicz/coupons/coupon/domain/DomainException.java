package pl.miskiewicz.coupons.coupon.domain;

// Expected business outcomes, not failures: no stack trace, and a closed set so every mapping (HTTP, metrics)
// is checked by the compiler when a new outcome is added.
public abstract sealed class DomainException extends RuntimeException
        permits CountryUnresolvedException,
                CouponAlreadyExistsException,
                CouponAlreadyUsedByUserException,
                CouponCountryNotAllowedException,
                CouponNotFoundException,
                CouponRejectedException,
                InvalidCountryCodeException,
                InvalidCouponCodeException,
                InvalidExpiryException,
                InvalidIdempotencyKeyException,
                InvalidRedemptionIdException,
                InvalidUserIdException,
                InvalidValidityPeriodException,
                RedemptionCancelledException,
                RedemptionNotFoundException {

    protected DomainException(String message) {
        super(message, null, false, false);
    }
}
