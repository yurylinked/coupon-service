package pl.miskiewicz.coupons.coupon

import java.time.Instant
import org.springframework.beans.factory.annotation.Autowired
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId

class RedemptionCancellationIT extends IntegrationSpec {

    static final CountryCode PL = new CountryCode('PL')

    @Autowired
    RedemptionStore redemptionStore

    def "a redemption can be read back, cancelled, and the user can then redeem again"() {
        given:
        createCoupon('BOOK', 1)
        def first = redeem('BOOK', 'user-1')
        String id = first.body().redemptionId

        when:
        def cancelled = cancel(id)
        def afterCancel = get("/api/v1/redemptions/${id}")
        def again = redeem('BOOK', 'user-1')

        then:
        first.header('Location') == "/api/v1/redemptions/${id}"
        cancelled.status() == 204
        afterCancel.status() == 200
        afterCancel.body().cancelledAt != null
        Instant.parse(afterCancel.body().cancelledAt) >= Instant.parse(afterCancel.body().redeemedAt)
        again.status() == 201
        again.body().redemptionId != id
        currentUses('BOOK') == 1
        activeUsageRows('BOOK') == 1
        usageRows('BOOK') == 2
    }

    def "a repeated cancellation changes nothing and still answers 204"() {
        given:
        createCoupon('BOOK', 5)
        String id = redeem('BOOK', 'user-1').body().redemptionId

        when:
        def first = cancel(id)
        def second = cancel(id)

        then:
        first.status() == 204
        second.status() == 204
        currentUses('BOOK') == 0
        activeUsageRows('BOOK') == 0
    }

    def "answers #status #errorCode for #description"() {
        when:
        def response = cancel(redemptionId)

        then:
        response.status() == status
        response.body().errorCode == errorCode

        where:
        description           | redemptionId                 || status | errorCode
        'an unknown id'       | UUID.randomUUID().toString() || 404    | 'REDEMPTION_NOT_FOUND'
        'something not an id' | 'not-a-uuid'                 || 400    | 'INVALID_REDEMPTION_ID'
    }

    def "does not give back a use of an expired coupon"() {
        given:
        postJson('/api/v1/coupons', [code: 'BOOK', maxUses: 5, country: 'PL', expiresAt: '2030-07-01T00:00:00Z'])
        String id = redeem('BOOK', 'user-1').body().redemptionId
        expireNow('BOOK')

        when:
        def response = cancel(id)

        then:
        response.status() == 409
        response.body().errorCode == 'COUPON_EXPIRED'
        currentUses('BOOK') == 1
        activeUsageRows('BOOK') == 1
    }

    def "a use returned to an exhausted coupon goes to the next customer"() {
        given:
        createCoupon('BOOK', 1)
        String id = redeem('BOOK', 'user-1').body().redemptionId
        def refused = redeem('BOOK', 'user-2')

        when:
        def cancelled = cancel(id)
        def granted = redeem('BOOK', 'user-2')

        then:
        refused.body().errorCode == 'COUPON_EXHAUSTED'
        cancelled.status() == 204
        granted.status() == 201
        currentUses('BOOK') == 1
    }

    def "a use of a suspended coupon can be given back while the coupon stays suspended"() {
        given:
        createCoupon('BOOK', 5)
        String id = redeem('BOOK', 'user-1').body().redemptionId
        suspend('BOOK')

        when:
        def cancelled = cancel(id)
        def refused = redeem('BOOK', 'user-1')

        then:
        cancelled.status() == 204
        currentUses('BOOK') == 0
        refused.body().errorCode == 'COUPON_SUSPENDED'
    }

    def "twenty parallel cancellations of one redemption give the use back exactly once"() {
        given:
        createCoupon('BOOK', 5)
        def redeemed = redemptionStore.redeem(new CouponCode('BOOK'), new UserId('user-1'), PL, null) as RedeemAttempt.Redeemed

        when:
        def outcomes = race(20) { i -> redemptionStore.cancel(redeemed.redemption().id()) ? 'cancelled' : 'unchanged' }

        then:
        outcomes.countBy { it } == [cancelled: 1, unchanged: 19]
        currentUses('BOOK') == 0
        activeUsageRows('BOOK') == 0
    }

    def "cancelling the last use while another customer redeems it never deadlocks and keeps the counter exact"() {
        given:
        def codes = (1..20).collect { "LAST-$it" as String }
        def redemptionIds = codes.collect { code ->
            createCoupon(code, 1)
            (redemptionStore.redeem(new CouponCode(code), new UserId('user-1'), PL, null) as RedeemAttempt.Redeemed).redemption().id()
        }

        when:
        def outcomes = codes.indices.collectMany { int index ->
            race(2) { i ->
                if (i == 1) {
                    return redemptionStore.cancel(redemptionIds[index]) ? 'cancelled' : 'unchanged'
                }
                redemptionStore.redeem(new CouponCode(codes[index]), new UserId('user-2'), PL, null)
            }
        }

        then:
        outcomes.every { it in ['cancelled', 'Redeemed', 'NoUseLeft'] }
        outcomes.count { it == 'cancelled' } == codes.size()
        codes.every { currentUses(it) == activeUsageRows(it) }
    }
}
