package pl.miskiewicz.coupons.coupon.application

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import spock.lang.Specification

class CancelRedemptionServiceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-23T09:15:30.123456789Z')
    static final UUID REDEMPTION_ID = UUID.fromString('019a9f3e-7c2b-7d41-9a3e-5b6c7d8e9f01')
    static final CouponCode BOOK = new CouponCode('BOOK')

    RedemptionStore redemptions = Mock()
    SimpleMeterRegistry meters = new SimpleMeterRegistry()
    CancelRedemptionService service = new CancelRedemptionService(
            redemptions, new RedemptionBulkhead(1, 1, Duration.ZERO), new RedemptionOutcomes(meters), Clock.fixed(NOW, ZoneOffset.UTC))

    def "cancels a redemption at a microsecond timestamp"() {
        when:
        service.cancel(REDEMPTION_ID.toString().toUpperCase())

        then:
        1 * redemptions.findCouponCode(REDEMPTION_ID) >> Optional.of(BOOK)
        1 * redemptions.cancel(REDEMPTION_ID, Instant.parse('2026-11-23T09:15:30.123456Z'))
        cancellations('success') == 1
    }

    def "treats '#raw' as an unknown redemption without touching the store"() {
        when:
        service.cancel(raw)

        then:
        thrown(RedemptionNotFoundException)
        0 * redemptions._
        cancellations('redemption_not_found') == 1

        where:
        raw << ['', 'not-a-uuid', '1-2-3-4-5', REDEMPTION_ID.toString() + '0', REDEMPTION_ID.toString().replace('-', '')]
    }

    def "reports an unknown redemption without opening a cancellation"() {
        when:
        service.cancel(REDEMPTION_ID.toString())

        then:
        1 * redemptions.findCouponCode(REDEMPTION_ID) >> Optional.empty()
        0 * redemptions.cancel(*_)
        thrown(RedemptionNotFoundException)
    }

    def "passes on #failure.class.simpleName from the store and counts it as #outcome"() {
        given:
        redemptions.findCouponCode(REDEMPTION_ID) >> Optional.of(BOOK)
        redemptions.cancel(*_) >> { throw failure }

        when:
        service.cancel(REDEMPTION_ID.toString())

        then:
        def thrownFailure = thrown(RuntimeException)
        thrownFailure.is(failure)
        cancellations(outcome) == 1

        where:
        failure                             || outcome
        new CouponExpiredException()        || 'expired'
        new RedemptionNotFoundException()   || 'redemption_not_found'
        new RedemptionBusyException()       || 'busy'
    }

    private double cancellations(String outcome) {
        meters.get('coupon.cancellations').tag('outcome', outcome).counter().count()
    }
}
