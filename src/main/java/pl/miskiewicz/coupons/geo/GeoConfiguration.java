package pl.miskiewicz.coupons.geo;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;

@Configuration(proxyBeanMethods = false)
class GeoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "coupons.geo.provider", havingValue = "dbip", matchIfMissing = true)
    DbIpGeoLocationResolver dbIpGeoLocationResolver(@Value("${coupons.geo.dbip.database}") Path database)
            throws IOException {
        return new DbIpGeoLocationResolver(database);
    }

    @Bean
    @ConditionalOnProperty(name = "coupons.geo.provider", havingValue = "static")
    StaticGeoLocationResolver staticGeoLocationResolver(@Value("${coupons.geo.static.country}") String country) {
        return new StaticGeoLocationResolver(CountryCode.parse(country));
    }
}
