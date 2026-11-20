package pl.miskiewicz.coupons.coupon.application

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import pl.miskiewicz.coupons.coupon.domain.Coupon
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException
import pl.miskiewicz.coupons.coupon.domain.CouponRepository
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.InvalidRedemptionIdException
import pl.miskiewicz.coupons.coupon.domain.LockNotAcquiredException
import pl.miskiewicz.coupons.coupon.domain.Redemption
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId
import spock.lang.Specification

class RedemptionServiceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-23T09:15:30.123456Z')
    static final UUID ID = UUID.fromString('019a9f3e-7c2b-7d41-9a3e-5b6c7d8e9f01')
    static final CouponCode BOOK = new CouponCode('BOOK')
    static final Redemption REDEMPTION = new Redemption(ID, BOOK, new UserId('user-1'), new CountryCode('PL'), NOW.minusSeconds(60))
    static final RedemptionRecord ACTIVE = new RedemptionRecord(REDEMPTION, null)
    static final RedemptionRecord CANCELLED = new RedemptionRecord(REDEMPTION, NOW)

    RedemptionStore redemptions = Mock()
    CouponRepository coupons = Mock()
    SimpleMeterRegistry meters = new SimpleMeterRegistry()
    RedemptionService service = new RedemptionService(
            redemptions, coupons, new RedemptionBulkhead(1, 1, Duration.ZERO), new RedemptionOutcomes(meters))

    def "finds a redemption by its id in any letter case"() {
        given:
        redemptions.findById(ID) >> Optional.of(ACTIVE)

        expect:
        service.get(ID.toString().toUpperCase()) == ACTIVE
    }

    def "rejects '#raw' as a malformed id without a query"() {
        when:
        service.get(raw)

        then:
        thrown(InvalidRedemptionIdException)
        0 * redemptions._

        where:
        raw << [null, '', 'not-a-uuid', '1-2-3-4-5', ID.toString() + '0', ID.toString().replace('-', '')]
    }

    def "reports an unknown redemption"() {
        given:
        redemptions.findById(_) >> Optional.empty()

        when:
        service.cancel(ID.toString())

        then:
        thrown(RedemptionNotFoundException)
        cancellations('redemption_not_found') == 1
    }

    def "cancels an active redemption"() {
        when:
        service.cancel(ID.toString())

        then:
        1 * redemptions.findById(ID) >> Optional.of(ACTIVE)
        1 * redemptions.cancel(ID) >> true
        cancellations('success') == 1
    }

    def "answers a repeated cancellation without touching the coupon and counts it apart"() {
        when:
        service.cancel(ID.toString())

        then:
        1 * redemptions.findById(ID) >> Optional.of(CANCELLED)
        0 * redemptions.cancel(_)
        cancellations('already_cancelled') == 1
    }

    def "when the atomic cancel changes nothing, decides why: #reason"() {
        given:
        redemptions.findById(ID) >>> [Optional.of(ACTIVE), Optional.of(current)]
        redemptions.cancel(ID) >> false
        coupons.findByCode(BOOK) >> Optional.of(new CouponSnapshot(coupon, NOW))

        when:
        Throwable failure = null
        try {
            service.cancel(ID.toString())
        } catch (RuntimeException ex) {
            failure = ex
        }

        then:
        failure?.class == expected
        cancellations(outcome) == 1

        where:
        reason                                  | current   | coupon                       || expected                | outcome
        'another cancel committed meanwhile'    | CANCELLED | coupon(null)                 || null                    | 'already_cancelled'
        'the coupon has expired'                | ACTIVE    | coupon(NOW)                  || CouponExpiredException  | 'expired'
        'nothing explains it, so retry'         | ACTIVE    | coupon(null)                 || RedemptionBusyException | 'busy'
    }

    def "asks the caller to retry when the coupon row stays locked"() {
        given:
        redemptions.findById(ID) >> Optional.of(ACTIVE)
        redemptions.cancel(ID) >> { throw new LockNotAcquiredException(new RuntimeException('55P03')) }

        when:
        service.cancel(ID.toString())

        then:
        thrown(RedemptionBusyException)
        cancellations('busy') == 1
    }

    private double cancellations(String outcome) {
        meters.get('coupon.cancellations').tag('outcome', outcome).counter().count()
    }

    private static Coupon coupon(Instant expiresAt) {
        new Coupon(BOOK, new CountryCode('PL'), 5, 1, NOW.minusSeconds(3600), null, expiresAt, true)
    }
}
