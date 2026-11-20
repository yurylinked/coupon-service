package pl.miskiewicz.coupons.coupon.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import pl.miskiewicz.coupons.coupon.application.CreateCouponCommand;

// Every field is validated by the domain, so each has exactly one error code.
record CreateCouponRequest(
        String code,
        Integer maxUses,
        String country,
        OffsetDateTime validFrom,
        OffsetDateTime expiresAt) {

    CreateCouponCommand toCommand() {
        return new CreateCouponCommand(code, maxUses, country, toInstant(validFrom), toInstant(expiresAt));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
