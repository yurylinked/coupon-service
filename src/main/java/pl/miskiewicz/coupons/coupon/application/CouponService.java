package pl.miskiewicz.coupons.coupon.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.Coupon;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;

@Service
public class CouponService {

    private final CouponRepository coupons;
    private final Clock clock;

    public CouponService(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    public Coupon create(CreateCouponCommand command) {
        Coupon coupon = Coupon.newCoupon(
                CouponCode.parse(command.code()),
                CountryCode.parse(command.country()),
                command.maxUses(),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                toMicros(command.validFrom()),
                toMicros(command.expiresAt()));
        if (!coupons.insertIfAbsent(coupon)) {
            throw new CouponAlreadyExistsException();
        }
        return coupon;
    }

    public Coupon get(String rawCode) {
        return CouponCode.tryParse(rawCode)
                .flatMap(coupons::findByCode)
                .orElseThrow(CouponNotFoundException::new);
    }

    // Suspending stops new redemptions at once (the redemption UPDATE checks the flag); redemptions committed before
    // stay valid. Setting the current value again is a no-op, so operators can safely repeat the call.
    public Coupon setActive(String rawCode, boolean active) {
        return CouponCode.tryParse(rawCode)
                .flatMap(code -> coupons.updateActive(code, active))
                .orElseThrow(CouponNotFoundException::new);
    }

    private static Instant toMicros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
