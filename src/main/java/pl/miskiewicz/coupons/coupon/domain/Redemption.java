package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Objects;

public record Redemption(RedemptionId id, CouponCode code, UserId userId, CountryCode country, Instant redeemedAt) {

    public Redemption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(redeemedAt, "redeemedAt");
    }
}
