package pl.miskiewicz.coupons.coupon

import groovy.json.JsonOutput
import java.time.Instant
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponNotYetActiveException
import pl.miskiewicz.coupons.coupon.domain.CouponSuspendedException
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId

class CouponLifecycleIT extends IntegrationSpec {

    @Autowired
    RedemptionStore redemptionStore

    def "exposes the start of validity and refuses a redemption before it"() {
        when:
        def created = postJson('/api/v1/coupons',
                [code: 'ZIMA', maxUses: 5, country: 'PL', validFrom: '2030-12-01T00:00:00+01:00', expiresAt: '2031-01-01T00:00:00Z'])
        def early = postJson('/api/v1/redemptions', [code: 'ZIMA', userId: 'user-1'])

        then:
        created.status() == 201
        created.body().validFrom == '2030-11-30T23:00:00Z'
        created.body().active == true
        early.status() == 409
        early.body().errorCode == 'COUPON_NOT_YET_ACTIVE'
        currentUses('ZIMA') == 0
    }

    def "rejects a validity window that does not start before the expiry"() {
        when:
        def response = postJson('/api/v1/coupons',
                [code: 'ZIMA', maxUses: 5, country: 'PL', validFrom: '2031-01-01T00:00:00Z', expiresAt: '2031-01-01T00:00:00Z'])

        then:
        response.status() == 400
        response.body().errorCode == 'INVALID_VALIDITY_PERIOD'
    }

    def "suspends and resumes redemptions, and repeating a status change is harmless"() {
        given:
        createCoupon('LEAKED', 5)

        when:
        def suspended = patchStatus('leaked', false)
        def suspendedAgain = patchStatus('LEAKED', false)
        def refused = postJson('/api/v1/redemptions', [code: 'LEAKED', userId: 'user-1'])
        def resumed = patchStatus('LEAKED', true)
        def redeemed = postJson('/api/v1/redemptions', [code: 'LEAKED', userId: 'user-1'])

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
        description          | code      | body                  || status | errorCode
        'without the flag'   | 'LEAKED'  | '{}'                  || 400    | 'VALIDATION_FAILED'
        'with a non-boolean' | 'LEAKED'  | '{"active":"maybe"}'  || 400    | 'MALFORMED_REQUEST'
        'for an unknown code'| 'UNKNOWN' | '{"active":false}'    || 404    | 'COUPON_NOT_FOUND'
    }

    def "the atomic update refuses a #state coupon even when the pre-check is skipped"() {
        given:
        createCoupon('ZIMA', 5)
        jdbcClient.sql(sql).param('code', 'ZIMA').update()

        when:
        redemptionStore.redeem(new CouponCode('ZIMA'), new UserId('user-1'), new CountryCode('PL'), Instant.now())

        then:
        thrown(expected)
        currentUses('ZIMA') == 0
        usageRows('ZIMA') == 0

        where:
        state            | sql                                                                       || expected
        'suspended'      | 'UPDATE coupon SET active = false WHERE code = :code'                     || CouponSuspendedException
        'not yet active' | "UPDATE coupon SET valid_from = now() + interval '1 day' WHERE code = :code" || CouponNotYetActiveException
    }

    def "the database refuses a validity window that does not start before the expiry"() {
        given:
        postJson('/api/v1/coupons', [code: 'ZIMA', maxUses: 5, country: 'PL', expiresAt: '2031-01-01T00:00:00Z'])

        when:
        jdbcClient.sql('UPDATE coupon SET valid_from = expires_at WHERE code = :code').param('code', 'ZIMA').update()

        then:
        thrown(DataIntegrityViolationException)
    }

    private def patchStatus(String code, boolean active) {
        request('PATCH', "/api/v1/coupons/${code}", JsonOutput.toJson([active: active]), 'application/json')
    }
}
