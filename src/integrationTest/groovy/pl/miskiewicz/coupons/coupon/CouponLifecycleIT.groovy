package pl.miskiewicz.coupons.coupon

import org.springframework.beans.factory.annotation.Autowired
import org.postgresql.util.PSQLException
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
                [code: 'ZIMA', maxUses: 5, country: 'PL', validFrom: '2998-12-01T00:00:00+01:00', expiresAt: '2999-01-01T00:00:00Z'])
        def early = redeem('ZIMA', 'user-1')

        then:
        created.status() == 201
        created.body().validFrom == '2998-11-30T23:00:00Z'
        created.body().active == true
        early.status() == 409
        early.body().errorCode == 'COUPON_NOT_YET_VALID'
        currentUses('ZIMA') == 0
    }

    def "suspends and resumes redemptions, and repeating a status change is harmless"() {
        given:
        createCoupon('LEAKED', 5)

        when:
        def suspended = setActive('leaked', false)
        def suspendedAgain = setActive('LEAKED', false)
        def refused = redeem('LEAKED', 'user-1')
        def resumed = setActive('LEAKED', true)
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
        'without the flag'    | 'LEAKED'  | '{}'                 || 400    | 'INVALID_ACTIVE_FLAG'
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
        redemptionRows('ZIMA') == 0

        where:
        state           | sql
        'suspended'     | 'UPDATE coupon SET active = false WHERE code = :code'
        'not yet valid' | "UPDATE coupon SET valid_from = now() + interval '1 day' WHERE code = :code"
    }

    def "the database refuses #description through #constraint"() {
        given:
        postJson('/api/v1/coupons', [code: 'ZIMA', maxUses: 5, country: 'PL', expiresAt: '2999-01-01T00:00:00Z'])
        redeem('ZIMA', 'user-1')

        when:
        jdbcClient.sql(sql).param('code', 'ZIMA').update()

        then:
        def ex = thrown(DataIntegrityViolationException)
        (ex.mostSpecificCause as PSQLException).serverErrorMessage.constraint == constraint

        where:
        description                                     | constraint                                  | sql
        'a validity window that does not start earlier' | 'ck_coupon_valid_from_before_expiry'        | 'UPDATE coupon SET valid_from = expires_at WHERE code = :code'
        'more uses than the limit'                      | 'ck_coupon_current_uses'                    | 'UPDATE coupon SET current_uses = max_uses + 1 WHERE code = :code'
        'a negative use count'                          | 'ck_coupon_current_uses'                    | 'UPDATE coupon SET current_uses = -1 WHERE code = :code'
        'a use limit of zero'                           | 'ck_coupon_max_uses'                        | 'UPDATE coupon SET max_uses = 0, current_uses = 0 WHERE code = :code'
        'a use limit above the cap'                     | 'ck_coupon_max_uses'                        | 'UPDATE coupon SET max_uses = 1000001 WHERE code = :code'
        'a lowercase code'                              | 'ck_coupon_code_format'                     | "UPDATE coupon SET code = 'zima' WHERE code = :code"
        'a lowercase coupon country'                    | 'ck_coupon_country_format'                  | "UPDATE coupon SET country_code = 'pl' WHERE code = :code"
        'an expiry before creation'                     | 'ck_coupon_expires_after_creation'          | "UPDATE coupon SET expires_at = created_at - interval '1 second' WHERE code = :code"
        'a cancellation before the redemption'          | 'ck_redemption_cancelled_after_redeemed'    | "UPDATE redemption SET cancelled_at = redeemed_at - interval '1 microsecond' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
        'a lowercase redemption country'                | 'ck_redemption_country_format'              | "UPDATE redemption SET country_code = 'pl' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
        'an idempotency key with a space'               | 'ck_redemption_idempotency_key'             | "UPDATE redemption SET idempotency_key = 'a b' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
        'an empty user id'                              | 'ck_redemption_user_id'                     | "UPDATE redemption SET user_id = '' WHERE coupon_id = (SELECT id FROM coupon WHERE code = :code)"
    }
}
