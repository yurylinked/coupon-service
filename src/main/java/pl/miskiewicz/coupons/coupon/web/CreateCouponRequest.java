package pl.miskiewicz.coupons.coupon.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.OffsetDateTime;
import pl.miskiewicz.coupons.coupon.application.CreateCouponCommand;

record CreateCouponRequest(
        @NotBlank String code,
        @NotNull @Positive @Max(1_000_000) Integer maxUses,
        @NotBlank String country,
        OffsetDateTime validFrom,
        OffsetDateTime expiresAt) {

    CreateCouponCommand toCommand() {
        return new CreateCouponCommand(code, maxUses, country, toInstant(validFrom), toInstant(expiresAt));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
