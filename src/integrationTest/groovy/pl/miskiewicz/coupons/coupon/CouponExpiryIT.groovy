package pl.miskiewicz.coupons.coupon

import java.time.Instant
import org.springframework.beans.factory.annotation.Autowired
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId

class CouponExpiryIT extends IntegrationSpec {

    @Autowired
    RedemptionStore redemptionStore

    def "stores the expiry as an instant and returns it in UTC"() {
        when:
        def response = postJson('/api/v1/coupons', [code: 'LATO', maxUses: 5, country: 'PL', expiresAt: '2030-07-01T00:00:00+02:00'])

        then:
        response.status() == 201
        response.body().expiresAt == '2030-06-30T22:00:00Z'
    }

    def "rejects #description"() {
        when:
        def response = postJson('/api/v1/coupons', [code: 'LATO', maxUses: 5, country: 'PL', expiresAt: expiresAt])

        then:
        response.status() == 400
        response.body().errorCode == errorCode

        where:
        description                   | expiresAt                   || errorCode
        'an expiry without an offset' | '2030-07-01T00:00:00'       || 'MALFORMED_REQUEST'
        'an expiry in the past'       | '2020-01-01T00:00:00Z'      || 'INVALID_EXPIRY'
    }

    def "answers 409 COUPON_EXPIRED once the expiry has passed"() {
        given:
        postJson('/api/v1/coupons', [code: 'LATO', maxUses: 5, country: 'PL', expiresAt: '2030-07-01T00:00:00Z'])
        expireNow('LATO')

        when:
        def response = postJson('/api/v1/redemptions', [code: 'LATO', userId: 'user-1'])

        then:
        response.status() == 409
        response.body().errorCode == 'COUPON_EXPIRED'
        currentUses('LATO') == 0
    }

    def "the atomic update refuses an expired coupon even when the pre-check is skipped"() {
        given:
        postJson('/api/v1/coupons', [code: 'LATO', maxUses: 5, country: 'PL', expiresAt: '2030-07-01T00:00:00Z'])
        expireNow('LATO')

        when:
        redemptionStore.redeem(new CouponCode('LATO'), new UserId('user-1'), new CountryCode('PL'), Instant.now())

        then:
        thrown(CouponExpiredException)
        currentUses('LATO') == 0
        usageRows('LATO') == 0
    }

    private void expireNow(String code) {
        jdbcClient.sql("UPDATE coupon SET expires_at = created_at + interval '1 microsecond' WHERE code = :code")
                .param('code', code)
                .update()
    }
}
