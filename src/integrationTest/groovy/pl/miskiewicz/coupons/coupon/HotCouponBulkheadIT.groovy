package pl.miskiewicz.coupons.coupon

class HotCouponBulkheadIT extends HotCouponSpec {

    def "a hot coupon holds at most 4 connections and other coupons stay fast"() {
        given:
        lockHotCouponRow()
        def hotRequests = fireHotRedemptions()
        awaitTransactionsWaitingOnLock(4)
        Thread.sleep(300)

        when:
        def cold = redeemColdCoupon()

        then:
        transactionsWaitingOnLock() == 4
        cold.status == 201
        cold.millis < 1_000

        when:
        releaseHotCouponRow()
        def hotStatuses = statusesOf(hotRequests)

        then:
        hotStatuses.every { it in [201, 503] }
        hotStatuses.count { it == 503 } > 0
    }
}
