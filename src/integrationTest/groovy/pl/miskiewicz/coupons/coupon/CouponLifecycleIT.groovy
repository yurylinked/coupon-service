package pl.miskiewicz.coupons.coupon

import groovy.json.JsonOutput
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId

class CouponLifecycleIT extends IntegrationSpec {

    @Autowired
    RedemptionStore redemptionStore

    def "exposes the start of validity and refuses a redemption before it"() {
        when:
        def created = postJson('/api/v1/coupons',
                [code: 'ZIMA', maxUses: 5, country: 'PL', validFrom: '2030-12-01T00:00:00+01:00', expiresAt: '2031-01-01T00:00:00Z'])
        def early = redeem('ZIMA', 'user-1')

        then:
        created.status() == 201
        created.body().validFrom == '2030-11-30T23:00:00Z'
        created.body().active == true
        early.status() == 409
        early.body().errorCode == 'COUPON_NOT_YET_VALID'
        currentUses('ZIMA') == 0
    }

    def "suspends and resumes redemptions, and repeating a status change is harmless"() {
        given:
        createCoupon('LEAKED', 5)

        when:
        def suspended = patchStatus('leaked', false)
        def suspendedAgain = patchStatus('LEAKED', false)
        def refused = redeem('LEAKED', 'user-1')
        def resumed = patchStatus('LEAKED', true)
        def redeemed = redeem('LEAKED', 'user-1')

        then:
        suspended.status() == 200
        suspended.body().active == false
        suspendedAgain.status() == 200
        suspendedAgain.body().active == false
        refused.status() == 409
        refused.body().errorCode == 'COUPON_SUSPENDED'
        resumed.status() == 200
        resumed.body().active == true
        redeemed.status() == 201
        currentUses('LEAKED') == 1
    }

    def "answers #status #errorCode to a status change #description"() {
        given:
        createCoupon('LEAKED', 5)

        when:
        def response = request('PATCH', "/api/v1/coupons/${code}", body, 'application/json')

        then:
        response.status() == status
        response.body().errorCode == errorCode

        where:
        description           | code      | body                 || status | errorCode
        'without the flag'    | 'LEAKED'  | '{}'                 || 400    | 'VALIDATION_FAILED'
        'with a non-boolean'  | 'LEAKED'  | '{"active":"maybe"}' || 400    | 'MALFORMED_REQUEST'
        'for an unknown code' | 'UNKNOWN' | '{"active":false}'   || 404    | 'COUPON_NOT_FOUND'
    }

    def "the atomic update refuses a #state coupon even when the pre-check is skipped"() {
        given:
        createCoupon('ZIMA', 5)
        jdbcClient.sql(sql).param('code', 'ZIMA').update()

        when:
        def attempt = redemptionStore.redeem(new CouponCode('ZIMA'), new UserId('user-1'), new CountryCode('PL'), null)

        then:
        attempt instanceof RedeemAttempt.NoUseLeft
        currentUses('ZIMA') == 0
        usageRows('ZIMA') == 0

        where:
        state           | sql
        'suspended'     | 'UPDATE coupon SET active = false WHERE code = :code'
        'not yet valid' | "UPDATE coupon SET valid_from = now() + interval '1 day' WHERE code = :code"
    }

    def "the database refuses #description"() {
        given:
        postJson('/api/v1/coupons', [code: 'ZIMA', maxUses: 5, country: 'PL', expiresAt: '2031-01-01T00:00:00Z'])
        redeem('ZIMA', 'user-1')

        when:
        jdbcClient.sql(sql).param('code', 'ZIMA').update()

        then:
        thrown(DataIntegrityViolationException)

        where:
        description                                     | sql
        'a validity window that does not start earlier' | 'UPDATE coupon SET valid_from = expires_at WHERE code = :code'
        'more uses than the limit'                      | 'UPDATE coupon SET current_uses = max_uses + 1 WHERE code = :code'
        'a negative use count'                          | 'UPDATE coupon SET current_uses = -1 WHERE code = :code'
        'a use limit of zero'                           | 'UPDATE coupon SET max_uses = 0, current_uses = 0 WHERE code = :code'
        'a use limit above the cap'                     | 'UPDATE coupon SET max_uses = 1000001 WHERE code = :code'
        'a lowercase code'                              | "UPDATE coupon SET code = 'zima' WHERE code = :code"
        'a lowercase coupon country'                    | "UPDATE coupon SET country_code = 'pl' WHERE code = :code"
        'an expiry before creation'                     | "UPDATE coupon SET expires_at = created_at - interval '1 second' WHERE code = :code"
        'a cancellation before the use'                 | "UPDATE coupon_usage SET cancelled_at = used_at - interval '1 microsecond' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
        'a lowercase redemption country'                | "UPDATE coupon_usage SET country_code = 'pl' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
        'an idempotency key with a space'               | "UPDATE coupon_usage SET idempotency_key = 'a b' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
        'an empty user id'                              | "UPDATE coupon_usage SET user_id = '' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
    }

    private patchStatus(String code, boolean active) {
        request('PATCH', "/api/v1/coupons/${code}", JsonOutput.toJson([active: active]), 'application/json')
    }
}
