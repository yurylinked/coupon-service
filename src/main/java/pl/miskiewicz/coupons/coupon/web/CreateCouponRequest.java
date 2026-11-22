package pl.miskiewicz.coupons.coupon.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import java.time.Instant;
import java.time.OffsetDateTime;
import pl.miskiewicz.coupons.coupon.application.CreateCouponCommand;

// No Bean Validation: the domain rejects each field with its own errorCode.
// Strict dates: an empty string is a mistake, not "no expiry", so it fails instead of becoming null.
record CreateCouponRequest(
        String code,
        Integer maxUses,
        String country,
        @JsonFormat(lenient = OptBoolean.FALSE) OffsetDateTime validFrom,
        @JsonFormat(lenient = OptBoolean.FALSE) OffsetDateTime expiresAt) {

    CreateCouponCommand toCommand() {
        return new CreateCouponCommand(code, maxUses, country, toInstant(validFrom), toInstant(expiresAt));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
