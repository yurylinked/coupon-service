package pl.miskiewicz.coupons.geo;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver;
import pl.miskiewicz.coupons.coupon.domain.InvalidCountryCodeException;

public final class DbIpGeoLocationResolver implements GeoLocationResolver, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DbIpGeoLocationResolver.class);

    private final DatabaseReader reader;

    public DbIpGeoLocationResolver(Path database) throws IOException {
        this.reader = new DatabaseReader.Builder(database.toFile()).build();
    }

    @Override
    public Optional<CountryCode> resolveCountry(InetAddress address) {
        try {
            return reader.tryCountry(address)
                    .map(response -> response.country().isoCode())
                    .flatMap(DbIpGeoLocationResolver::toCountryCode);
        } catch (IOException | GeoIp2Exception ex) {
            LOG.warn("Geolocation lookup failed, country treated as unresolved", ex);
            return Optional.empty();
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private static Optional<CountryCode> toCountryCode(String isoCode) {
        try {
            return Optional.of(CountryCode.parse(isoCode));
        } catch (InvalidCountryCodeException ex) {
            return Optional.empty();
        }
    }
}
