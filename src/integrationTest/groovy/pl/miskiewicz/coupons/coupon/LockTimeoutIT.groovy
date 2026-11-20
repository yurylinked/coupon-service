package pl.miskiewicz.coupons.coupon

import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import pl.miskiewicz.coupons.IntegrationSpec
import spock.lang.Timeout

@Timeout(30)
class LockTimeoutIT extends IntegrationSpec {

    @Value('${coupons.redemption.lock-timeout}')
    Duration lockTimeout

    def "answers 429 with Retry-After while the coupon row is locked and succeeds once it is released"() {
        given:
        createCoupon('LOCKED', 5)
        def lockHolder = sideConnection()
        lockHolder.autoCommit = false
        lockHolder.createStatement().execute("SELECT id FROM coupon WHERE code = 'LOCKED' FOR UPDATE")

        when:
        long started = System.nanoTime()
        def busy = redeem('LOCKED', 'user-1')
        long waitedMillis = (System.nanoTime() - started).intdiv(1_000_000)
        lockHolder.rollback()
        def retried = redeem('LOCKED', 'user-1')

        then:
        busy.status() == 429
        busy.header('Retry-After') in ['1', '2', '3']
        busy.body().errorCode == 'REDEMPTION_BUSY'
        waitedMillis >= lockTimeout.toMillis() - 100 && waitedMillis < lockTimeout.toMillis() + 10_000
        retried.status() == 201
        currentUses('LOCKED') == 1

        cleanup:
        lockHolder?.close()
    }
}
