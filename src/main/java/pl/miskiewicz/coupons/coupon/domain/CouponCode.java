package pl.miskiewicz.coupons.coupon.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CouponCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{3,32}");
    private static final Pattern NORMALIZED = Pattern.compile("[A-Z0-9_-]{3,32}");

    public CouponCode {
        Objects.requireNonNull(value, "value");
        if (!NORMALIZED.matcher(value).matches()) {
            throw new InvalidCouponCodeException();
        }
    }

    public static CouponCode parse(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        if (!FORMAT.matcher(stripped).matches()) {
            throw new InvalidCouponCodeException();
        }
        return new CouponCode(stripped.toUpperCase(Locale.ROOT));
    }
}
