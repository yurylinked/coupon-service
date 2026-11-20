package pl.miskiewicz.coupons.coupon.application

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.application.RedemptionBusyException
import spock.lang.AutoCleanup
import spock.lang.Specification

class RedemptionBulkheadSpec extends Specification {

    static final CouponCode HOT = new CouponCode('HOT')

    @AutoCleanup('close')
    def executor = Executors.newVirtualThreadPerTaskExecutor()

    def "runs the action and returns its result"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 1, Duration.ZERO)

        expect:
        bulkhead.execute(HOT) { 'done' } == 'done'
        bulkhead.execute(HOT) { 'again' } == 'again'
    }

    def "rejects a call when all permits of the coupon are taken for longer than the wait limit"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 64, Duration.ofMillis(50))
        def release = holdPermit(bulkhead, HOT)

        when:
        bulkhead.execute(HOT) { 'second' }

        then:
        thrown(RedemptionBusyException)

        cleanup:
        release.countDown()
    }

    def "lets a coupon in another stripe through while a hot coupon is saturated"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 64, Duration.ZERO)
        def cold = (1..1000).collect { new CouponCode("COLD-$it") }
                .find { bulkhead.stripeIndex(it) != bulkhead.stripeIndex(HOT) }
        def release = holdPermit(bulkhead, HOT)

        expect:
        bulkhead.execute(cold) { 'cold' } == 'cold'

        cleanup:
        release.countDown()
    }

    def "returns the permit when the action fails"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 1, Duration.ZERO)

        when:
        bulkhead.execute(HOT) { throw new IllegalStateException('boom') }

        then:
        thrown(IllegalStateException)
        bulkhead.execute(HOT) { 'next' } == 'next'
    }

    def "a refused call gives back no permit it never held"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 1, Duration.ZERO)
        def release = holdPermit(bulkhead, HOT)

        when:
        def outcomes = (1..2).collect {
            try {
                bulkhead.execute(HOT) { 'granted' }
            } catch (RedemptionBusyException ignored) {
                'refused'
            }
        }

        then:
        outcomes == ['refused', 'refused']

        cleanup:
        release.countDown()
    }

    def "waits for a permit freed within the wait limit instead of refusing at once"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 1, Duration.ofSeconds(5))
        def release = holdPermit(bulkhead, HOT)
        executor.submit({
            Thread.sleep(100)
            release.countDown()
        } as Runnable)

        expect:
        bulkhead.execute(HOT) { 'after the wait' } == 'after the wait'
    }

    def "an interrupted caller is refused and keeps its interrupt flag"() {
        given:
        def bulkhead = new RedemptionBulkhead(1, 1, Duration.ofSeconds(5))
        def release = holdPermit(bulkhead, HOT)
        Thread.currentThread().interrupt()

        when:
        bulkhead.execute(HOT) { 'never' }

        then:
        thrown(RedemptionBusyException)
        Thread.interrupted()

        cleanup:
        release.countDown()
    }

    def "rejects invalid configuration: permits=#permits, stripes=#stripes, wait=#wait"() {
        when:
        new RedemptionBulkhead(permits, stripes, wait)

        then:
        thrown(IllegalArgumentException)

        where:
        permits | stripes | wait
        0       | 64      | Duration.ofMillis(200)
        4       | 0       | Duration.ofMillis(200)
        4       | 64      | Duration.ofMillis(-1)
    }

    private CountDownLatch holdPermit(RedemptionBulkhead bulkhead, CouponCode code) {
        def acquired = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        executor.submit({
            bulkhead.execute(code) {
                acquired.countDown()
                release.await()
            }
        } as Runnable)
        assert acquired.await(5, TimeUnit.SECONDS)
        release
    }
}
