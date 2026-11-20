package pl.miskiewicz.coupons.coupon.application

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import pl.miskiewicz.coupons.coupon.domain.Coupon
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException
import pl.miskiewicz.coupons.coupon.domain.CouponNotYetValidException
import pl.miskiewicz.coupons.coupon.domain.CouponRepository
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot
import pl.miskiewicz.coupons.coupon.domain.CouponSuspendedException
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver
import pl.miskiewicz.coupons.coupon.domain.IdempotencyKey
import pl.miskiewicz.coupons.coupon.domain.InvalidCouponCodeException
import pl.miskiewicz.coupons.coupon.domain.InvalidIdempotencyKeyException
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException
import pl.miskiewicz.coupons.coupon.domain.LockNotAcquiredException
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt
import pl.miskiewicz.coupons.coupon.domain.Redemption
import pl.miskiewicz.coupons.coupon.domain.RedemptionCancelledException
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId
import spock.lang.Specification

class RedeemCouponServiceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-23T10:15:30.123456Z')
    static final CountryCode PL = new CountryCode('PL')
    static final CouponCode CODE = new CouponCode('WIOSNA')
    static final UserId USER = new UserId('user-1')
    static final String CLIENT = '203.0.113.7'
    static final IdempotencyKey KEY = new IdempotencyKey('order-2026-11-23-0001')
    static final Redemption REDEMPTION = new Redemption(
            UUID.fromString('019a9f3e-7c2b-7d41-9a3e-5b6c7d8e9f01'), CODE, USER, PL, NOW.minusSeconds(30))

    CouponRepository coupons = Mock()
    RedemptionStore redemptions = Mock()
    GeoLocationResolver geo = Mock()
    SimpleMeterRegistry meters = new SimpleMeterRegistry()
    RedeemCouponService service = new RedeemCouponService(
            coupons, redemptions, geo, new RedemptionBulkhead(1, 1, Duration.ZERO), new RedemptionOutcomes(meters))

    def "redeems a coupon from normalized input"() {
        when:
        def result = service.redeem(new RedeemCouponCommand(' wiosna ', ' user-1 ', CLIENT, null))

        then:
        1 * coupons.findByCode(CODE) >> snapshot(coupon())
        1 * geo.resolveCountry(InetAddress.ofLiteral(CLIENT)) >> Optional.of(PL)
        1 * redemptions.hasRedeemed(CODE, USER) >> false
        1 * redemptions.redeem(CODE, USER, PL, null) >> new RedeemAttempt.Redeemed(REDEMPTION)
        result.redemption() == REDEMPTION
        !result.replayed()
        redemptionCount('success') == 1
    }

    def "rejects #input before touching any dependency"() {
        when:
        service.redeem(command)

        then:
        thrown(expected)
        0 * _

        where:
        input                      | command                                                           || expected
        'a user id with a space'   | new RedeemCouponCommand('WIOSNA', 'john doe', CLIENT, null)        || InvalidUserIdException
        'a missing user id'        | new RedeemCouponCommand('WIOSNA', null, CLIENT, null)              || InvalidUserIdException
        'a malformed key'          | new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, 'has space')   || InvalidIdempotencyKeyException
        'an empty key'             | new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, '')            || InvalidIdempotencyKeyException
        'a non-ASCII key'          | new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, 'zamówienie')  || InvalidIdempotencyKeyException
        'a code that cannot exist' | new RedeemCouponCommand('a/b', 'user-1', CLIENT, null)             || InvalidCouponCodeException
        'a missing code'           | new RedeemCouponCommand(null, 'user-1', CLIENT, null)              || InvalidCouponCodeException
    }

    def "refuses with #expected.simpleName when #situation, without opening a redemption"() {
        given:
        coupons.findByCode(_) >> stored
        geo.resolveCountry(_) >> Optional.ofNullable(country)
        redemptions.hasRedeemed(_, _) >> usedBefore

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, null))

        then:
        thrown(expected)
        0 * redemptions.redeem(*_)

        where:
        situation                               | stored                                            | country               | usedBefore || expected
        'the coupon does not exist'             | Optional.empty()                                  | PL                    | false      || CouponNotFoundException
        'the country cannot be resolved'        | snapshot(coupon())                                | null                  | false      || CountryUnresolvedException
        'the request comes from another country' | snapshot(coupon())                               | new CountryCode('DE') | true       || CouponCountryNotAllowedException
        'the user already used it'              | snapshot(coupon(currentUses: 10))                 | PL                    | true       || CouponAlreadyUsedByUserException
        'it has expired'                        | snapshot(coupon(expiresAt: NOW))                  | PL                    | false      || CouponExpiredException
        'it is suspended'                       | snapshot(coupon(active: false))                   | PL                    | false      || CouponSuspendedException
        'it is not valid yet'                   | snapshot(coupon(validFrom: NOW.plusSeconds(60)))  | PL                    | false      || CouponNotYetValidException
        'all uses are taken'                    | snapshot(coupon(currentUses: 10))                 | PL                    | false      || CouponExhaustedException
    }

    def "treats an unparseable client address '#address' as an unresolved country"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', address, null))

        then:
        thrown(CountryUnresolvedException)
        0 * geo.resolveCountry(_)
        0 * redemptions._

        where:
        address << [null, '', 'not-an-ip', 'example.com', '203.0.113.7, 10.0.0.1']
    }

    def "when the atomic update finds no use left, the coupon is re-read and its reason given: #reason"() {
        given:
        coupons.findByCode(_) >>> [snapshot(coupon()), reread]
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.hasRedeemed(_, _) >> false
        redemptions.redeem(*_) >> new RedeemAttempt.NoUseLeft()

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, null))

        then:
        thrown(expected)
        redemptionCount(outcome) == 1

        where:
        reason                             | reread                             || expected                 | outcome
        'taken by others meanwhile'        | snapshot(coupon(currentUses: 10))  || CouponExhaustedException | 'exhausted'
        'expired meanwhile'                | snapshot(coupon(expiresAt: NOW))   || CouponExpiredException   | 'expired'
        'a use came back meanwhile, retry' | snapshot(coupon())                 || RedemptionBusyException  | 'busy'
    }

    def "a user who won a race against themselves is told they already used the coupon"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.hasRedeemed(_, _) >> false
        redemptions.redeem(*_) >> new RedeemAttempt.AlreadyRedeemed()

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, null))

        then:
        thrown(CouponAlreadyUsedByUserException)
    }

    def "asks the client to retry when the coupon row stays locked"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.hasRedeemed(_, _) >> false
        redemptions.redeem(*_) >> { throw new LockNotAcquiredException(new RuntimeException('55P03')) }

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, null))

        then:
        thrown(RedemptionBusyException)
        redemptionCount('busy') == 1
    }

    def "counts an unexpected failure as an error"() {
        given:
        coupons.findByCode(_) >> { throw new IllegalStateException('database hiccup') }

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, null))

        then:
        thrown(IllegalStateException)
        redemptionCount('error') == 1
    }

    def "replays the stored redemption for the same key, whatever changed since: #change"() {
        given:
        coupons.findByCode(_) >> snapshot(current)

        when:
        def result = service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, KEY.value()))

        then:
        1 * redemptions.findByIdempotencyKey(CODE, USER, KEY) >> Optional.of(new RedemptionRecord(REDEMPTION, null))
        result.replayed()
        result.redemption() == REDEMPTION
        0 * geo._
        0 * redemptions.hasRedeemed(*_)
        0 * redemptions.redeem(*_)
        redemptionCount('replayed') == 1

        where:
        change      | current
        'nothing'   | coupon()
        'suspended' | coupon(active: false)
        'exhausted' | coupon(currentUses: 10)
        'expired'   | coupon(expiresAt: NOW)
    }

    def "refuses to replay a redemption that was cancelled"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())
        redemptions.findByIdempotencyKey(*_) >> Optional.of(new RedemptionRecord(REDEMPTION, NOW))

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, KEY.value()))

        then:
        thrown(RedemptionCancelledException)
        0 * redemptions.redeem(*_)
        redemptionCount('redemption_cancelled') == 1
    }

    def "stores a new key with the redemption"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.findByIdempotencyKey(*_) >> Optional.empty()
        redemptions.hasRedeemed(_, _) >> false

        when:
        def result = service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, KEY.value()))

        then:
        1 * redemptions.redeem(CODE, USER, PL, KEY) >> new RedeemAttempt.Redeemed(REDEMPTION)
        !result.replayed()
    }

    def "a request whose twin with the same key committed #moment gets the twin's redemption"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.hasRedeemed(_, _) >> usedBefore
        redemptions.findByIdempotencyKey(*_) >>> [Optional.empty(), Optional.of(new RedemptionRecord(REDEMPTION, null))]
        redemptions.redeem(*_) >> new RedeemAttempt.AlreadyRedeemed()

        when:
        def result = service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, KEY.value()))

        then:
        result.replayed()
        result.redemption() == REDEMPTION

        where:
        moment                                | usedBefore
        'just before the already-used check'  | true
        'while this one was in the store'     | false
    }

    def "an already-used answer stands when #description"() {
        given:
        coupons.findByCode(_) >> snapshot(coupon())
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.hasRedeemed(_, _) >> true
        redemptions.findByIdempotencyKey(*_) >> Optional.empty()

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT, key))

        then:
        thrown(CouponAlreadyUsedByUserException)
        0 * redemptions.redeem(*_)

        where:
        description                                   | key
        'the request has no key'                      | null
        'the earlier redemption used a different key' | KEY.value()
    }

    private double redemptionCount(String outcome) {
        meters.get('coupon.redemptions').tag('outcome', outcome).counter().count()
    }

    private static Optional<CouponSnapshot> snapshot(Coupon coupon) {
        Optional.of(new CouponSnapshot(coupon, NOW))
    }

    private static Coupon coupon() {
        coupon([:])
    }

    private static Coupon coupon(Map changes) {
        new Coupon(
                CODE,
                PL,
                10,
                changes.getOrDefault('currentUses', 0) as int,
                NOW.minusSeconds(3600),
                changes.validFrom as Instant,
                changes.expiresAt as Instant,
                changes.getOrDefault('active', true) as boolean)
    }
}
