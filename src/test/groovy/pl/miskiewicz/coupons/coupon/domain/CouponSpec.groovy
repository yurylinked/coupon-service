package pl.miskiewicz.coupons.coupon.domain

import java.time.Instant
import spock.lang.Specification

class CouponSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-19T10:00:00Z')

    def "is exhausted=#exhausted with #currentUses of #maxUses uses taken"() {
        expect:
        new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), maxUses, currentUses, NOW).isExhausted() == exhausted

        where:
        maxUses | currentUses || exhausted
        1       | 0           || false
        10      | 9           || false
        10      | 10          || true
    }

    def "rejects inconsistent counters: max=#maxUses, current=#currentUses"() {
        when:
        new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), maxUses, currentUses, NOW)

        then:
        thrown(IllegalArgumentException)

        where:
        maxUses | currentUses
        0       | 0
        5       | -1
        5       | 6
    }
}
