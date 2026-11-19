package pl.miskiewicz.coupons.geo;

import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver;

public final class StaticGeoLocationResolver implements GeoLocationResolver {

    private final CountryCode country;

    public StaticGeoLocationResolver(CountryCode country) {
        this.country = Objects.requireNonNull(country, "country");
    }

    @Override
    public Optional<CountryCode> resolveCountry(InetAddress address) {
        return Optional.of(country);
    }
}
