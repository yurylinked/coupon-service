package pl.miskiewicz.coupons.coupon

class HotCouponBulkheadIT extends HotCouponSpec {

    def "a hot coupon holds at most 4 connections while another coupon is served"() {
        given:
        lockHotCouponRow()
        def hotRequests = fireHotRedemptions()
        awaitTransactionsWaitingOnLock(4)
        Thread.sleep(300)

        expect:
        transactionsWaitingOnLock() == 4

        when:
        def cold = redeemColdCoupon()

        then:
        cold.status == 201
        transactionsWaitingOnLock() == 4

        when:
        releaseHotCouponRow()
        def hotStatuses = statusesOf(hotRequests)

        then:
        hotStatuses.every { it in [201, 503] }
        hotStatuses.count { it == 503 } > 0
    }
}
