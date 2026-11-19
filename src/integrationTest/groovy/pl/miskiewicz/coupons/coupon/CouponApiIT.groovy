package pl.miskiewicz.coupons.coupon

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        given:
        int requests = 20
        def start = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when:
        def futures = (1..requests).collect { i ->
            executor.submit({
                start.await()
                postJson('/api/v1/coupons', [code: i % 2 ? 'promo' : 'PROMO', maxUses: 1, country: 'PL']).status()
            } as Callable<Integer>)
        }
        start.countDown()
        def statuses = futures*.get()

        then:
        statuses.count { it == 201 } == 1
        statuses.count { it == 409 } == requests - 1
        couponRows('PROMO') == 1

        cleanup:
        executor.close()
    }

    def "returns #errorCode for #description"() {
        when:
        def response = postRaw('/api/v1/coupons', body)

        then:
        response.status() == 400
        response.header('Content-Type') == 'application/problem+json'
        response.body().errorCode == errorCode

        where:
        description                  | body                                                 || errorCode
        'a missing field'            | '{"code":"WIOSNA","country":"PL"}'                   || 'VALIDATION_FAILED'
        'a code with invalid format' | '{"code":"ab","maxUses":1,"country":"PL"}'           || 'INVALID_COUPON_CODE'
        'malformed JSON'             | '{"code":'                                           || 'MALFORMED_REQUEST'
    }

    def "lists every invalid field"() {
        when:
        def response = postJson('/api/v1/coupons', [code: '', maxUses: 0, country: ''])

        then:
        response.status() == 400
        response.body().errors*.field == ['code', 'country', 'maxUses']
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
