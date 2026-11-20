package pl.miskiewicz.coupons.coupon.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.Coupon;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot;
import pl.miskiewicz.coupons.coupon.domain.InvalidActiveFlagException;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;

@Service
public class CouponService {

    private final CouponRepository coupons;

    public CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    public Coupon create(CreateCouponCommand command) {
        CouponCode code = CouponCode.parse(command.code());
        CountryCode country = CountryCode.parse(command.country());
        Coupon coupon = Coupon.newCoupon(
                code,
                country,
                command.maxUses(),
                coupons.currentTime(),
                toMicros(command.validFrom()),
                toMicros(command.expiresAt()));
        if (!coupons.insertIfAbsent(coupon)) {
            throw new CouponAlreadyExistsException();
        }
        return coupon;
    }

    public Coupon get(String rawCode) {
        return coupons.findByCode(CouponCode.parse(rawCode))
                .map(CouponSnapshot::coupon)
                .orElseThrow(CouponNotFoundException::new);
    }

    public Coupon setActive(String rawCode, Boolean active) {
        CouponCode code = CouponCode.parse(rawCode);
        if (active == null) {
            throw new InvalidActiveFlagException();
        }
        return coupons.setActive(code, active)
                .orElseThrow(CouponNotFoundException::new);
    }

    private static Instant toMicros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
