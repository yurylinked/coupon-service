package pl.miskiewicz.coupons.coupon

import pl.miskiewicz.coupons.IntegrationSpec

class RedemptionApiIT extends IntegrationSpec {

    def "redeems a coupon, counts the use and points to the redemption"() {
        given:
        createCoupon('WIOSNA', 10)

        when:
        def response = redeem('wiosna', 'user-1')

        then:
        response.status() == 201
        response.body().redemptionId ==~ /[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}/
        response.header('Location') == "/api/v1/redemptions/${response.body().redemptionId}"
        response.body().code == 'WIOSNA'
        response.body().userId == 'user-1'
        response.body().redeemedAt != null
        response.body().cancelledAt == null
        get('/api/v1/coupons/WIOSNA').body().currentUses == 1
    }

    def "answers a business refusal as problem+json: #errorCode"() {
        given:
        createCoupon('WIOSNA', 1)
        redeem('WIOSNA', 'user-2')

        when:
        def response = redeem(code, 'user-1')

        then:
        response.status() == status
        response.header('Content-Type') == 'application/problem+json'
        response.body().errorCode == errorCode

        where:
        code      || status | errorCode
        'UNKNOWN' || 404    | 'COUPON_NOT_FOUND'
        'WIOSNA'  || 409    | 'COUPON_EXHAUSTED'
    }

    def "rejects malformed input with one error code per field: #errorCode"() {
        when:
        def response = postRaw('/api/v1/redemptions', body)

        then:
        response.status() == 400
        response.body().errorCode == errorCode

        where:
        body                                     || errorCode
        '{"code":"WIOSNA","userId":"john doe"}'  || 'INVALID_USER_ID'
        '{"code":"WIOSNA"}'                      || 'INVALID_USER_ID'
        '{"code":"","userId":"user-1"}'          || 'INVALID_COUPON_CODE'
        '{"code":"ab","userId":"user-1"}'        || 'INVALID_COUPON_CODE'
    }
}
