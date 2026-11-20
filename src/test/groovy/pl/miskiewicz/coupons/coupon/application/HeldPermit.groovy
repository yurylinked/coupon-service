package pl.miskiewicz.coupons.coupon.application

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import pl.miskiewicz.coupons.coupon.domain.CouponCode

// Holds one permit of a bulkhead on another thread until closed, so a spec can see a saturated coupon.
class HeldPermit implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1)
    private final Thread holder

    HeldPermit(RedemptionBulkhead bulkhead, CouponCode code) {
        def acquired = new CountDownLatch(1)
        holder = Thread.ofVirtual().start {
            bulkhead.execute(code) {
                acquired.countDown()
                release.await()
            }
        }
        assert acquired.await(5, TimeUnit.SECONDS)
    }

    @Override
    void close() {
        release.countDown()
        holder.join(5_000)
    }
}
