package pl.miskiewicz.coupons.coupon

import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = 'coupons.redemption.bulkhead.permits-per-coupon=10000')
class HotCouponWithoutBulkheadIT extends HotCouponSpec {

    def "without the bulkhead a hot coupon starves the connection pool for every other coupon"() {
        given:
        lockHotCouponRow()
        def hotRequests = fireHotRedemptions()
        awaitTransactionsWaitingOnLock(5)
        Thread.sleep(300)
        int waitingBeforeColdRequest = transactionsWaitingOnLock()

        when:
        def cold = redeemColdCoupon()
        releaseHotCouponRow()
        statusesOf(hotRequests)

        then:
        waitingBeforeColdRequest > 4
        cold.status == 201
        cold.millis >= 1_500
    }
}
