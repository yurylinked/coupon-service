package pl.miskiewicz.coupons.coupon.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.OffsetDateTime;
import pl.miskiewicz.coupons.coupon.application.CreateCouponCommand;

// Code and country are validated by their value objects, so each field has exactly one error code.
record CreateCouponRequest(
        String code,
        @NotNull @Positive @Max(1_000_000) Integer maxUses,
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
