package pl.miskiewicz.coupons.coupon

import java.time.Instant
import org.springframework.beans.factory.annotation.Autowired
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId

class RedemptionCancellationIT extends IntegrationSpec {

    static final CountryCode PL = new CountryCode('PL')

    @Autowired
    RedemptionStore redemptionStore

    def "cancelling gives the use back, keeps the record and lets the user redeem again"() {
        given:
        createCoupon('BOOK', 1)
        def first = redeem('BOOK', 'user-1')

        when:
        def cancelled = cancel(first.body().redemptionId)
        def again = redeem('BOOK', 'user-1')

        then:
        first.status() == 201
        cancelled.status() == 204
        again.status() == 201
        again.body().redemptionId != first.body().redemptionId
        currentUses('BOOK') == 1
        activeUsageRows('BOOK') == 1
        usageRows('BOOK') == 2
    }

    def "a repeated cancellation changes nothing and still answers 204"() {
        given:
        createCoupon('BOOK', 5)
        def redemptionId = redeem('BOOK', 'user-1').body().redemptionId

        when:
        def first = cancel(redemptionId)
        def second = cancel(redemptionId)

        then:
        first.status() == 204
        second.status() == 204
        currentUses('BOOK') == 0
        activeUsageRows('BOOK') == 0
    }

    def "answers 404 REDEMPTION_NOT_FOUND for #description"() {
        when:
        def response = cancel(redemptionId)

        then:
        response.status() == 404
        response.body().errorCode == 'REDEMPTION_NOT_FOUND'

        where:
        description            | redemptionId
        'an unknown id'        | UUID.randomUUID().toString()
        'something not an id'  | 'not-a-uuid'
    }

    def "does not give back a use of an expired coupon"() {
        given:
        postJson('/api/v1/coupons', [code: 'BOOK', maxUses: 5, country: 'PL', expiresAt: '2030-07-01T00:00:00Z'])
        def redemptionId = redeem('BOOK', 'user-1').body().redemptionId
        jdbcClient.sql("UPDATE coupon SET expires_at = created_at + interval '1 microsecond' WHERE code = 'BOOK'").update()

        when:
        def response = cancel(redemptionId)

        then:
        response.status() == 409
        response.body().errorCode == 'COUPON_EXPIRED'
        currentUses('BOOK') == 1
        activeUsageRows('BOOK') == 1
    }

    def "a use returned to an exhausted coupon goes to the next customer"() {
        given:
        createCoupon('BOOK', 1)
        def redemptionId = redeem('BOOK', 'user-1').body().redemptionId
        def refused = redeem('BOOK', 'user-2')

        when:
        def cancelled = cancel(redemptionId)
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
        def redemptionId = redeem('BOOK', 'user-1').body().redemptionId
        request('PATCH', '/api/v1/coupons/BOOK', '{"active":false}', 'application/json')

        when:
        def cancelled = cancel(redemptionId)
        def refused = redeem('BOOK', 'user-1')

        then:
        cancelled.status() == 204
        currentUses('BOOK') == 0
        refused.body().errorCode == 'COUPON_SUSPENDED'
    }

    def "twenty parallel cancellations of one redemption give the use back exactly once"() {
        given:
        createCoupon('BOOK', 5)
        def redemption = redemptionStore.redeem(new CouponCode('BOOK'), new UserId('user-1'), PL, Instant.now())

        when:
        def outcomes = race(20) { i -> redemptionStore.cancel(redemption.id(), Instant.now()) }

        then:
        outcomes.countBy { it } == [OK: 20]
        currentUses('BOOK') == 0
        activeUsageRows('BOOK') == 0
    }

    def "cancelling the last use while another customer redeems it never deadlocks and keeps the counter exact"() {
        given:
        def codes = (1..20).collect { "LAST-$it" as String }
        def cancellations = codes.collect { code ->
            createCoupon(code, 1)
            redemptionStore.redeem(new CouponCode(code), new UserId('user-1'), PL, Instant.now()).id()
        }

        when:
        def outcomes = codes.indices.collectMany { int index ->
            race(2) { i ->
                if (i == 1) {
                    redemptionStore.cancel(cancellations[index], Instant.now())
                } else {
                    redemptionStore.redeem(new CouponCode(codes[index]), new UserId('user-2'), PL, Instant.now())
                }
            }
        }

        then: 'a redemption that found the coupon full a moment before the use came back is asked to retry, not told it is exhausted'
        outcomes.every { it in ['OK', 'CouponExhaustedException', 'RedemptionBusyException'] }
        codes.every { currentUses(it) == activeUsageRows(it) }
        codes.every { activeUsageRows(it) <= 1 }
    }

    private def redeem(String code, String userId) {
        postJson('/api/v1/redemptions', [code: code, userId: userId])
    }

    private def cancel(String redemptionId) {
        request('DELETE', "/api/v1/redemptions/${redemptionId}", null, null)
    }

    private int activeUsageRows(String code) {
        jdbcClient.sql('''
                SELECT count(*)
                FROM coupon_usage u
                JOIN coupon c ON c.id = u.coupon_id
                WHERE c.code = :code AND u.cancelled_at IS NULL
                ''')
                .param('code', code)
                .query(Integer)
                .single()
    }
}
