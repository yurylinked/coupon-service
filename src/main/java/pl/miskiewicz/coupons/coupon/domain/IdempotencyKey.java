package pl.miskiewicz.coupons.coupon.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

// Chosen by the client, typically its order id, and compared exactly.
public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    public static Optional<IdempotencyKey> ofNullable(String raw) {
        return raw == null ? Optional.empty() : Optional.of(new IdempotencyKey(raw));
    }
}
