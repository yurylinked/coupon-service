package pl.miskiewicz.coupons.coupon.application

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import spock.lang.Specification
import spock.lang.Timeout

class RedemptionBulkheadSpec extends Specification {

    static final CouponCode HOT = new CouponCode('HOT')

    def "runs the action and returns its result"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 1, Duration.ZERO))

        expect:
        bulkhead.execute(HOT) { 'done' } == 'done'
        bulkhead.execute(HOT) { 'again' } == 'again'
    }

    def "rejects a call when all permits of the coupon are taken for longer than the wait limit"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 64, Duration.ofMillis(50)))
        def held = new HeldPermit(bulkhead, HOT)

        when:
        bulkhead.execute(HOT) { 'second' }

        then:
        thrown(RedemptionBusyException)

        cleanup:
        held.close()
    }

    def "lets a coupon in another stripe through while a hot coupon is saturated"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 64, Duration.ZERO))
        def cold = (1..1000).collect { new CouponCode("COLD-$it") }
                .find { bulkhead.stripeIndex(it) != bulkhead.stripeIndex(HOT) }
        def held = new HeldPermit(bulkhead, HOT)

        expect:
        bulkhead.execute(cold) { 'cold' } == 'cold'

        cleanup:
        held.close()
    }

    def "returns the permit when the action fails"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 1, Duration.ZERO))

        when:
        bulkhead.execute(HOT) { throw new IllegalStateException('boom') }

        then:
        thrown(IllegalStateException)
        bulkhead.execute(HOT) { 'next' } == 'next'
    }

    def "a refused call gives back no permit it never held"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 1, Duration.ZERO))
        def held = new HeldPermit(bulkhead, HOT)

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
        held.close()
    }

    def "waits for a permit freed within the wait limit instead of refusing at once"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 1, Duration.ofSeconds(5)))
        def held = new HeldPermit(bulkhead, HOT)
        def result = new CompletableFuture<String>()
        def waiter = Thread.ofVirtual().start { result.complete(bulkhead.execute(HOT) { 'after the wait' }) }

        when: 'the second caller is parked on the semaphore, then the permit comes back'
        awaitState(waiter, Thread.State.TIMED_WAITING)
        held.close()

        then:
        result.get(5, TimeUnit.SECONDS) == 'after the wait'
    }

    @Timeout(5)
    def "an interrupted caller is refused at once and keeps its interrupt flag"() {
        given:
        def bulkhead = new RedemptionBulkhead(new BulkheadProperties(1, 1, Duration.ofSeconds(5)))
        def held = new HeldPermit(bulkhead, HOT)
        Thread.currentThread().interrupt()

        when:
        bulkhead.execute(HOT) { 'never' }

        then:
        thrown(RedemptionBusyException)
        Thread.currentThread().isInterrupted()

        cleanup:
        Thread.interrupted()
        held.close()
    }

    private static void awaitState(Thread thread, Thread.State state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != state) {
            assert System.nanoTime() < deadline: "thread never reached $state, last seen ${thread.state}"
            Thread.sleep(5)
        }
    }
}
