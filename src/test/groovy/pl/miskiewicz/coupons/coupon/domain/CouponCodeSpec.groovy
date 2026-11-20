package pl.miskiewicz.coupons.coupon.domain

import spock.lang.Specification

class CouponCodeSpec extends Specification {

    def "normalizes '#raw' to '#expected'"() {
        expect:
        CouponCode.parse(raw).value() == expected

        where:
        raw                 || expected
        'WIOSNA'            || 'WIOSNA'
        'wiosna'            || 'WIOSNA'
        'WiOsNa'            || 'WIOSNA'
        '  wiosna \t'       || 'WIOSNA'
        'abc'               || 'ABC'
        'black_friday-2026' || 'BLACK_FRIDAY-2026'
        '2026'              || '2026'
        'a' * 32            || 'A' * 32
    }

    def "rejects #description"() {
        when:
        CouponCode.parse(raw)

        then:
        thrown(InvalidCouponCodeException)

        where:
        description              | raw
        'null'                   | null
        'empty string'           | ''
        'blank string'           | '   '
        'too short code'         | 'ab'
        'too long code'          | 'a' * 33
        'inner space'            | 'WIO SNA'
        'Latin long s'           | 'ſale'
        'Turkish dotless i'      | 'wıosna'
        'Turkish dotted I'       | 'İSTANBUL'
        'Kelvin sign'            | 'KUPON'
        'ff ligature'            | 'ﬀX1'
        'full-width letter'      | 'ＡBC'
        'Cyrillic A'             | 'АBC'
        'no-break space'         | 'WIO SNA'
        'zero-width space'       | 'WIO​SNA'
        'emoji'                  | 'SALE🔥'
        'slash'                  | 'A/B'
        'percent sign'           | '50%OFF'
    }

    def "normalization does not depend on the default locale"() {
        given:
        def original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag('tr-TR'))

        expect:
        CouponCode.parse('wiosna').value() == 'WIOSNA'

        cleanup:
        Locale.setDefault(original)
    }

    def "normalization is idempotent for '#raw'"() {
        given:
        def once = CouponCode.parse(raw)

        expect:
        CouponCode.parse(once.value()) == once

        where:
        raw << ['wiosna', 'Black_Friday-26', '  x-1  ']
    }

    def "rejects '#raw', including a missing code and a letter that only becomes ASCII when upper-cased"() {
        when:
        CouponCode.parse(raw)

        then:
        thrown(InvalidCouponCodeException)

        where:
        raw << [null, '', 'ab', 'ſale']
    }

    def "constructor accepts only the normalized form"() {
        when:
        new CouponCode('wiosna')

        then:
        thrown(InvalidCouponCodeException)
    }
}
