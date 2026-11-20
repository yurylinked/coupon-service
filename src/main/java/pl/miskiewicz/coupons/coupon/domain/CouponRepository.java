package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Optional;

public interface CouponRepository {

    Instant currentTime();

    boolean insertIfAbsent(Coupon coupon);

    Optional<CouponSnapshot> findByCode(CouponCode code);

    Optional<Coupon> setActive(CouponCode code, boolean active);
}
