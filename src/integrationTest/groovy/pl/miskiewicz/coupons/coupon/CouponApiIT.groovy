package pl.miskiewicz.coupons.coupon

import pl.miskiewicz.coupons.IntegrationSpec

class CouponApiIT extends IntegrationSpec {

    def "creates a coupon with a normalized code"() {
        when:
        def response = postJson('/api/v1/coupons', [code: ' wiosna ', maxUses: 100, country: 'pl'])

        then:
        response.status() == 201
        response.header('Location') == '/api/v1/coupons/WIOSNA'
        response.body().code == 'WIOSNA'
        response.body().country == 'PL'
        response.body().maxUses == 100
        response.body().currentUses == 0
        response.body().createdAt != null
    }

    def "treats codes differing only in case as the same coupon"() {
        given:
        postJson('/api/v1/coupons', [code: 'WIOSNA', maxUses: 10, country: 'PL'])

        when:
        def response = postJson('/api/v1/coupons', [code: 'wiosna', maxUses: 5, country: 'DE'])

        then:
        response.status() == 409
        response.header('Content-Type') == 'application/problem+json'
        response.body().errorCode == 'COUPON_CODE_ALREADY_EXISTS'
        couponRows('WIOSNA') == 1
    }

    def "creates exactly one coupon when the same code is submitted concurrently"() {
        when:
        def outcomes = race(20) { i ->
            postJson('/api/v1/coupons', [code: i % 2 ? 'promo' : 'PROMO', maxUses: 1, country: 'PL']).status().toString()
        }

        then:
        outcomes.countBy { it } == ['201': 1, '409': 19]
        couponRows('PROMO') == 1
    }

    def "returns #errorCode for #description"() {
        when:
        def response = postRaw('/api/v1/coupons', body)

        then:
        response.status() == 400
        response.header('Content-Type') == 'application/problem+json'
        response.body().errorCode == errorCode

        where:
        description                  | body                                        || errorCode
        'a missing use limit'        | '{"code":"WIOSNA","country":"PL"}'          || 'VALIDATION_FAILED'
        'a missing code'             | '{"maxUses":1,"country":"PL"}'              || 'INVALID_COUPON_CODE'
        'a code with invalid format' | '{"code":"ab","maxUses":1,"country":"PL"}'  || 'INVALID_COUPON_CODE'
        'an unknown country'         | '{"code":"WIOSNA","maxUses":1,"country":"XX"}' || 'INVALID_COUNTRY_CODE'
        'malformed JSON'             | '{"code":'                                  || 'MALFORMED_REQUEST'
    }

    def "lists every invalid annotated field"() {
        when:
        def response = postJson('/api/v1/coupons', [code: 'WIOSNA', maxUses: 0, country: 'PL'])

        then:
        response.status() == 400
        response.body().errors*.field == ['maxUses']
    }

    def "reads a coupon regardless of the code case"() {
        given:
        postJson('/api/v1/coupons', [code: 'WIOSNA', maxUses: 3, country: 'PL'])

        when:
        def response = get('/api/v1/coupons/wiosna')

        then:
        response.status() == 200
        response.body().code == 'WIOSNA'
        response.body().currentUses == 0
    }

    def "returns 404 for an unknown coupon"() {
        when:
        def response = get('/api/v1/coupons/UNKNOWN')

        then:
        response.status() == 404
        response.body().errorCode == 'COUPON_NOT_FOUND'
    }

    private int couponRows(String code) {
        jdbcClient.sql('SELECT count(*) FROM coupon WHERE code = :code')
                .param('code', code)
                .query(Integer)
                .single()
    }
}
