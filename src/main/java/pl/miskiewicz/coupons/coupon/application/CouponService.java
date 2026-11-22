package pl.miskiewicz.coupons.coupon.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.Coupon;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.InvalidActiveFlagException;

@Service
public class CouponService {

    private static final Logger LOG = LoggerFactory.getLogger(CouponService.class);

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
        Coupon coupon = coupons.setActive(code, active)
                .orElseThrow(CouponNotFoundException::new);
        LOG.atInfo()
                .addKeyValue("event", active ? "coupon.resumed" : "coupon.suspended")
                .addKeyValue("couponCode", code.value())
                .log(active ? "Coupon resumed" : "Coupon suspended");
        return coupon;
    }

    private static Instant toMicros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
