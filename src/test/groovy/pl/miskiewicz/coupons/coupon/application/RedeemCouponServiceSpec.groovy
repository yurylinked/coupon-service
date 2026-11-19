package pl.miskiewicz.coupons.coupon.application

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import pl.miskiewicz.coupons.coupon.domain.Coupon
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException
import pl.miskiewicz.coupons.coupon.domain.CouponRepository
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException
import pl.miskiewicz.coupons.coupon.domain.Redemption
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId
import spock.lang.Specification

class RedeemCouponServiceSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-11-19T10:15:30.123456789Z')
    static final CountryCode PL = new CountryCode('PL')
    static final InetAddress CLIENT = InetAddress.ofLiteral('203.0.113.7')
    static final Coupon WIOSNA = Coupon.newCoupon(new CouponCode('WIOSNA'), PL, 10, NOW)

    CouponRepository coupons = Mock()
    RedemptionStore redemptions = Mock()
    GeoLocationResolver geo = Mock()
    RedeemCouponService service = new RedeemCouponService(coupons, redemptions, geo, Clock.fixed(NOW, ZoneOffset.UTC))

    def "redeems a coupon with normalized input and a microsecond timestamp"() {
        given:
        def expected = new Redemption(UUID.randomUUID(), WIOSNA.code(), new UserId('user-1'), PL, NOW)

        when:
        def result = service.redeem(new RedeemCouponCommand(' wiosna ', ' user-1 ', CLIENT))

        then:
        1 * coupons.findByCode(new CouponCode('WIOSNA')) >> Optional.of(WIOSNA)
        1 * geo.resolveCountry(CLIENT) >> Optional.of(PL)
        1 * redemptions.hasRedeemed(WIOSNA.code(), new UserId('user-1')) >> false
        1 * redemptions.redeem(WIOSNA.code(), new UserId('user-1'), PL, Instant.parse('2026-11-19T10:15:30.123456Z')) >> expected
        result == expected
    }

    def "reports #expected.simpleName when coupon exists=#couponExists, country=#requestCountry, used before=#usedBefore, store fails with #storeFailure.simpleName"() {
        given:
        coupons.findByCode(_) >> (couponExists ? Optional.of(WIOSNA) : Optional.empty())
        geo.resolveCountry(_) >> Optional.ofNullable(requestCountry).map { new CountryCode(it) }
        redemptions.hasRedeemed(_, _) >> usedBefore
        redemptions.redeem(*_) >> { throw storeFailure.getDeclaredConstructor().newInstance() }

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT))

        then:
        thrown(expected)

        where:
        couponExists | requestCountry | usedBefore | storeFailure                     || expected
        false        | 'PL'           | false      | CouponExhaustedException         || CouponNotFoundException
        false        | null           | true       | CouponExhaustedException         || CouponNotFoundException
        true         | null           | true       | CouponExhaustedException         || CountryUnresolvedException
        true         | 'DE'           | true       | CouponExhaustedException         || CouponCountryNotAllowedException
        true         | 'PL'           | true       | CouponExhaustedException         || CouponAlreadyUsedByUserException
        true         | 'PL'           | false      | CouponExhaustedException         || CouponExhaustedException
        true         | 'PL'           | false      | CouponAlreadyUsedByUserException || CouponAlreadyUsedByUserException
        true         | 'PL'           | false      | RedemptionBusyException          || RedemptionBusyException
    }

    def "rejects an invalid user id before touching any dependency"() {
        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'john doe', CLIENT))

        then:
        thrown(InvalidUserIdException)
        0 * _
    }

    def "does not look up a code that cannot exist"() {
        when:
        service.redeem(new RedeemCouponCommand('a/b', 'user-1', CLIENT))

        then:
        thrown(CouponNotFoundException)
        0 * _
    }

    def "does not open a redemption when the user already used the coupon"() {
        given:
        coupons.findByCode(_) >> Optional.of(WIOSNA)
        geo.resolveCountry(_) >> Optional.of(PL)
        redemptions.hasRedeemed(_, _) >> true

        when:
        service.redeem(new RedeemCouponCommand('WIOSNA', 'user-1', CLIENT))

        then:
        thrown(CouponAlreadyUsedByUserException)
        0 * redemptions.redeem(*_)
    }
}
