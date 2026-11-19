package pl.miskiewicz.coupons.coupon.domain

import spock.lang.Specification

class UserIdSpec extends Specification {

    def "accepts '#raw'"() {
        expect:
        UserId.parse(raw).value() == expected

        where:
        raw                                    || expected
        'user-1'                               || 'user-1'
        '  user@example.com '                  || 'user@example.com'
        '0b7c1a9e-3f4d-4b8a-9c2e-1d5f6a7b8c9d' || '0b7c1a9e-3f4d-4b8a-9c2e-1d5f6a7b8c9d'
        'tenant:42+eu'                         || 'tenant:42+eu'
        'a' * 64                               || 'a' * 64
    }

    def "user ids are case-sensitive"() {
        expect:
        UserId.parse('User-1') != UserId.parse('user-1')
    }

    def "rejects #description"() {
        when:
        UserId.parse(raw)

        then:
        thrown(InvalidUserIdException)

        where:
        description           | raw
        'null'                | null
        'empty string'        | ''
        'blank string'        | '   '
        'too long id'         | 'a' * 65
        'inner space'         | 'john doe'
        'non-ASCII letter'    | 'zołza'
        'markup'              | '<script>'
        'line break'          | 'a\nb'
    }
}
