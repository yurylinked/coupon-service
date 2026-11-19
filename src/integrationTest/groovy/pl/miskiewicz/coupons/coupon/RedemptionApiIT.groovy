package pl.miskiewicz.coupons.coupon

import pl.miskiewicz.coupons.IntegrationSpec

class RedemptionApiIT extends IntegrationSpec {

    def "redeems a coupon and counts the use"() {
        given:
        createCoupon('WIOSNA', 10)

        when:
        def response = redeem('wiosna', 'user-1')

        then:
        response.status() == 201
        response.body().redemptionId ==~ /[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}/
        response.body().code == 'WIOSNA'
        response.body().userId == 'user-1'
        response.body().redeemedAt != null
        get('/api/v1/coupons/WIOSNA').body().currentUses == 1
    }

    def "returns #status #errorCode when #description"() {
        given:
        createCoupon('WIOSNA', maxUses)
        previousUsers.each { assert redeem('WIOSNA', it).status() == 201 }
        requestsComeFrom(requestCountry)

        when:
        def response = redeem(code, 'user-1')

        then:
        response.status() == status
        response.header('Content-Type') == 'application/problem+json'
        response.body().errorCode == errorCode

        where:
        description                              | code      | maxUses | previousUsers | requestCountry || status | errorCode
        'the coupon does not exist'              | 'UNKNOWN' | 10      | []            | 'PL'           || 404    | 'COUPON_NOT_FOUND'
        'the country cannot be resolved'         | 'WIOSNA'  | 10      | []            | null           || 403    | 'COUNTRY_UNRESOLVED'
        'the request comes from another country' | 'WIOSNA'  | 10      | []            | 'DE'           || 403    | 'COUPON_COUNTRY_NOT_ALLOWED'
        'the user already used the coupon'       | 'WIOSNA'  | 1       | ['user-1']    | 'PL'           || 409    | 'COUPON_ALREADY_USED_BY_USER'
        'all uses are taken'                     | 'WIOSNA'  | 1       | ['user-2']    | 'PL'           || 409    | 'COUPON_EXHAUSTED'
    }

    def "rejects an invalid user id"() {
        when:
        def response = redeem('WIOSNA', 'john doe')

        then:
        response.status() == 400
        response.body().errorCode == 'INVALID_USER_ID'
    }

    private redeem(String code, String userId) {
        postJson('/api/v1/redemptions', [code: code, userId: userId])
    }
}
