package pl.miskiewicz.coupons.coupon.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import pl.miskiewicz.coupons.coupon.application.CreateCouponCommand;

record CreateCouponRequest(
        @NotBlank String code,
        @NotNull @Positive @Max(1_000_000) Integer maxUses,
        @NotBlank String country,
        OffsetDateTime expiresAt) {

    CreateCouponCommand toCommand() {
        return new CreateCouponCommand(code, maxUses, country, expiresAt == null ? null : expiresAt.toInstant());
    }
}
