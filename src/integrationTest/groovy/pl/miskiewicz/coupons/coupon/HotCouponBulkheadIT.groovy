package pl.miskiewicz.coupons.coupon

import org.springframework.beans.factory.annotation.Value

class HotCouponBulkheadIT extends HotCouponSpec {

    @Value('${coupons.redemption.bulkhead.permits-per-stripe}')
    int permitsPerStripe

    def "a hot coupon holds at most its bulkhead's connections while another coupon is served"() {
        given:
        lockHotCouponRow()
        def hotRequests = fireHotRedemptions()

        when: 'the permitted requests wait on the row and every other one has been turned away'
        awaitTransactionsWaitingOnLock(permitsPerStripe)
        awaitCompleted(hotRequests, HOT_REQUESTS - permitsPerStripe)

        then:
        transactionsWaitingOnLock() == permitsPerStripe
        hotRequests.findAll { it.done }*.get().every { it == 429 }

        when:
        def cold = redeemColdCoupon()

        then:
        cold.status() == 201
        transactionsWaitingOnLock() == permitsPerStripe

        when:
        releaseHotCouponRow()

        then:
        def statuses = statusesOf(hotRequests)
        statuses.every { it in [201, 429] }
        statuses.count { it == 429 } >= HOT_REQUESTS - permitsPerStripe
    }
}
