package pl.miskiewicz.coupons.coupon.domain

import java.time.Instant
import spock.lang.Specification

class CouponSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-19T10:00:00Z')

    def "is exhausted=#exhausted with #currentUses of #maxUses uses taken"() {
        expect:
        coupon(maxUses: maxUses, currentUses: currentUses).isExhausted() == exhausted

        where:
        maxUses | currentUses || exhausted
        1       | 0           || false
        10      | 9           || false
        10      | 10          || true
    }

    def "rejects inconsistent counters: max=#maxUses, current=#currentUses"() {
        when:
        coupon(maxUses: maxUses, currentUses: currentUses)

        then:
        thrown(IllegalArgumentException)

        where:
        maxUses | currentUses
        5       | -1
        5       | 6
    }

    def "accepts a use limit of #maxUses"() {
        expect:
        coupon(maxUses: maxUses, currentUses: 0).maxUses() == maxUses

        where:
        maxUses << [1, Coupon.MAX_USES_LIMIT]
    }

    def "rejects a use limit of #maxUses"() {
        when:
        coupon(maxUses: maxUses, currentUses: 0)

        then:
        thrown(InvalidMaxUsesException)

        where:
        maxUses << [Integer.MIN_VALUE, -1, 0, Coupon.MAX_USES_LIMIT + 1, Integer.MAX_VALUE]
    }

    def "a new coupon needs a use limit"() {
        when:
        Coupon.newCoupon(new CouponCode('WIOSNA'), new CountryCode('PL'), null, NOW, null, null)

        then:
        thrown(InvalidMaxUsesException)
    }

    def "is expired=#expired at #at when it expires at #expiresAt"() {
        expect:
        coupon(expiresAt: expiresAt).isExpiredAt(Instant.parse(at)) == expired

        where:
        expiresAt              | at                         || expired
        null                   | '2099-01-01T00:00:00Z'     || false
        '2026-11-20T00:00:00Z' | '2026-11-19T23:59:59.999Z' || false
        '2026-11-20T00:00:00Z' | '2026-11-20T00:00:00Z'     || true
        '2026-11-20T00:00:00Z' | '2026-11-20T00:00:01Z'     || true
    }

    def "rejects an expiry that is not after creation: #expiresAt"() {
        when:
        coupon(expiresAt: expiresAt)

        then:
        thrown(InvalidExpiryException)

        where:
        expiresAt << ['2026-11-19T10:00:00Z', '2026-11-19T09:59:59Z']
    }

    def "is not yet valid=#notYetValid at #at when valid from #validFrom"() {
        expect:
        coupon(validFrom: validFrom).isNotYetValidAt(Instant.parse(at)) == notYetValid

        where:
        validFrom              | at                         || notYetValid
        null                   | '2000-01-01T00:00:00Z'     || false
        '2026-11-23T00:00:00Z' | '2026-11-22T23:59:59.999Z' || true
        '2026-11-23T00:00:00Z' | '2026-11-23T00:00:00Z'     || false
        '2026-11-23T00:00:00Z' | '2026-11-23T00:00:01Z'     || false
    }

    def "accepts a validity window from #validFrom to #expiresAt"() {
        when:
        coupon(validFrom: validFrom, expiresAt: expiresAt)

        then:
        noExceptionThrown()

        where:
        validFrom              | expiresAt
        '2026-11-01T00:00:00Z' | null
        '2026-11-01T00:00:00Z' | '2026-11-30T00:00:00Z'
        '2026-11-23T00:00:00Z' | '2026-11-23T00:00:00.000001Z'
    }

    def "rejects a validity window that does not start before the expiry: #validFrom to #expiresAt"() {
        when:
        coupon(validFrom: validFrom, expiresAt: expiresAt)

        then:
        thrown(InvalidValidityPeriodException)

        where:
        validFrom              | expiresAt
        '2026-11-30T00:00:00Z' | '2026-11-30T00:00:00Z'
        '2026-12-01T00:00:00Z' | '2026-11-30T00:00:00Z'
    }

    def "rejects as #rejection when expired=#expired, active=#active, not yet valid=#notYetValid, exhausted=#exhausted"() {
        given:
        def candidate = coupon(
                currentUses: exhausted ? 10 : 0,
                active: active,
                validFrom: notYetValid ? '2026-11-19T10:00:01Z' : null,
                expiresAt: expired ? '2026-11-19T10:00:00.000001Z' : null)
        def at = expired ? NOW.plusSeconds(60) : NOW

        expect:
        candidate.rejectionAt(at).map { it.class.simpleName }.orElse('none') == rejection

        where:
        expired | active | notYetValid  | exhausted || rejection
        false   | true   | false        | false     || 'none'
        true    | false  | false        | true      || 'CouponExpiredException'
        true    | true   | false        | false     || 'CouponExpiredException'
        false   | false  | true         | true      || 'CouponSuspendedException'
        false   | false  | false        | false     || 'CouponSuspendedException'
        false   | true   | true         | true      || 'CouponNotYetValidException'
        false   | true   | false        | true      || 'CouponExhaustedException'
    }

    def "rejects dates outside the four-digit years RFC 3339 can write: #description"() {
        when:
        coupon(validFrom: validFrom, expiresAt: expiresAt)

        then:
        thrown(expected)

        where:
        description                | validFrom                    | expiresAt                       || expected
        'an expiry after year 9999' | null                        | '+10000-01-01T00:00:00Z'        || InvalidExpiryException
        'a start before year 1'    | '-0001-12-31T00:00:00Z'      | null                            || InvalidValidityPeriodException
        'a start after year 9999'  | '+10000-01-01T00:00:00Z'     | null                            || InvalidValidityPeriodException
    }

    def "accepts the last microsecond of year 9999 as an expiry"() {
        expect:
        coupon(expiresAt: '9999-12-31T23:59:59.999999Z').expiresAt() == Instant.parse('9999-12-31T23:59:59.999999Z')
    }

    def "throws the rejection when asked to ensure the coupon is redeemable"() {
        when:
        coupon(active: false).ensureRedeemableAt(NOW)

        then:
        thrown(CouponSuspendedException)
    }

    def "a new coupon starts active with no uses"() {
        when:
        def created = Coupon.newCoupon(new CouponCode('WIOSNA'), new CountryCode('PL'), 5, NOW, null, null)

        then:
        created.active()
        created.currentUses() == 0
        created.rejectionAt(NOW).empty
    }

    private static Coupon coupon(Map args) {
        new Coupon(
                new CouponCode('WIOSNA'),
                new CountryCode('PL'),
                args.getOrDefault('maxUses', 10) as int,
                args.getOrDefault('currentUses', 0) as int,
                NOW,
                instant(args.validFrom as String),
                instant(args.expiresAt as String),
                args.getOrDefault('active', true) as boolean)
    }

    private static Instant instant(String value) {
        value == null ? null : Instant.parse(value)
    }
}
