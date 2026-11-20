package pl.miskiewicz.coupons.coupon

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import pl.miskiewicz.coupons.ApiResponse
import pl.miskiewicz.coupons.IntegrationSpec

class IdempotentRedemptionIT extends IntegrationSpec {

    def "a retry with the same key gets the original redemption back, even after the coupon changed"() {
        given:
        createCoupon('BOOK', 1)
        def original = redeem('BOOK', 'user-1', 'order-1')
        suspend('BOOK')
        requestsComeFrom('DE')

        when:
        def retry = redeem('BOOK', 'user-1', 'order-1')

        then:
        original.status() == 201
        original.header('Idempotent-Replayed') == null
        retry.status() == 201
        retry.header('Idempotent-Replayed') == 'true'
        retry.header('Location') == original.header('Location')
        retry.body() == original.body()
        currentUses('BOOK') == 1
        usageRows('BOOK') == 1
    }

    def "a second request #description is told the coupon is already used"() {
        given:
        createCoupon('BOOK', 5)
        redeem('BOOK', 'user-1', 'order-1')

        when:
        def response = key == null ? redeem('BOOK', 'user-1') : redeem('BOOK', 'user-1', key)

        then:
        response.status() == 409
        response.body().errorCode == 'COUPON_ALREADY_USED_BY_USER'
        currentUses('BOOK') == 1

        where:
        description        | key
        'without a key'    | null
        'with another key' | 'order-2'
    }

    def "the same key used by another user or for another coupon is a separate redemption"() {
        given:
        createCoupon('BOOK', 5)
        createCoupon('FILM', 5)
        def original = redeem('BOOK', 'user-1', 'order-1')

        when:
        def otherUser = redeem('BOOK', 'user-2', 'order-1')
        def otherCoupon = redeem('FILM', 'user-1', 'order-1')

        then:
        otherUser.status() == 201
        otherUser.header('Idempotent-Replayed') == null
        otherCoupon.status() == 201
        otherCoupon.header('Idempotent-Replayed') == null
        [original, otherUser, otherCoupon]*.body()*.redemptionId.unique().size() == 3
    }

    def "parallel requests with one key redeem once and every successful answer carries the same redemption"() {
        given:
        createCoupon('BOOK', 5)
        def responses = new ConcurrentLinkedQueue<ApiResponse>()

        when:
        def outcomes = race(10) { i ->
            def response = redeem('BOOK', 'user-1', 'order-1')
            responses << response
            response.status().toString()
        }

        then: 'a request may still be turned away by the bulkhead, but never with an error about its own redemption'
        outcomes.size() == 10
        outcomes.every { it in ['201', '429'] }
        responses.findAll { it.status() == 201 }*.body()*.redemptionId.unique().size() == 1
        currentUses('BOOK') == 1
        usageRows('BOOK') == 1
    }

    def "a retry after the redemption was cancelled is refused instead of redeeming again"() {
        given:
        createCoupon('BOOK', 5)
        def original = redeem('BOOK', 'user-1', 'order-1')
        cancel(original.body().redemptionId as String)

        when:
        def staleRetry = redeem('BOOK', 'user-1', 'order-1')
        def newOrder = redeem('BOOK', 'user-1', 'order-2')

        then:
        staleRetry.status() == 409
        staleRetry.body().errorCode == 'REDEMPTION_CANCELLED'
        newOrder.status() == 201
        currentUses('BOOK') == 1
    }

    def "a request held up until its twin was redeemed and cancelled does not redeem the same key again"() {
        given:
        createCoupon('BOOK', 5)
        def stalled = geo.pauseLookupsFrom('203.0.113.99')
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when:
        def late = executor.submit({
            postJson('/api/v1/redemptions', [code: 'BOOK', userId: 'user-1'],
                    ['Idempotency-Key': 'order-1', 'X-Forwarded-For': '203.0.113.99'])
        } as Callable<ApiResponse>)
        stalled.awaitEntered()
        def original = redeem('BOOK', 'user-1', 'order-1')
        def cancelled = cancel(original.body().redemptionId as String)
        stalled.release()
        def lateResponse = late.get(10, TimeUnit.SECONDS)

        then:
        original.status() == 201
        cancelled.status() == 204
        lateResponse.status() == 409
        lateResponse.body().errorCode == 'REDEMPTION_CANCELLED'
        currentUses('BOOK') == 0
        usageRows('BOOK') == 1

        cleanup:
        stalled?.release()
        executor?.close()
    }

    def "the longest key with every allowed character is stored and replayed, so the domain and the database agree"() {
        given:
        createCoupon('BOOK', 5)
        String key = ('AZaz09._:-' * 7).take(64)

        when:
        def original = redeem('BOOK', 'user-1', key)
        def retry = redeem('BOOK', 'user-1', key)

        then:
        original.status() == 201
        retry.status() == 201
        retry.header('Idempotent-Replayed') == 'true'
        retry.body().redemptionId == original.body().redemptionId
    }

    def "rejects a malformed key before looking at the coupon"() {
        when:
        def response = redeem('UNKNOWN', 'user-1', 'x' * 65)

        then:
        response.status() == 400
        response.body().errorCode == 'INVALID_IDEMPOTENCY_KEY'
    }
}
