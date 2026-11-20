package pl.miskiewicz.coupons.coupon.domain

import java.time.Instant
import spock.lang.Specification

class CouponSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-19T10:00:00Z')

    def "is exhausted=#exhausted with #currentUses of #maxUses uses taken"() {
        expect:
        new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), maxUses, currentUses, NOW, null).isExhausted() == exhausted

        where:
        maxUses | currentUses || exhausted
        1       | 0           || false
        10      | 9           || false
        10      | 10          || true
    }

    def "rejects inconsistent counters: max=#maxUses, current=#currentUses"() {
        when:
        new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), maxUses, currentUses, NOW, null)

        then:
        thrown(IllegalArgumentException)

        where:
        maxUses | currentUses
        0       | 0
        5       | -1
        5       | 6
    }

    def "is expired=#expired at #at when it expires at #expiresAt"() {
        given:
        def coupon = new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), 10, 0, NOW,
                expiresAt == null ? null : Instant.parse(expiresAt))

        expect:
        coupon.isExpiredAt(Instant.parse(at)) == expired

        where:
        expiresAt              | at                         || expired
        null                   | '2099-01-01T00:00:00Z'     || false
        '2026-11-20T00:00:00Z' | '2026-11-19T23:59:59.999Z' || false
        '2026-11-20T00:00:00Z' | '2026-11-20T00:00:00Z'     || true
        '2026-11-20T00:00:00Z' | '2026-11-20T00:00:01Z'     || true
    }

    def "rejects an expiry that is not after creation: #expiresAt"() {
        when:
        new Coupon(new CouponCode('WIOSNA'), new CountryCode('PL'), 10, 0, NOW, Instant.parse(expiresAt))

        then:
        thrown(InvalidExpiryException)

        where:
        expiresAt << ['2026-11-19T10:00:00Z', '2026-11-19T09:59:59Z']
    }
}
