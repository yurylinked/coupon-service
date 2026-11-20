package pl.miskiewicz.coupons.coupon

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.application.RedemptionBulkhead
import pl.miskiewicz.coupons.coupon.domain.CouponCode

abstract class HotCouponSpec extends IntegrationSpec {

    static final int HOT_REQUESTS = 20

    @Autowired
    PostgreSQLContainer postgres

    @Autowired
    RedemptionBulkhead bulkhead

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()
    Connection lockHolder
    Connection observer

    def setup() {
        assert bulkhead.stripeIndex(new CouponCode('HOT')) != bulkhead.stripeIndex(new CouponCode('COLD'))
        createCoupon('HOT', 1_000)
        createCoupon('COLD', 1_000)
    }

    def cleanup() {
        lockHolder?.rollback()
        lockHolder?.close()
        observer?.close()
        executor.close()
    }

    void lockHotCouponRow() {
        lockHolder = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        lockHolder.autoCommit = false
        lockHolder.createStatement().execute("SELECT id FROM coupon WHERE code = 'HOT' FOR UPDATE")
        observer = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    void releaseHotCouponRow() {
        lockHolder.rollback()
    }

    List<Future<Integer>> fireHotRedemptions() {
        (1..HOT_REQUESTS).collect { int i ->
            executor.submit({
                postJson('/api/v1/redemptions', [code: 'HOT', userId: "hot-user-$i".toString()]).status()
            } as Callable<Integer>)
        }
    }

    void awaitTransactionsWaitingOnLock(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        int observedMax = 0
        int waiting
        while ((waiting = transactionsWaitingOnLock()) < expected) {
            observedMax = Math.max(observedMax, waiting)
            assert System.nanoTime() < deadline: "expected $expected transactions waiting on the hot row, saw at most $observedMax"
            Thread.sleep(10)
        }
    }

    static void awaitCompleted(List<Future<Integer>> requests, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (requests.count { it.done } < expected) {
            assert System.nanoTime() < deadline: "expected $expected finished requests, saw ${requests.count { it.done }}"
            Thread.sleep(10)
        }
    }

    int transactionsWaitingOnLock() {
        def result = observer.createStatement().executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
        result.next()
        result.getInt(1)
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
