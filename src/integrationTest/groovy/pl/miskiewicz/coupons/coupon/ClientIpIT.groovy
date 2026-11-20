package pl.miskiewicz.coupons.coupon

import pl.miskiewicz.coupons.IntegrationSpec

class ClientIpIT extends IntegrationSpec {

    def setup() {
        createCoupon('WIOSNA', 100)
    }

    def "uses #expected as the client address when X-Forwarded-For is #forwardedFor"() {
        when:
        redeemWith(forwardedFor)

        then:
        geo.lastLookup == InetAddress.ofLiteral(expected)

        where:
        forwardedFor                    || expected
        '203.0.113.7'                   || '203.0.113.7'
        '198.51.100.1, 203.0.113.7'     || '203.0.113.7'
        '203.0.113.7, 10.0.0.5'         || '10.0.0.5'
        '203.0.113.7, 127.0.0.1'        || '203.0.113.7'
    }

    def "uses the socket address when no proxy header is present"() {
        when:
        redeemWith(null)

        then:
        geo.lastLookup.isLoopbackAddress()
    }

    def "answers 403 without a geo lookup when the forwarded address is not an IP"() {
        when:
        def response = redeemWith('not-an-ip')

        then:
        response.status() == 403
        response.body().errorCode == 'COUNTRY_UNRESOLVED'
        geo.lastLookup == null
    }

    private redeemWith(String forwardedFor) {
        def headers = forwardedFor == null ? [:] : ['X-Forwarded-For': forwardedFor]
        postJson('/api/v1/redemptions', [code: 'WIOSNA', userId: "user-${UUID.randomUUID()}".toString()], headers)
    }
}
