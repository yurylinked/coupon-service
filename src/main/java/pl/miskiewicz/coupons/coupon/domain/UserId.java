package pl.miskiewicz.coupons.coupon.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record UserId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._@:+-]{1,64}");

    public UserId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidUserIdException();
        }
    }

    public static UserId parse(String raw) {
        if (raw == null) {
            throw new InvalidUserIdException();
        }
        return new UserId(raw.strip());
    }
}
