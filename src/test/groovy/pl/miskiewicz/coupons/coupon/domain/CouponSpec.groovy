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
        0       | 0
        5       | -1
        5       | 6
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

    def "is not yet active=#notYetActive at #at when valid from #validFrom"() {
        expect:
        coupon(validFrom: validFrom).isNotYetActiveAt(Instant.parse(at)) == notYetActive

        where:
        validFrom              | at                         || notYetActive
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

    def "rejects as #rejection when expired=#expired, active=#active, not yet active=#notYetActive, exhausted=#exhausted"() {
        given:
        def candidate = coupon(
                currentUses: exhausted ? 10 : 0,
                active: active,
                validFrom: notYetActive ? '2026-11-19T10:00:01Z' : null,
                expiresAt: expired ? '2026-11-19T10:00:00.000001Z' : null)
        def at = expired ? NOW.plusSeconds(60) : NOW

        expect:
        candidate.rejectionAt(at).map { it.class.simpleName }.orElse('none') == rejection

        where:
        expired | active | notYetActive | exhausted || rejection
        false   | true   | false        | false     || 'none'
        true    | false  | false        | true      || 'CouponExpiredException'
        true    | true   | false        | false     || 'CouponExpiredException'
        false   | false  | true         | true      || 'CouponSuspendedException'
        false   | false  | false        | false     || 'CouponSuspendedException'
        false   | true   | true         | true      || 'CouponNotYetActiveException'
        false   | true   | false        | true      || 'CouponExhaustedException'
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
