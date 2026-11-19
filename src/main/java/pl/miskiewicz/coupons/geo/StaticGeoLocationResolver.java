package pl.miskiewicz.coupons.geo;

import java.net.InetAddress;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver;

@Component
@ConditionalOnProperty("coupons.geo.static-country")
class StaticGeoLocationResolver implements GeoLocationResolver {

    private final CountryCode country;

    StaticGeoLocationResolver(@Value("${coupons.geo.static-country}") String country) {
        this.country = CountryCode.parse(country);
    }

    @Override
    public Optional<CountryCode> resolveCountry(InetAddress address) {
        return Optional.of(country);
    }
}
