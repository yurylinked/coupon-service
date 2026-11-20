package pl.miskiewicz.coupons.coupon.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record RedemptionId(UUID value) {

    // UUID.fromString also accepts shortened forms such as "1-2-3-4-5"; only the canonical form is an id.
    private static final Pattern CANONICAL =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public RedemptionId {
        Objects.requireNonNull(value, "value");
    }

    public static RedemptionId parse(String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            throw new InvalidRedemptionIdException();
        }
        return new RedemptionId(UUID.fromString(raw));
    }
}
