package pl.miskiewicz.coupons.coupon.application;

import java.net.InetAddress;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.Coupon;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException;
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

@Service
public class RedeemCouponService {

    private final CouponRepository coupons;
    private final RedemptionStore redemptions;
    private final GeoLocationResolver geoLocation;
    private final Clock clock;

    public RedeemCouponService(
            CouponRepository coupons, RedemptionStore redemptions, GeoLocationResolver geoLocation, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.geoLocation = geoLocation;
        this.clock = clock;
    }

    public Redemption redeem(RedeemCouponCommand command) {
        UserId userId = UserId.parse(command.userId());
        Coupon coupon = CouponCode.tryParse(command.code())
                .flatMap(coupons::findByCode)
                .orElseThrow(CouponNotFoundException::new);
        CountryCode requestCountry = parseAddress(command.clientAddress())
                .flatMap(geoLocation::resolveCountry)
                .orElseThrow(CountryUnresolvedException::new);
        if (!coupon.country().equals(requestCountry)) {
            throw new CouponCountryNotAllowedException();
        }
        if (redemptions.hasRedeemed(coupon.code(), userId)) {
            throw new CouponAlreadyUsedByUserException();
        }
        return redemptions.redeem(
                coupon.code(), userId, requestCountry, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private static Optional<InetAddress> parseAddress(String clientAddress) {
        if (clientAddress == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(clientAddress));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
