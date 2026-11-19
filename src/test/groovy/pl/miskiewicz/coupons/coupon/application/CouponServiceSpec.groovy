package pl.miskiewicz.coupons.coupon.application

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import pl.miskiewicz.coupons.coupon.domain.Coupon
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException
import pl.miskiewicz.coupons.coupon.domain.CouponRepository
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import spock.lang.Specification

class CouponServiceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-19T10:15:30.123456789Z')

    CouponRepository coupons = Mock()
    CouponService service = new CouponService(coupons, Clock.fixed(NOW, ZoneOffset.UTC))

    def "creates a normalized coupon with zero uses and a microsecond timestamp"() {
        when:
        def coupon = service.create(new CreateCouponCommand(' wiosna ', 100, 'pl'))

        then:
        1 * coupons.insertIfAbsent(_ as Coupon) >> true
        coupon.code() == new CouponCode('WIOSNA')
        coupon.country() == new CountryCode('PL')
        coupon.maxUses() == 100
        coupon.currentUses() == 0
        coupon.createdAt() == Instant.parse('2026-11-19T10:15:30.123456Z')
    }

    def "rejects a code that already exists"() {
        given:
        coupons.insertIfAbsent(_) >> false

        when:
        service.create(new CreateCouponCommand('WIOSNA', 100, 'PL'))

        then:
        thrown(CouponAlreadyExistsException)
    }

    def "finds a coupon regardless of the code case"() {
        given:
        def stored = Coupon.newCoupon(new CouponCode('WIOSNA'), new CountryCode('PL'), 5, NOW)
        coupons.findByCode(new CouponCode('WIOSNA')) >> Optional.of(stored)

        expect:
        service.get('wiosna') == stored
    }

    def "does not query the database for a code that cannot exist"() {
        when:
        service.get('a/b')

        then:
        thrown(CouponNotFoundException)
        0 * coupons.findByCode(_)
    }

    def "reports a missing coupon"() {
        given:
        coupons.findByCode(_) >> Optional.empty()

        when:
        service.get('UNKNOWN')

        then:
        thrown(CouponNotFoundException)
    }
}
