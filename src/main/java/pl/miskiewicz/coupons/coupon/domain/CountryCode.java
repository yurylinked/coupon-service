package pl.miskiewicz.coupons.coupon.domain;

import java.util.Locale;
import java.util.Objects;
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
        if (raw == null) {
            throw new InvalidCountryCodeException();
        }
        String stripped = raw.strip();
        if (!ALPHA_2.matcher(stripped).matches()) {
            throw new InvalidCountryCodeException();
        }
        return new CountryCode(stripped.toUpperCase(Locale.ROOT));
    }
}
