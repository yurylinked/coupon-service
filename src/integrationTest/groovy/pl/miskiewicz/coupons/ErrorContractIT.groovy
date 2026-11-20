package pl.miskiewicz.coupons

class ErrorContractIT extends IntegrationSpec {

    def "#method #path with #contentType answers #status #errorCode as problem+json"() {
        when:
        def response = request(method, path, body, contentType)

        then:
        response.status() == status
        response.header('Content-Type') == 'application/problem+json'
        response.body().errorCode == errorCode

        where:
        method   | path                  | body | contentType        || status | errorCode
        'GET'    | '/api/v1/nope'        | null | null               || 404    | 'NOT_FOUND'
        'DELETE' | '/api/v1/coupons'     | null | null               || 405    | 'METHOD_NOT_ALLOWED'
        'GET'    | '/api/v1/redemptions' | null | null               || 405    | 'METHOD_NOT_ALLOWED'
        'POST'   | '/api/v1/coupons'     | 'x'  | 'text/plain'       || 415    | 'UNSUPPORTED_MEDIA_TYPE'
        'POST'   | '/api/v1/coupons'     | '{'  | 'application/json' || 400    | 'MALFORMED_REQUEST'
    }

    def "a code longer than any valid code is reported as not found, like any other unknown code"() {
        when:
        def response = postJson('/api/v1/redemptions', [code: 'A' * 40, userId: 'user-1'])

        then:
        response.status() == 404
        response.body().errorCode == 'COUPON_NOT_FOUND'
    }
}
