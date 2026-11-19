package pl.miskiewicz.coupons.coupon.domain

import spock.lang.Specification

class CountryCodeSpec extends Specification {

    def "normalizes '#raw' to '#expected'"() {
        expect:
        CountryCode.parse(raw).value() == expected

        where:
        raw    || expected
        'PL'   || 'PL'
        'pl'   || 'PL'
        ' de ' || 'DE'
        'Us'   || 'US'
    }

    def "rejects #description"() {
        when:
        CountryCode.parse(raw)

        then:
        thrown(InvalidCountryCodeException)

        where:
        description               | raw
        'null'                    | null
        'empty string'            | ''
        'single letter'           | 'P'
        'alpha-3 code'            | 'POL'
        'unassigned code'         | 'XX'
        'digits'                  | '12'
        'Turkish dotted I'        | 'İT'
        'Cyrillic look-alike'     | 'РL'
    }
}
