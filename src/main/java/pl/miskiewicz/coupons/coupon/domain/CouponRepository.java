package pl.miskiewicz.coupons.coupon.domain;

import java.util.Optional;

public interface CouponRepository {

    boolean insertIfAbsent(Coupon coupon);

    Optional<Coupon> findByCode(CouponCode code);

    Optional<Coupon> updateActive(CouponCode code, boolean active);
}
