package pl.miskiewicz.coupons.coupon.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record CountryCode(String value) {

    private static final Pattern ALPHA_2 = Pattern.compile("[A-Za-z]{2}");
    private static final Set<String> ISO_COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    public CountryCode {
        Objects.requireNonNull(value, "value");
        if (!ISO_COUNTRIES.contains(value)) {
            throw new InvalidCountryCodeException();
        }
    }

    public static CountryCode parse(String raw) {
        return tryParse(raw).orElseThrow(InvalidCountryCodeException::new);
    }

    public static Optional<CountryCode> tryParse(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        if (!ALPHA_2.matcher(stripped).matches()) {
            return Optional.empty();
        }
        String normalized = stripped.toUpperCase(Locale.ROOT);
        return ISO_COUNTRIES.contains(normalized) ? Optional.of(new CountryCode(normalized)) : Optional.empty();
    }
}
