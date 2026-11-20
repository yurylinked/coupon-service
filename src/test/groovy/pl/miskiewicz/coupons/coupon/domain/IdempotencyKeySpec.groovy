package pl.miskiewicz.coupons.coupon.domain

import spock.lang.Specification

class IdempotencyKeySpec extends Specification {

    def "accepts '#raw' exactly as given"() {
        expect:
        new IdempotencyKey(raw).value() == raw

        where:
        raw << ['a', 'order-2026.11:42_A', 'Z' * 64, 'AZaz09._:-']
    }

    def "rejects #description"() {
        when:
        new IdempotencyKey(raw)

        then:
        thrown(InvalidIdempotencyKeyException)

        where:
        description            | raw
        'an empty key'         | ''
        'a key over 64 chars'  | 'a' * 65
        'a space'              | 'order 42'
        'a slash'              | 'order/42'
        'a non-ASCII letter'   | 'zamówienie-42'
        'a trailing newline'   | 'order-42\n'
    }

    def "no header means no key"() {
        expect:
        IdempotencyKey.ofNullable(null).isEmpty()
    }
}
