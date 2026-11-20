package pl.miskiewicz.coupons.coupon.application

import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException
import pl.miskiewicz.coupons.coupon.domain.CouponNotYetValidException
import pl.miskiewicz.coupons.coupon.domain.CouponSuspendedException
import pl.miskiewicz.coupons.coupon.domain.InvalidCountryCodeException
import pl.miskiewicz.coupons.coupon.domain.InvalidCouponCodeException
import pl.miskiewicz.coupons.coupon.domain.InvalidExpiryException
import pl.miskiewicz.coupons.coupon.domain.InvalidActiveFlagException
import pl.miskiewicz.coupons.coupon.domain.InvalidIdempotencyKeyException
import pl.miskiewicz.coupons.coupon.domain.InvalidMaxUsesException
import pl.miskiewicz.coupons.coupon.domain.InvalidRedemptionIdException
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException
import pl.miskiewicz.coupons.coupon.domain.InvalidValidityPeriodException
import pl.miskiewicz.coupons.coupon.domain.RedemptionCancelledException
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException
import spock.lang.Specification

class RedemptionOutcomesSpec extends Specification {

    def "tags #reason.class.simpleName as '#outcome'"() {
        expect:
        RedemptionOutcomes.outcomeOf(reason) == outcome

        where:
        reason                                   || outcome
        new InvalidUserIdException()             || 'invalid_user'
        new InvalidIdempotencyKeyException()     || 'invalid_idempotency_key'
        new InvalidMaxUsesException()            || 'invalid_max_uses'
        new InvalidActiveFlagException()         || 'invalid_active_flag'
        new InvalidCouponCodeException()         || 'invalid_code'
        new InvalidRedemptionIdException()       || 'invalid_redemption_id'
        new InvalidCountryCodeException()        || 'invalid_country'
        new InvalidExpiryException()             || 'invalid_expiry'
        new InvalidValidityPeriodException()     || 'invalid_validity_period'
        new CouponNotFoundException()            || 'not_found'
        new RedemptionNotFoundException()        || 'redemption_not_found'
        new CouponAlreadyExistsException()       || 'already_exists'
        new CountryUnresolvedException()         || 'country_unresolved'
        new CouponCountryNotAllowedException()   || 'country_not_allowed'
        new CouponAlreadyUsedByUserException()   || 'already_used'
        new RedemptionCancelledException()       || 'redemption_cancelled'
        new CouponExpiredException()             || 'expired'
        new CouponSuspendedException()           || 'suspended'
        new CouponNotYetValidException()         || 'not_yet_valid'
        new CouponExhaustedException()           || 'exhausted'
        new RedemptionBusyException()            || 'busy'
        new IllegalStateException('unexpected')  || 'error'
    }
}
