package pl.miskiewicz.coupons.web

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import java.sql.SQLException
import java.time.Duration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus
import pl.miskiewicz.coupons.coupon.application.RedemptionBusyException
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

class ApiExceptionHandlerSpec extends Specification {

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler(new RetryAfter(new RetryAfterProperties(Duration.ofSeconds(2), Duration.ofSeconds(2)))))
            .build()

    def "maps #failure to #status #errorCode"() {
        when:
        def response = mvc.perform(get("/fail/$failure")).andReturn().response

        then:
        response.status == status
        response.contentType == 'application/problem+json'
        response.contentAsString.contains("\"errorCode\":\"$errorCode\"")
        response.getHeader('Retry-After') == retryAfter
        !response.contentAsString.contains('secret')

        where:
        failure            || status | errorCode             | retryAfter
        'pool-exhausted'   || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'no-transaction'   || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'connection-lost'  || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'connect-refused'  || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'no-sql-state'     || 500    | 'INTERNAL_ERROR'      | null
        'constraint'       || 500    | 'INTERNAL_ERROR'      | null
        'bug'              || 500    | 'INTERNAL_ERROR'      | null
    }

    def "maps every domain outcome to one status and error code"() {
        expect:
        ApiExceptionHandler.mappingOf(outcome) == new ApiExceptionHandler.Mapping(HttpStatus.valueOf(status), ErrorCode.valueOf(errorCode))

        where:
        outcome                                  || status | errorCode
        new InvalidCouponCodeException()         || 400    | 'INVALID_COUPON_CODE'
        new InvalidCountryCodeException()        || 400    | 'INVALID_COUNTRY_CODE'
        new InvalidUserIdException()             || 400    | 'INVALID_USER_ID'
        new InvalidIdempotencyKeyException()     || 400    | 'INVALID_IDEMPOTENCY_KEY'
        new InvalidMaxUsesException()            || 400    | 'INVALID_MAX_USES'
        new InvalidActiveFlagException()         || 400    | 'INVALID_ACTIVE_FLAG'
        new InvalidRedemptionIdException()       || 400    | 'INVALID_REDEMPTION_ID'
        new InvalidExpiryException()             || 400    | 'INVALID_EXPIRY'
        new InvalidValidityPeriodException()     || 400    | 'INVALID_VALIDITY_PERIOD'
        new CouponNotFoundException()            || 404    | 'COUPON_NOT_FOUND'
        new RedemptionNotFoundException()        || 404    | 'REDEMPTION_NOT_FOUND'
        new CountryUnresolvedException()         || 403    | 'COUNTRY_UNRESOLVED'
        new CouponCountryNotAllowedException()   || 403    | 'COUPON_COUNTRY_NOT_ALLOWED'
        new CouponAlreadyExistsException()       || 409    | 'COUPON_CODE_ALREADY_EXISTS'
        new CouponAlreadyUsedByUserException()   || 409    | 'COUPON_ALREADY_USED_BY_USER'
        new RedemptionCancelledException()       || 409    | 'REDEMPTION_CANCELLED'
        new CouponExpiredException()             || 409    | 'COUPON_EXPIRED'
        new CouponSuspendedException()           || 409    | 'COUPON_SUSPENDED'
        new CouponNotYetValidException()         || 409    | 'COUPON_NOT_YET_VALID'
        new CouponExhaustedException()           || 409    | 'COUPON_EXHAUSTED'
    }

    def "answers a domain outcome as problem+json with its message, and a busy coupon with Retry-After"() {
        when:
        def response = mvc.perform(get("/fail/$failure")).andReturn().response

        then:
        response.status == status
        response.contentType == 'application/problem+json'
        response.contentAsString.contains("\"errorCode\":\"$errorCode\"")
        response.getHeader('Retry-After') == retryAfter

        where:
        failure     || status | errorCode                     | retryAfter
        'suspended' || 409    | 'COUPON_SUSPENDED'            | null
        'busy'      || 429    | 'REDEMPTION_BUSY'             | '2'
    }

    @RestController
    static class FailingController {

        @GetMapping('/fail/{failure}')
        String fail(@PathVariable('failure') String failure) {
            if (failure == 'pool-exhausted') {
                throw new CannotGetJdbcConnectionException('secret pool detail')
            }
            if (failure == 'no-transaction') {
                throw new CannotCreateTransactionException('secret tx detail')
            }
            if (failure == 'connection-lost') {
                throw new DataAccessResourceFailureException('secret', new SQLException('secret connection detail', '08006'))
            }
            if (failure == 'connect-refused') {
                throw new DataAccessResourceFailureException('secret', new SQLException('secret refused detail', '08001'))
            }
            if (failure == 'no-sql-state') {
                throw new DataAccessResourceFailureException('secret', new SQLException('secret stateless detail'))
            }
            if (failure == 'suspended') {
                throw new CouponSuspendedException()
            }
            if (failure == 'busy') {
                throw new RedemptionBusyException()
            }
            if (failure == 'constraint') {
                throw new DataIntegrityViolationException('secret constraint detail')
            }
            throw new IllegalStateException('secret bug detail')
        }
    }
}
