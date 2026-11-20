package pl.miskiewicz.coupons.coupon.application

import java.time.Instant
import pl.miskiewicz.coupons.coupon.domain.Coupon
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException
import pl.miskiewicz.coupons.coupon.domain.CouponRepository
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.InvalidCountryCodeException
import pl.miskiewicz.coupons.coupon.domain.InvalidCouponCodeException
import pl.miskiewicz.coupons.coupon.domain.InvalidExpiryException
import pl.miskiewicz.coupons.coupon.domain.InvalidMaxUsesException
import pl.miskiewicz.coupons.coupon.domain.InvalidValidityPeriodException
import spock.lang.Specification

class CouponServiceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-19T10:15:30.123456Z')
    static final Coupon WIOSNA = Coupon.newCoupon(new CouponCode('WIOSNA'), new CountryCode('PL'), 5, NOW, null, null)

    CouponRepository coupons = Mock()
    CouponService service = new CouponService(coupons)

    def "creates a normalized, active coupon with no uses, stamped with the database time"() {
        when:
        def coupon = service.create(new CreateCouponCommand(' wiosna ', 100, 'pl', null, null))

        then:
        1 * coupons.currentTime() >> NOW
        1 * coupons.insertIfAbsent(_ as Coupon) >> true
        coupon.code() == new CouponCode('WIOSNA')
        coupon.country() == new CountryCode('PL')
        coupon.maxUses() == 100
        coupon.currentUses() == 0
        coupon.createdAt() == NOW
        coupon.validFrom() == null
        coupon.active()
    }

    def "stores the validity window with microsecond precision"() {
        given:
        coupons.currentTime() >> NOW

        when:
        def coupon = service.create(new CreateCouponCommand('WIOSNA', 100, 'PL',
                Instant.parse('2026-11-01T00:00:00.123456789Z'), Instant.parse('2026-12-31T22:59:59.123456789Z')))

        then:
        1 * coupons.insertIfAbsent(_ as Coupon) >> true
        coupon.validFrom() == Instant.parse('2026-11-01T00:00:00.123456Z')
        coupon.expiresAt() == Instant.parse('2026-12-31T22:59:59.123456Z')
    }

    def "rejects #description without storing anything"() {
        given:
        coupons.currentTime() >> NOW

        when:
        service.create(command)

        then:
        thrown(expected)
        0 * coupons.insertIfAbsent(_)

        where:
        description                       | command                                                                                                       || expected
        'an invalid code'                 | new CreateCouponCommand('ab', 100, 'PL', null, null)                                                          || InvalidCouponCodeException
        'an invalid country'              | new CreateCouponCommand('WIOSNA', 100, 'XX', null, null)                                                      || InvalidCountryCodeException
        'no use limit'                    | new CreateCouponCommand('WIOSNA', null, 'PL', null, null)                                                     || InvalidMaxUsesException
        'an expiry that is not ahead'     | new CreateCouponCommand('WIOSNA', 100, 'PL', null, NOW)                                                       || InvalidExpiryException
        'a validity that starts too late' | new CreateCouponCommand('WIOSNA', 100, 'PL', Instant.parse('2027-01-01T00:00:00Z'), Instant.parse('2027-01-01T00:00:00Z')) || InvalidValidityPeriodException
    }

    def "does not read the database time for a code that is invalid anyway"() {
        when:
        service.create(new CreateCouponCommand(null, 100, 'PL', null, null))

        then:
        thrown(InvalidCouponCodeException)
        0 * coupons._
    }

    def "rejects a code that already exists"() {
        given:
        coupons.currentTime() >> NOW
        coupons.insertIfAbsent(_) >> false

        when:
        service.create(new CreateCouponCommand('WIOSNA', 100, 'PL', null, null))

        then:
        thrown(CouponAlreadyExistsException)
    }

    def "finds a coupon regardless of the code case"() {
        given:
        coupons.findByCode(new CouponCode('WIOSNA')) >> Optional.of(new CouponSnapshot(WIOSNA, NOW))

        expect:
        service.get('wiosna') == WIOSNA
    }

    def "reports a missing coupon, and a code that cannot exist as invalid without a query"() {
        when:
        service.get(code)

        then:
        thrown(expected)
        calls * coupons.findByCode(_) >> Optional.empty()

        where:
        code      || expected                   | calls
        'UNKNOWN' || CouponNotFoundException    | 1
        'a/b'     || InvalidCouponCodeException | 0
    }

    def "suspends and resumes a coupon regardless of the code case"() {
        given:
        def updated = new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), 5, 0, NOW, null, null, active)

        when:
        def result = service.setActive('wiosna', active)

        then:
        1 * coupons.setActive(new CouponCode('WIOSNA'), active) >> Optional.of(updated)
        result == updated

        where:
        active << [false, true]
    }

    def "reports a missing coupon when changing its status"() {
        given:
        coupons.setActive(_, _) >> Optional.empty()

        when:
        service.setActive('UNKNOWN', false)

        then:
        thrown(CouponNotFoundException)
    }
}
