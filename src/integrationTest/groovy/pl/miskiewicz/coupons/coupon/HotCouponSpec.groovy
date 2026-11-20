package pl.miskiewicz.coupons.coupon

import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.application.RedemptionBulkhead
import pl.miskiewicz.coupons.coupon.domain.CouponCode

abstract class HotCouponSpec extends IntegrationSpec {

    static final int HOT_REQUESTS = 20

    @Autowired
    RedemptionBulkhead bulkhead

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()
    Connection lockHolder

    def setup() {
        assert bulkhead.stripeIndex(new CouponCode('HOT')) != bulkhead.stripeIndex(new CouponCode('COLD'))
        createCoupon('HOT', 1_000)
        createCoupon('COLD', 1_000)
    }

    def cleanup() {
        lockHolder?.rollback()
        lockHolder?.close()
        executor.close()
    }

    void lockHotCouponRow() {
        lockHolder = sideConnection()
        lockHolder.autoCommit = false
        lockHolder.createStatement().execute("SELECT id FROM coupon WHERE code = 'HOT' FOR UPDATE")
    }

    void releaseHotCouponRow() {
        lockHolder.rollback()
    }

    List<Future<Integer>> fireHotRedemptions() {
        (1..HOT_REQUESTS).collect { int i ->
            executor.submit({
                redeem('HOT', "hot-user-$i".toString()).status()
            } as Callable<Integer>)
        }
    }

    static void awaitCompleted(List<Future<Integer>> requests, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (requests.count { it.done } < expected) {
            assert System.nanoTime() < deadline: "expected $expected finished requests, saw ${requests.count { it.done }}"
            Thread.sleep(10)
        }
    }

    ColdRedemption redeemColdCoupon() {
        long started = System.nanoTime()
        int status = redeem('COLD', 'cold-user').status()
        new ColdRedemption(status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
    }

    List<Integer> statusesOf(List<Future<Integer>> requests) {
        requests.collect { it.get(10, TimeUnit.SECONDS) }
    }

    record ColdRedemption(int status, long millis) {
    }
}
